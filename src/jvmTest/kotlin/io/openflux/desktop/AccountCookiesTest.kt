package io.openflux.desktop

import io.openflux.desktop.model.YandexBoards
import io.openflux.desktop.model.DocumentKind
import io.openflux.desktop.model.AccountCookies
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AccountSession
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.InstalledScript
import io.openflux.desktop.model.ScriptAuth
import io.openflux.desktop.model.parseScriptAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountCookiesTest {
    private val yandex = mapOf("Session_id" to "s", "yandex_login" to "ivan.petrov", "yandexuid" to "1")

    @Test
    fun kindsByTransport() {
        assertEquals(AccountKind.Yandex, AccountKind.of(TransportType.VYANDEX))
        assertEquals(AccountKind.Yandex, AccountKind.of(TransportType.YANDEX))
        assertEquals(AccountKind.Yandex, AccountKind.of(TransportType.BOARDS))
        assertEquals(AccountKind.Mailru, AccountKind.of(TransportType.MAILRU))
        assertEquals(AccountKind.Max, AccountKind.of(TransportType.ONEME))
        assertNull(AccountKind.of(TransportType.CUPSONLINE))
        assertNull(AccountKind.of(TransportType.DIRECT))
    }

    @Test
    fun documentKindsByTransport() {
        assertEquals(DocumentKind.YandexDocument, DocumentKind.of(TransportType.VYANDEX))
        assertEquals(DocumentKind.YandexDocument, DocumentKind.of(TransportType.YANDEX))
        assertEquals(DocumentKind.YandexBoard, DocumentKind.of(TransportType.BOARDS))
        assertEquals(DocumentKind.MailruDocument, DocumentKind.of(TransportType.MAILRU))
        assertNull(DocumentKind.of(TransportType.CUPSONLINE))
    }

    @Test
    fun boardLinks() {
        val board = "https://boards.yandex.ru/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"
        assertEquals(board, YandexBoards.clean("$board&from=cabinet#slide"))
        assertEquals(board, YandexBoards.clean("https://boards.yandex.ru/whiteboard/?from=x&hash=820d6571111dffeb9e85de65ccfc880a"))
        assertNull(YandexBoards.clean("https://evil.example/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"))
        assertNull(YandexBoards.clean("https://boards.yandex.ru/cabinet/"))
    }

    @Test
    fun yandexLoginAndSignedIn() {
        assertTrue(AccountCookies.signedIn(AccountKind.Yandex, yandex))
        assertEquals("ivan.petrov", AccountCookies.login(AccountKind.Yandex, yandex))
        assertFalse(AccountCookies.signedIn(AccountKind.Yandex, mapOf("yandexuid" to "1")))
        assertEquals("", AccountCookies.login(AccountKind.Yandex, mapOf("Session_id" to "s")))
    }

    @Test
    fun mailruLoginFromMpop() {
        val jar = mapOf("Mpop" to "1700000000:abcdef:ivan@mail.ru:")
        assertTrue(AccountCookies.signedIn(AccountKind.Mailru, jar))
        assertEquals("ivan@mail.ru", AccountCookies.login(AccountKind.Mailru, jar))
    }

    @Test
    fun headerRoundTrip() {
        assertEquals(mapOf("a" to "1", "b" to "2=3"), AccountCookies.parseHeader("a=1; b=2=3; ;c; a=9"))
        assertEquals("a=1; b=2", AccountCookies.header(mapOf("a" to "1", "b" to "2")))
    }

    @Test
    fun sessionToStringHidesCookies() {
        val s = AccountSession(AccountKind.Yandex, "ivan.petrov", yandex, signedInAt = 1, checkedAt = 1)
        assertFalse("Session_id" in s.toString() || "=s" in s.toString())
        assertTrue("ivan.petrov" in s.toString())
    }

    @Test
    fun seedPutsAccountIntoEveryYandexCarrierAndKeepsOtherKeys() {
        val profile = Profile(
            id = "p1", name = "n", transport = TransportType.VYANDEX,
            value = " https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA ", session = true, secret = "0123456789abcdef",
            extras = listOf(
                ExtraTransport(TransportType.DIRECT, "1.2.3.4:8445"),
                ExtraTransport(TransportType.MAILRU, "https://cloud.mail.ru/public/x/y"),
            ),
        )
        val store = mapOf(
            "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA" to mapOf("spravka" to "old", "Session_id" to "stale"),
            "other" to mapOf("a" to "b"),
        )
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "ivan.petrov", yandex, 1, 1))
        val seeded = AccountCookies.seed(store, profile, sessions)
        val doc = seeded.getValue("https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA")
        assertEquals("s", doc["Session_id"])
        assertEquals("old", doc["spravka"])
        assertEquals(mapOf("a" to "b"), seeded["other"])
        assertNull(seeded["https://cloud.mail.ru/public/x/y"])
        assertNull(seeded["1.2.3.4:8445"])
    }

    @Test
    fun seedGivesTheMailruAccountToItsDocumentBecauseCloudWantsALogin() {
        val url = "https://cloud.mail.ru/public/zMYY/5djxyNJEY"
        val profile = Profile(id = "p", name = "n", transport = TransportType.MAILRU, value = url)
        val mail = mapOf("Mpop" to "a:b:ivan@mail.ru:", "t" to "x")
        val sessions = mapOf(AccountKind.Mailru to AccountSession(AccountKind.Mailru, "ivan@mail.ru", mail, 1, 1))
        val store = mapOf(url to mapOf("captcha" to "ok"), "other" to mapOf("a" to "b"))
        val seeded = AccountCookies.seed(store, profile, sessions)
        assertEquals(mail + ("captcha" to "ok"), seeded[url])
        assertEquals(mapOf("a" to "b"), seeded["other"])
        // Signed out: nothing is added, and what the jar had stays.
        assertEquals(store[url], AccountCookies.seed(store, profile, emptyMap())[url])
    }

    @Test
    fun aBoardIsJoinedAsAGuestWithoutTheAccount() {
        val board = "https://boards.yandex.ru/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"
        val doc = "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA"
        val profile = Profile(
            id = "p", name = "n", transport = TransportType.VYANDEX, value = doc, session = true, secret = "0123456789abcdef",
            extras = listOf(ExtraTransport(TransportType.BOARDS, board)),
        )
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "i", yandex, 1, 1))
        val seeded = AccountCookies.seed(mapOf(board to yandex), profile, sessions)
        assertEquals("s", seeded.getValue(doc)["Session_id"])
        assertNull(seeded[board])
        assertEquals(setOf(TransportType.VYANDEX, TransportType.YANDEX), AccountKind.Yandex.signedInTransports)
        assertEquals(setOf(TransportType.MAILRU), AccountKind.Mailru.signedInTransports)
    }

    @Test
    fun seedSkipsExpiredSessions() {
        val profile = Profile(id = "p", name = "n", value = "https://docs.yandex.ru/edit/d/AAAAAAAAAAAAAAAAAAAA")
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "x", yandex, 1, 1, expired = true))
        assertEquals(emptyMap(), AccountCookies.seed(emptyMap(), profile, sessions))
    }

    private fun script(id: String, auth: ScriptAuth?) =
        InstalledScript(id = id, name = id, pubkeyHex = "aa", fingerprint = "f", fileName = "$id.flux", auth = auth)

    @Test
    fun aScriptTransportGetsOnlyTheCookiesItDeclaresUnderTheCoresKey() {
        val url = "https://cloud.mail.ru/public/zMYY/5djxyNJEY"
        val mail = mapOf("Mpop" to "a:b:ivan@mail.ru:", "sdcs" to "d", "t" to "x", "tracking" to "y")
        val scripts = mapOf(
            "mailru" to script("mailru", ScriptAuth(account = "mailru", cookies = listOf("Mpop", "sdcs"))),
            "free" to script("free", null),
        )
        val profile = Profile(
            id = "p", name = "n", transport = TransportType.SCRIPT, value = url, session = true, secret = "0123456789abcdef",
            scriptId = "mailru",
            extras = listOf(ExtraTransport(TransportType.SCRIPT, "https://free.example/x", scriptId = "free")),
        )
        val sessions = mapOf(AccountKind.Mailru to AccountSession(AccountKind.Mailru, "ivan@mail.ru", mail, 1, 1))
        val seeded = AccountCookies.seed(mapOf("script:mailru $url" to mapOf("captcha" to "ok")), profile, sessions, scripts = scripts::get)
        assertEquals(mapOf("captcha" to "ok", "Mpop" to "a:b:ivan@mail.ru:", "sdcs" to "d"), seeded["script:mailru $url"])
        // a script that declares no sign-in gets none
        assertNull(seeded["script:free https://free.example/x"])
        assertNull(seeded[url])
    }

    @Test
    fun aScriptThatNeedsTheSignInOnlyToCreateIsJoinedWithoutIt() {
        val board = "https://boards.yandex.ru/whiteboard/?hash=820d6571111dffeb9e85de65ccfc880a"
        val scripts = mapOf("boards" to script("boards", ScriptAuth(account = "yandex", need = "create")))
        val profile = Profile(id = "p", name = "n", transport = TransportType.SCRIPT, value = board, scriptId = "boards")
        val sessions = mapOf(AccountKind.Yandex to AccountSession(AccountKind.Yandex, "i", yandex, 1, 1))
        val key = "script:boards $board"
        assertEquals(mapOf("x" to "1"), AccountCookies.seed(mapOf(key to yandex + ("x" to "1")), profile, sessions, scripts = scripts::get)[key])
    }

    @Test
    fun theCoresAuthReportIsRead() {
        val report = Json.parseToJsonElement(
            """{"auth":{"account":"yandex","label":"Яндекс","need":"create","signInUrl":"https://passport.yandex.ru/auth",
                "cookieUrls":["https://yandex.ru/"],"loginCookie":"Session_id","cookies":["Session_id","sessionid2"]}}""",
        ) as JsonObject
        val auth = parseScriptAuth(report["auth"])!!
        assertEquals("yandex", auth.account)
        assertFalse(auth.opensSignedIn)
        assertEquals(listOf("Session_id", "sessionid2"), auth.cookies)
        assertEquals(AccountKind.Yandex, AccountKind.ofAccount(auth.account))
        assertNull(parseScriptAuth(null))
    }
}
