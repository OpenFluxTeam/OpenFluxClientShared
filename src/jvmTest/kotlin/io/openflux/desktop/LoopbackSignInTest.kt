package io.openflux.desktop

import io.openflux.desktop.data.LoopbackSignIn
import io.openflux.desktop.model.BotException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackSignInTest {
    private val held = mutableListOf<ServerSocket>()
    private val signIns = mutableListOf<LoopbackSignIn>()

    @AfterTest
    fun cleanUp() { signIns.forEach { it.closeReturn() }; held.forEach { it.close() } }

    /** Ports free right now (taken and let go): the tests never touch 47621–47625, the real app's. */
    private fun freePorts(n: Int) = List(n) { ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort } }
    private fun signIn(ports: List<Int>) = LoopbackSignIn("openflux-desktop", ports, open = {}).also { signIns += it }
    private fun hold(port: Int) { held += ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")) }
    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try { c.responseCode to (if (c.responseCode < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString().orEmpty() } finally { c.disconnect() }
    }

    @Test
    fun theFirstFreePortIsTheWayBack() = runBlocking {
        val (a, b) = freePorts(2)
        hold(a)
        assertEquals("http://127.0.0.1:$b/callback", signIn(listOf(a, b)).openReturn())
    }

    @Test
    fun theBrowsersReturnIsHandedOverAndAnsweredWithAPage() = runBlocking {
        val s = signIn(freePorts(1))
        val back = s.openReturn()
        val (code, page) = get("$back?state=st&code=c1")
        assertEquals(200, code)
        assertTrue(page.contains("OpenFlux"))
        assertEquals("$back?state=st&code=c1", withTimeout(5_000) { s.awaitReturn() })
    }

    @Test
    fun otherPathsAreNotTheWayBack() = runBlocking {
        val s = signIn(freePorts(1))
        val back = s.openReturn()
        val root = back.removeSuffix("/callback")
        assertEquals(404, get("$root/favicon.ico").first)
        assertEquals(404, get("$root/callbackX?code=c").first)
        assertNull(withTimeoutOrNull(300) { s.awaitReturn() })
    }

    @Test
    fun allPortsBusyIsSaidInWords() = runBlocking {
        val ports = freePorts(2)
        ports.forEach(::hold)
        val e = assertFailsWith<BotException> { signIn(ports).openReturn() }
        assertEquals("no_return", e.code)
        assertTrue(e.message!!.contains("заняты"))
    }

    @Test
    fun closingFreesThePort() = runBlocking {
        val port = freePorts(1).single()
        val s = signIn(listOf(port))
        s.openReturn()
        s.closeReturn()
        s.closeReturn() // twice is harmless
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
    }

    @Test
    fun aReturnFromAnEarlierAttemptIsDroppedWhenANewOneBegins() = runBlocking {
        val s = signIn(freePorts(1))
        val back = s.openReturn()
        get("$back?state=old&code=old")
        s.openReturn()
        assertNull(withTimeoutOrNull(300) { s.awaitReturn() })
    }
}
