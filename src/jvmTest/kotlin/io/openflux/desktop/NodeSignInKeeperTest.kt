package io.openflux.desktop

import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.LogLevel
import io.openflux.desktop.model.NodeSignInDecision
import io.openflux.desktop.model.NodeSignInPolicy
import io.openflux.desktop.model.NodeSignInRecord
import io.openflux.desktop.model.NodeSignInState
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.service.NodeSignInAccess
import io.openflux.desktop.service.NodeSignInKeeper
import io.openflux.desktop.service.NodeSignInLedger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NodeSignInKeeperTest {
    private val ya = "https://disk.yandex.ru/i/doc"
    private val mr = "https://cloud.mail.ru/public/aa/bb"
    private val node = InstalledChannel(
        "of-x", state = "active", port = 1,
        transports = listOf(NodeTransport("vyandex", ya), NodeTransport("mailru", mr)),
    )
    private val yandexOnly = node.copy(transports = listOf(NodeTransport("vyandex", ya)))
    private val now = 10_000_000_000L

    private fun yandex(sid: String, checkedAt: Long = now, expired: Boolean = false) =
        AccountSession(AccountKind.Yandex, "me", mapOf("Session_id" to sid, "yandexuid" to "u"), checkedAt = checkedAt, expired = expired)

    private fun mailru(mpop: String) = AccountSession(AccountKind.Mailru, "me@mail.ru", mapOf("Mpop" to mpop, "sdcs" to "s"), checkedAt = now)

    private fun pushedRecord(s: AccountSession, at: Long = now - 3_600_000) = NodeSignInPolicy.pushed(NodeSignInRecord(), s.kind, s, at)

    private fun decide(kind: AccountKind, s: AccountSession?, ch: InstalledChannel, r: NodeSignInRecord?) =
        NodeSignInPolicy.decide(kind, s, ch, r, now)

    @Test
    fun theFingerprintHidesTheCookieAndFollowsOnlyTheLogin() {
        val a = NodeSignInPolicy.fingerprint(AccountKind.Yandex, mapOf("Session_id" to "secret-1", "yandexuid" to "u1"))
        val b = NodeSignInPolicy.fingerprint(AccountKind.Yandex, mapOf("Session_id" to "secret-1", "yandexuid" to "u2"))
        val c = NodeSignInPolicy.fingerprint(AccountKind.Yandex, mapOf("Session_id" to "secret-2"))
        assertEquals(64, a.length)
        assertFalse("secret" in a)
        assertEquals(a, b, "a non-login cookie is not a new sign-in")
        assertTrue(a != c)
        assertEquals("", NodeSignInPolicy.fingerprint(AccountKind.Yandex, mapOf("yandexuid" to "u")))
        assertEquals("", NodeSignInPolicy.fingerprint(AccountKind.Mailru, mapOf("Session_id" to "x")), "a Yandex login is no Mail.ru sign-in")
    }

    @Test
    fun aNodeTheAppNeverSignedInIsLeftAlone() {
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, yandex("new"), node, null))
    }

    @Test
    fun anUnchangedSignInIsNotSentAgain() {
        val s = yandex("same")
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, s, node, pushedRecord(s)))
    }

    @Test
    fun aChangedSignInIsSent() {
        val d = decide(AccountKind.Yandex, yandex("new"), node, pushedRecord(yandex("old")))
        assertIs<NodeSignInDecision.Push>(d)
    }

    @Test
    fun aSignedOutOrExpiredSessionIsNeverSent() {
        val r = pushedRecord(yandex("old"))
        val loginNode = node.copy(signin = listOf(NodeSignInState("login", now / 1000 - 600, ya)))
        assertTrue((decide(AccountKind.Yandex, yandex("new", expired = true), loginNode, r) as NodeSignInDecision.Skip).problem)
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, null, loginNode, r))
        val signedOut = AccountSession(AccountKind.Yandex, "me", mapOf("yandexuid" to "u"), checkedAt = now)
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, signedOut, loginNode, r))
    }

    @Test
    fun aMailruSignInGoesOnlyToANodeWithAMailruDocument() {
        val s = mailru("new")
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Mailru, s, yandexOnly, pushedRecord(mailru("old"))))
        assertIs<NodeSignInDecision.Push>(decide(AccountKind.Mailru, s, node, pushedRecord(mailru("old"))))
    }

    @Test
    fun theNodeSayingLoginBringsTheSameSignInAgainButNotForEver() {
        val s = yandex("same")
        val r = pushedRecord(s)
        val loginNode = node.copy(signin = listOf(NodeSignInState("login", now / 1000 - 60, ya)))
        assertIs<NodeSignInDecision.Push>(decide(AccountKind.Yandex, s, loginNode, r))
        // The node's word is older than the last push: it has not looked again yet.
        val stale = node.copy(signin = listOf(NodeSignInState("login", (r.lastTryAt - 60_000) / 1000, ya)))
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, s, stale, r.copy(lastTryAt = now - 1000)))
        // Pushed twice and still signed out: the owner has to sign in again.
        val tired = r.copy(repeats = NodeSignInPolicy.MAX_REPEATS)
        val skip = decide(AccountKind.Yandex, s, loginNode, tired)
        assertIs<NodeSignInDecision.Skip>(skip)
        assertTrue(skip.problem)
    }

    @Test
    fun aShortOutageIsNoSignInProblem() {
        val s = yandex("same")
        val r = pushedRecord(s)
        val briefly = node.copy(signin = listOf(NodeSignInState("down", now / 1000 - 60, ya)))
        val long = node.copy(signin = listOf(NodeSignInState("down", now / 1000 - NodeSignInPolicy.DOWN_GRACE_S - 1, ya)))
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, s, briefly, r))
        assertIs<NodeSignInDecision.Push>(decide(AccountKind.Yandex, s, long, r))
    }

    @Test
    fun aSessionNotCheckedLatelyIsCheckedFirst() {
        assertEquals(
            NodeSignInDecision.Recheck,
            decide(AccountKind.Yandex, yandex("new", checkedAt = now - NodeSignInPolicy.FRESH_MS - 1), node, pushedRecord(yandex("old"))),
        )
    }

    @Test
    fun failuresAreSpacedOut() {
        val r = pushedRecord(yandex("old")).copy(failures = 2, lastTryAt = now - NodeSignInPolicy.RETRY_MS)
        assertIs<NodeSignInDecision.Skip>(decide(AccountKind.Yandex, yandex("new"), node, r), "the second retry waits an hour")
        assertIs<NodeSignInDecision.Push>(decide(AccountKind.Yandex, yandex("new"), node, r.copy(lastTryAt = now - 2 * NodeSignInPolicy.RETRY_MS)))
        assertEquals(NodeSignInPolicy.MAX_RETRY_MS, NodeSignInPolicy.retryWait(20))
    }

    // ---- the keeper over a fake node ----

    private class FakeNode(var channel: InstalledChannel) : NodeSignInAccess {
        var reachable = true
        var fail: String? = null
        val pushes = mutableListOf<String>()
        override suspend fun reachable(serverId: String) = reachable
        override suspend fun show(serverId: String, channel: String) = this.channel
        override suspend fun push(serverId: String, channel: String, transports: List<NodeTransport>, cookieHeader: String): InstalledChannel {
            fail?.let { throw IllegalStateException(it) }
            pushes += cookieHeader
            this.channel = this.channel.copy(signin = emptyList())
            return this.channel
        }
    }

    private class MemLedger(override var enabled: Boolean = true) : NodeSignInLedger {
        val map = mutableMapOf<String, NodeSignInRecord>()
        override fun all() = map.toMap()
        override fun put(key: String, record: NodeSignInRecord) { map[key] = record }
    }

    private fun keeper(fake: FakeNode, ledger: MemLedger, sessions: () -> Map<AccountKind, AccountSession>, logs: MutableList<String>, recheck: AuthStatus = AuthStatus.SignedIn("me", now)) =
        NodeSignInKeeper(
            session = { sessions()[it] },
            recheck = { recheck },
            access = fake,
            ledger = ledger,
            now = { now },
            log = { text, _: LogLevel -> logs += text },
        )

    @Test
    fun theKeeperPushesAChangedSignInOnceAndLogsNoCookie() = runTest {
        val fake = FakeNode(node)
        val ledger = MemLedger()
        var sessions = mapOf(AccountKind.Yandex to yandex("v1-secret"), AccountKind.Mailru to mailru("m1-secret"))
        val logs = mutableListOf<String>()
        val k = keeper(fake, ledger, { sessions }, logs)
        k.recordPush("srv", "of-x", AccountKind.Yandex, sessions.getValue(AccountKind.Yandex))
        k.recordPush("srv", "of-x", AccountKind.Mailru, sessions.getValue(AccountKind.Mailru))
        k.tick()
        assertTrue(fake.pushes.isEmpty(), "nothing changed")
        sessions = sessions + (AccountKind.Yandex to yandex("v2-secret"))
        k.tick()
        assertEquals(1, fake.pushes.size)
        assertTrue("Session_id=v2-secret" in fake.pushes[0] && "Mpop" !in fake.pushes[0], "the Yandex sign-in only")
        k.tick()
        assertEquals(1, fake.pushes.size, "sent once")
        assertTrue(logs.none { "secret" in it }, "a cookie value in the log: $logs")
        assertTrue(k.status.value.values.none { "secret" in it.text })
    }

    @Test
    fun theKeeperAnswersTheNodeSayingItIsSignedOut() = runTest {
        val fake = FakeNode(node)
        val ledger = MemLedger()
        val s = mapOf(AccountKind.Mailru to mailru("m1"))
        val k = keeper(fake, ledger, { s }, mutableListOf())
        ledger.put(NodeSignInPolicy.key("srv", "of-x", AccountKind.Mailru), pushedRecord(s.getValue(AccountKind.Mailru), at = now - 7_200_000))
        fake.channel = node.copy(signin = listOf(NodeSignInState("down", now / 1000 - 3600, mr)))
        k.tick()
        assertEquals(1, fake.pushes.size)
        assertTrue("Mpop=m1" in fake.pushes[0])
    }

    @Test
    fun noAccessOrAFailedPushIsShownAndRetriedLater() = runTest {
        val fake = FakeNode(node)
        val ledger = MemLedger()
        var s = mapOf(AccountKind.Yandex to yandex("v1"))
        val k = keeper(fake, ledger, { s }, mutableListOf())
        k.recordPush("srv", "of-x", AccountKind.Yandex, s.getValue(AccountKind.Yandex))
        s = mapOf(AccountKind.Yandex to yandex("v2"))
        fake.reachable = false
        k.tick()
        val key = NodeSignInPolicy.key("srv", "of-x", AccountKind.Yandex)
        assertTrue(k.status.value.getValue(key).problem)
        fake.reachable = true
        fake.fail = "sudo"
        k.tick()
        assertEquals(1, ledger.map.getValue(key).failures)
        fake.fail = null
        k.tick()
        assertTrue(fake.pushes.isEmpty(), "the retry waits")
    }

    @Test
    fun aSessionTheServiceNoLongerTakesIsNotSent() = runTest {
        val fake = FakeNode(node)
        val ledger = MemLedger()
        var s = mapOf(AccountKind.Yandex to yandex("v1"))
        val k = keeper(fake, ledger, { s }, mutableListOf(), recheck = AuthStatus.Expired("me"))
        k.recordPush("srv", "of-x", AccountKind.Yandex, s.getValue(AccountKind.Yandex))
        s = mapOf(AccountKind.Yandex to yandex("v2", checkedAt = 0))
        k.tick()
        assertTrue(fake.pushes.isEmpty())
    }

    @Test
    fun switchedOffItDoesNothing() = runTest {
        val fake = FakeNode(node)
        val ledger = MemLedger(enabled = false)
        val k = keeper(fake, ledger, { mapOf(AccountKind.Yandex to yandex("v2")) }, mutableListOf())
        ledger.put(NodeSignInPolicy.key("srv", "of-x", AccountKind.Yandex), pushedRecord(yandex("v1")))
        k.tick()
        assertTrue(fake.pushes.isEmpty())
    }
}
