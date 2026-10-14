package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * Serves the sign-in API on 127.0.0.1:8099 for a hand run of the desktop app (STUB_SERVE=1; stops when
 * <tmp>/stub-stop exists). Poll answers "approved" on its third call. Not a test of anything: without STUB_SERVE
 * it returns at once. Run the app against it with OPENFLUX_API_BASE=http://127.0.0.1:8099 and a key built in.
 */
class StubServiceServe {
    @Test
    fun serve() {
        if (System.getenv("STUB_SERVE") != "1") return
        val link = System.getenv("STUB_LINK") ?: error("STUB_LINK: an openflux:// link to answer connect/resolve with")
        var polls = 0
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 8099), 0)
        s.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val (code, json) = when (ex.requestURI.path) {
                "/api/v1/auth/app/start" -> 200 to """{"nonce":"stubnonce","deepLink":"https://t.me/openflux_bot?start=auth_stubnonce","expiresAt":"${System.currentTimeMillis() / 1000 + 300}"}"""
                "/api/v1/auth/app/poll" -> 200 to if (++polls < 3) """{"status":"pending"}""" else """{"status":"approved","auth":{"token":"ofs_stub","userId":"7","username":"stub","expiresAt":"${System.currentTimeMillis() / 1000 + 86400}"}}"""
                "/api/v1/sessions" -> 200 to """[{"sessionId":"s1","status":"running","connectToken":"StubStubStubStubStubStub"}]"""
                "/api/v1/connect/resolve" -> 200 to """{"name":"Заглушка #1","link":${JsonPrimitive(link)}}"""
                "/api/v1/subscription" -> 200 to """{"planId":"own30","planName":"Заглушка","status":"active","daysLeft":20,"isActive":true}"""
                "/api/v1/auth/logout" -> 200 to "{}"
                else -> 404 to "{}"
            }
            println("stub: ${ex.requestMethod} ${ex.requestURI.path} ${body.ifBlank { "" }} -> $code")
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        val stop = File(System.getProperty("java.io.tmpdir"), "stub-stop").also { it.delete() }
        while (!stop.exists()) Thread.sleep(500)
        s.stop(0)
    }
}
