package io.openflux.desktop.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What both apps do with a script transport's own setup page (see
 * transport/script/setuppage.go and devhost in the core: the contract and its
 * reference). The core decides which pages are the script's own
 * ([TransportPrompt.own]); this is only how the app handles one.
 */
object SetupPages {
    /** Fired on window once window.openfluxSubmit is defined. */
    const val READY_EVENT = "openflux-ready"

    /**
     * The snippet that defines window.openfluxSubmit(payload) and fires
     * [READY_EVENT], for a bridge whose native side is called by
     * [callNative] (an expression taking the JSON text `j`, e.g.
     * `window.OpenFluxSubmit.submit(j)`). It does nothing if the function is
     * already there, so it can be put in twice: before the page's scripts
     * (an inline page) and again when the page has loaded (a page the
     * script serves itself, which cannot be edited on the way).
     *
     * With [callLogin] (the same kind of expression, e.g.
     * `window.OpenFluxSubmit.login(j)`) the page also gets
     * window.openfluxLogin(spec): see [LoginRequest]. Without it the
     * function is absent, which is how a page tells that this app cannot open
     * a sign-in window and falls back to asking for the data by hand.
     */
    fun bridgeScript(callNative: String, callLogin: String? = null): String =
        "(function(){if(window.openfluxSubmit)return;" +
            "window.openfluxSubmit=function(p){try{var j=JSON.stringify(p);$callNative}catch(e){}};" +
            (if (callLogin == null) "" else loginShim(callLogin)) +
            "setTimeout(function(){try{window.dispatchEvent(new Event('$READY_EVENT'))}catch(e){}},0)})();"

    /** window.openfluxLogin(spec) -> Promise of the captured text; [loginResultScript] settles it. */
    private fun loginShim(callLogin: String): String =
        "var ls=0,lw={};" +
            "window.openfluxLogin=function(s){return new Promise(function(ok,no){var id=String(++ls);lw[id]={ok:ok,no:no};" +
            "try{var j=JSON.stringify(Object.assign({},s,{id:id}));$callLogin}catch(e){delete lw[id];no(e)}})};" +
            "window.$LOGIN_DONE=function(id,r){var w=lw[id];if(!w)return;delete lw[id];" +
            "if(r&&r.ok)w.ok(r.value);else w.no(new Error((r&&r.error)||'cancelled'))};"

    /**
     * Puts [snippet] (a script, no tags) into an HTML document where it runs
     * before the page's own scripts and the page stays in standards mode:
     * after <head>, else after <html>, else after the doctype, else in front.
     * A script in front of the doctype would throw the page into quirks
     * mode. The core's devhost.InjectSnippet does the same.
     */
    fun inject(page: String, snippet: String): String {
        val tag = "<script>$snippet</script>"
        val lower = asciiLower(page)
        for (open in listOf("<head", "<html")) {
            val i = lower.indexOf(open)
            if (i < 0) continue
            val end = i + open.length
            // The tag must really be the tag (<header is not <head).
            if (end < lower.length && lower[end] in " \t\n\r>/") {
                val close = lower.indexOf('>', end)
                if (close >= 0) return page.substring(0, close + 1) + tag + page.substring(close + 1)
            }
        }
        if (lower.trimStart().startsWith("<!doctype")) {
            val close = lower.indexOf('>')
            if (close >= 0) return page.substring(0, close + 1) + tag + page.substring(close + 1)
        }
        return tag + page
    }

    /**
     * What a setup or settings page handed to window.openfluxSubmit, as the
     * {name: value} map the script receives. The rules are the core's
     * (script.FlattenSubmission): a flat object or {client: {...}, node: {...}}
     * (the client half is delivered); strings, numbers and booleans become
     * strings; null, objects and arrays are dropped; nothing left is an error.
     */
    fun flatten(json: String): Map<String, String> = flattenScoped(json).client

    data class ScopedSubmission(val client: Map<String, String>, val node: Map<String, String>?)

    /**
     * Like [flatten] but also returns the node half of a {client, node}
     * payload (null when the page passed a flat object or no node key).
     * Mirrors core's script.FlattenSubmissionScoped.
     */
    fun flattenScoped(json: String): ScopedSubmission {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Страница настройки передала не JSON-объект")
        }
        val clientObj = root["client"] as? JsonObject
        val source = clientObj ?: root
        val client = flattenObject(source)
        require(client.isNotEmpty()) { "Страница настройки не передала данных" }
        val node = if (clientObj != null) (root["node"] as? JsonObject)?.let { flattenObject(it) } else null
        return ScopedSubmission(client, node)
    }

    private fun flattenObject(obj: JsonObject): Map<String, String> =
        obj.entries
            .filter { it.key.isNotEmpty() }
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content } }
            .toMap()

    /** A-Z only, so every index into the result is an index into [s]. */
    private fun asciiLower(s: String): String {
        val out = CharArray(s.length) { val c = s[it]; if (c in 'A'..'Z') c + 32 else c }
        return out.concatToString()
    }

    /**
     * "scheme://host:port/" of an http page on loopback (the script's own
     * server), the prefix a page's address must keep for the bridge to stay
     * with it; null for anything else.
     */
    fun loopbackOrigin(url: String): String? {
        val m = LOOPBACK.matchEntire(url.trim()) ?: return null
        val port = m.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return "http://${m.groupValues[1]}:$port/"
    }

    /** The page function [loginResultScript] calls; defined by [bridgeScript] when it has a login half. */
    const val LOGIN_DONE = "__openfluxLoginDone"

    /**
     * A sign-in window a setup page asked for with
     * window.openfluxLogin({url, title, capture: {localStorage: key}}): the
     * app opens [url] (a site, https) on top of the page, the person signs
     * in there, the app reads [storageKey] from that site's localStorage and
     * hands its text back as the value of the page's promise (rejected when
     * the person goes back or the window times out). Only that one key of the
     * site the window was opened on is ever read, and only by the page's own
     * request; the window's address is shown to the person like any other.
     * This is for sites that keep their sign-in in localStorage instead of
     * cookies (MAX does).
     *
     * A site that signs in with cookies is asked for differently:
     * `capture: {cookies: true, signedIn: {fetch: "/path"}}`. The window waits
     * until a same-origin GET of `/path` (with the window's cookies) answers 200 with
     * JSON, which is how such a site tells that someone is signed in, then hands the
     * page [cookieCapture]'s JSON: the Cookie header the site's origin gets, the
     * window's User-Agent (a session may be bound to it) and the answer's text. This
     * gives the page the whole session of the site, HttpOnly cookies included, so a
     * page asks for it only for the site the script talks to; the window starts from
     * the site with its own storage and cookies forgotten, as above.
     *
     * [storageKey] is empty and [signedInPath] set for the second kind.
     *
     * A page is asked for with `capture: {page: {match, hint}}`: the person does something on the site (makes a
     * document, opens a board) and the window hands back the address of the page they ended on, as
     * [pageCapture]'s JSON. The window closes by itself once its address has matched the regular expression
     * [pageMatch] (Java syntax, searched in the whole address) for a moment; a bar button, "Это она", takes the
     * page the window is on whatever it matches, which is the way out when the site's addresses are not what the
     * script expected. [pageHint] is the line the bar shows. With `page: true` only the button takes the page. The
     * window may go anywhere over https (a sign-in often changes the host); nothing but the address, and the page's
     * title while the window is on the site it was opened for, is read.
     */
    data class LoginRequest(
        val id: String,
        val url: String,
        val title: String,
        val storageKey: String,
        val signedInPath: String = "",
        val capturesPage: Boolean = false,
        val pageMatch: String = "",
        val pageHint: String = "",
    ) {
        /** [pageMatch] as a regular expression; null when the page is taken by the button only. */
        val pageRegex: Regex? get() = pageMatch.takeIf { it.isNotEmpty() }?.let { Regex(it) }
    }

    private const val MAX_LOGIN_TITLE = 120
    private const val MAX_LOGIN_KEY = 128
    private const val MAX_SIGNED_IN_PATH = 256
    private const val MAX_PAGE_MATCH = 512
    private const val MAX_PAGE_HINT = 160

    /** The request's id, or "" when [json] is not even that: enough to reject the page's promise. */
    fun loginId(json: String): String = try {
        (Json.parseToJsonElement(json).jsonObject["id"] as? JsonPrimitive)?.content.orEmpty().take(32)
    } catch (e: Exception) {
        ""
    }

    /** What the page asked for; throws IllegalArgumentException (the person-facing reason) when it is not usable. */
    fun parseLogin(json: String): LoginRequest {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Страница передала не JSON-объект")
        }
        fun text(el: kotlinx.serialization.json.JsonElement?) = (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        val id = text(root["id"])
        require(id.isNotEmpty() && id.length <= 32) { "У запроса входа нет номера" }
        val url = text(root["url"]).trim()
        require(httpsOrigin(url) != null) { "Окно входа открывает только сайт по https без логина в адресе" }
        val capture = root["capture"] as? JsonObject
        val key = text(capture?.get("localStorage"))
        val withCookies = (capture?.get("cookies") as? JsonPrimitive)?.booleanOrNull == true
        val title = text(root["title"]).take(MAX_LOGIN_TITLE).ifBlank { "Вход" }
        val page = capture?.get("page")
        val withPage = page is JsonObject || (page as? JsonPrimitive)?.booleanOrNull == true
        if (withPage) {
            require(key.isEmpty() && !withCookies) { "Окну входа нужно что-то одно: ключ localStorage, куки сайта или адрес страницы" }
            val spec = page as? JsonObject
            val match = text(spec?.get("match")).trim()
            require(match.length <= MAX_PAGE_MATCH) { "capture.page.match длиннее $MAX_PAGE_MATCH знаков" }
            if (match.isNotEmpty()) {
                try {
                    Regex(match)
                } catch (e: Exception) {
                    throw IllegalArgumentException("capture.page.match не разбирается как регулярное выражение")
                }
            }
            return LoginRequest(id, url, title, "", "", true, match, text(spec?.get("hint")).take(MAX_PAGE_HINT))
        }
        if (withCookies) {
            require(key.isEmpty()) { "Окну входа нужно что-то одно: ключ localStorage или куки сайта" }
            val path = text((capture?.get("signedIn") as? JsonObject)?.get("fetch"))
            require(path.length <= MAX_SIGNED_IN_PATH && SIGNED_IN_PATH.matches(path)) {
                "Для кук нужен capture.signedIn.fetch: путь на сайте (вида /api/...), который отвечает JSON, когда вход выполнен"
            }
            return LoginRequest(id, url, title, "", path)
        }
        require(key.isNotEmpty() && key.length <= MAX_LOGIN_KEY) { "Не сказано, что забрать после входа (capture.localStorage)" }
        return LoginRequest(id, url, title, key)
    }

    /** What a page-taking window hands back (see [LoginRequest]): the page's address and its title (empty when it could not be read). */
    fun pageCapture(url: String, title: String): String =
        buildJsonObject {
            put("url", url)
            put("title", title)
        }.toString()

    /** What a cookie sign-in window hands back (see [LoginRequest]): the Cookie header the site's origin gets, the window's User-Agent and the probe's answer. */
    fun cookieCapture(cookie: String, userAgent: String, probe: String): String =
        buildJsonObject {
            put("cookie", cookie)
            put("ua", userAgent)
            put("probe", probe)
        }.toString()

    /**
     * The expression a cookie sign-in window evaluates to ask [path] of its site (same origin, with the window's
     * cookies): "v:" and the answer's text when it is 200 and JSON, else "" (not signed in yet, or the site is busy).
     */
    fun signedInProbe(path: String): String {
        val p = JsonPrimitive(path)
        return "(async()=>{try{var r=await fetch($p,{credentials:'include',headers:{'Accept':'application/json'}});" +
            "var t=r.headers.get('content-type')||'';if(r.status!==200||t.indexOf('json')<0)return '';" +
            "var b=await r.text();return b?'v:'+b:''}catch(e){return ''}})()"
    }

    /** The script that settles the page's openfluxLogin promise [id]: [value] on success, else [error]. */
    fun loginResultScript(id: String, ok: Boolean, value: String = "", error: String = ""): String {
        val result = buildJsonObject {
            put("ok", ok)
            if (ok) put("value", value) else put("error", error)
        }
        return "window.$LOGIN_DONE&&window.$LOGIN_DONE(${JsonPrimitive(id)},$result);"
    }

    /**
     * The script a sign-in window runs before the site's own, once per window: it removes [keys] from the site's
     * localStorage, so the site starts signed out whatever an earlier window left behind. A site that kept the
     * previous account's token would sign in with it at once, and the window would hand the wrong account back.
     * The marker in sessionStorage (a window's own) keeps a reload after the sign-in from wiping the new token.
     */
    fun clearStorageScript(keys: List<String>): String {
        val list = kotlinx.serialization.json.JsonArray(keys.map { JsonPrimitive(it) })
        return "(function(){try{if(sessionStorage.getItem('__openflux_clean'))return;sessionStorage.setItem('__openflux_clean','1');" +
            "var k=$list;for(var i=0;i<k.length;i++){localStorage.removeItem(k[i]);}}catch(e){}})();"
    }

    /** "https://host[:port]" of an https address (no login or password in it), the scope of what a sign-in window may read; null otherwise. */
    fun httpsOrigin(url: String): String? {
        val m = HTTPS.matchEntire(url.trim()) ?: return null
        val port = m.groupValues[2]
        if (port.isNotEmpty() && port.toIntOrNull()?.let { it in 1..65535 } != true) return null
        return "https://" + m.groupValues[1].lowercase() + if (port.isEmpty()) "" else ":$port"
    }

    /** Whether [url] is on the same origin as [origin] (a result of [httpsOrigin]). */
    fun isOn(origin: String, url: String): Boolean = httpsOrigin(url) == origin

    /** A path on the site's own origin: one slash, then address characters (no second slash, no space, no fragment). */
    private val SIGNED_IN_PATH = Regex("""/(?!/)[A-Za-z0-9._~:@%/?&=+,-]*""")

    private val HTTPS = Regex("""(?i)https://([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::(\d{1,5}))?(?:[/?#].*)?""")

    private val LOOPBACK = Regex("""(?i)http://(127\.\d{1,3}\.\d{1,3}\.\d{1,3}|localhost|\[::1\]):(\d{1,5})(?:[/?#].*)?""")
}
