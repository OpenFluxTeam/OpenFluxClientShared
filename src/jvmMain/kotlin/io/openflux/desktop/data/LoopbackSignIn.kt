package io.openflux.desktop.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxId
import io.openflux.desktop.service.SignInSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The way back on a computer (RFC 8252 §7.3): for one sign-in the app listens on 127.0.0.1 at the first free port of
 * [ports], the page returns the browser to http://127.0.0.1:<port>/callback. The service checks the address exactly,
 * so each of [PORTS] is registered with it.
 */
class LoopbackSignIn(
    override val clientId: String,
    private val ports: List<Int> = PORTS,
    override val issuer: String = OpenFluxId.ISSUER,
    private val open: (String) -> Unit,
) : SignInSupport {
    private val arrivals = Channel<String>(Channel.CONFLATED)
    private var server: HttpServer? = null

    override fun newAttempt() = Pkce.newAttempt()

    override suspend fun openReturn(): String = withContext(Dispatchers.IO) {
        closeReturn()
        while (arrivals.tryReceive().isSuccess) Unit
        val host = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        for (port in ports) {
            val s = try {
                HttpServer.create(InetSocketAddress(host, port), 0)
            } catch (e: IOException) {
                continue
            }
            s.createContext("/") { answer(it, port) }
            s.start()
            synchronized(this@LoopbackSignIn) { server = s }
            return@withContext "http://127.0.0.1:$port$PATH"
        }
        throw BotException("no_return", "Не получилось начать вход: порты ${ports.first()}–${ports.last()} на этом компьютере заняты. Закройте лишние программы и повторите")
    }

    private fun answer(ex: HttpExchange, port: Int) {
        try {
            if (ex.requestMethod != "GET" || ex.requestURI.rawPath != PATH) {
                ex.sendResponseHeaders(404, -1)
                return
            }
            arrivals.trySend("http://127.0.0.1:$port$PATH?${ex.requestURI.rawQuery.orEmpty()}")
            val page = PAGE.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.responseHeaders.add("Referrer-Policy", "no-referrer")
            ex.sendResponseHeaders(200, page.size.toLong())
            ex.responseBody.write(page)
        } finally {
            ex.close()
        }
    }

    override suspend fun awaitReturn(): String = arrivals.receive()

    override fun openPage(url: String) = open(url)

    override fun closeReturn() {
        val s = synchronized(this) { server.also { server = null } }
        s?.stop(0)
    }

    companion object {
        /** Registered with the service as http://127.0.0.1:<port>/callback, all five. */
        val PORTS = (47621..47625).toList()
        const val PATH = "/callback"
        private const val PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>OpenFlux</title></head>
<body style="font-family:system-ui,sans-serif;max-width:32em;margin:4em auto;padding:0 1em">
<h1>OpenFlux</h1><p>Вход передан в приложение. Эту вкладку можно закрыть.</p></body></html>"""
    }
}
