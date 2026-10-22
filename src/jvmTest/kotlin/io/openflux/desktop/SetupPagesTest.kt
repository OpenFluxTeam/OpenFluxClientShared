package io.openflux.desktop

import io.openflux.desktop.core.IpcCookiesRequest
import io.openflux.desktop.model.SetupPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupPagesTest {
    private fun tag(s: String) = "<script>$s</script>"

    @Test
    fun theBridgeGoesBeforeThePagesOwnScriptsAndKeepsStandardsMode() {
        val s = "S"
        assertEquals("<!doctype html><html><head>${tag(s)}<title>x</title></head>", SetupPages.inject("<!doctype html><html><head><title>x</title></head>", s))
        assertEquals("<HEAD lang=ru>${tag(s)}<b>", SetupPages.inject("<HEAD lang=ru><b>", s))
        // <header> is not <head>
        assertEquals("${tag(s)}<header>h</header>", SetupPages.inject("<header>h</header>", s))
        assertEquals("<html lang=ru>${tag(s)}<body>x", SetupPages.inject("<html lang=ru><body>x", s))
        // a script in front of the doctype would be quirks mode
        assertEquals("<!DOCTYPE html>${tag(s)}<p>x", SetupPages.inject("<!DOCTYPE html><p>x", s))
        assertEquals("${tag(s)}<p>x</p>", SetupPages.inject("<p>x</p>", s))
        assertEquals(tag(s), SetupPages.inject("", s))
        // lowercasing must not move offsets (İ lowercases to two chars)
        assertEquals("<!-- İİİ --><head>${tag(s)}<b>", SetupPages.inject("<!-- İİİ --><head><b>", s))
    }

    @Test
    fun theBridgeDefinesSubmitOnceAndAnnouncesIt() {
        val js = SetupPages.bridgeScript("native(j)")
        assertTrue(js.startsWith("(function(){if(window.openfluxSubmit)return;"))
        assertTrue("window.openfluxSubmit=function(p)" in js && "native(j)" in js)
        assertTrue(SetupPages.READY_EVENT in js)
    }

    @Test
    fun theLoginHalfOfTheBridgeIsOptional() {
        val without = SetupPages.bridgeScript("native(j)")
        assertFalse("openfluxLogin" in without)
        val with = SetupPages.bridgeScript("native(j)", "login(j)")
        assertTrue(with.startsWith("(function(){if(window.openfluxSubmit)return;"))
        assertTrue("window.openfluxLogin=function(s)" in with && "login(j)" in with && SetupPages.LOGIN_DONE in with)
        // the submit half is the same, and the page is told it is ready only after both exist
        assertTrue("native(j)" in with)
        assertTrue(with.indexOf("window.openfluxLogin=") < with.indexOf(SetupPages.READY_EVENT))
    }

    @Test
    fun aLoginRequestIsAnHttpsSiteAndOneStorageKey() {
        val ok = SetupPages.parseLogin("""{"id":"3","url":"https://web.max.ru","title":"Вход в MAX","capture":{"localStorage":"__oneme_auth"}}""")
        assertEquals(SetupPages.LoginRequest("3", "https://web.max.ru", "Вход в MAX", "__oneme_auth"), ok)
        // no title: a plain one; a long one is cut
        assertEquals("Вход", SetupPages.parseLogin("""{"id":"1","url":"https://a.b/c?d#e","capture":{"localStorage":"k"}}""").title)
        assertEquals(120, SetupPages.parseLogin("""{"id":"1","url":"https://a.b","title":"${"x".repeat(500)}","capture":{"localStorage":"k"}}""").title.length)
        val bad = listOf(
            "not json",
            """{"url":"https://web.max.ru","capture":{"localStorage":"k"}}""",                       // no id
            """{"id":"1","url":"http://web.max.ru","capture":{"localStorage":"k"}}""",               // not https
            """{"id":"1","url":"javascript:alert(1)","capture":{"localStorage":"k"}}""",
            """{"id":"1","url":"https://user:pw@web.max.ru","capture":{"localStorage":"k"}}""",      // login in the address
            """{"id":"1","url":"https://web.max.ru@evil.com/","capture":{"localStorage":"k"}}""",
            """{"id":"1","url":"https://web.max.ru:99999","capture":{"localStorage":"k"}}""",
            """{"id":"1","url":"https://web.max.ru"}""",                                              // nothing to capture
            """{"id":"1","url":"https://web.max.ru","capture":{"localStorage":""}}""",
            """{"id":"1","url":"https://web.max.ru","capture":{"cookies":"all"}}""",
        )
        for (json in bad) assertFailsWith<IllegalArgumentException>(json) { SetupPages.parseLogin(json) }
    }

    @Test
    fun aCookieSignInNamesTheSitesWhoAmIPath() {
        val ok = SetupPages.parseLogin("""{"id":"4","url":"https://app.workspace.vk.ru/calls","title":"VK","capture":{"cookies":true,"signedIn":{"fetch":"/api/v2/userinfo"}}}""")
        assertEquals(SetupPages.LoginRequest("4", "https://app.workspace.vk.ru/calls", "VK", "", "/api/v2/userinfo", cookies = true), ok)
        // a query belongs to the path
        assertEquals("/api/v1/userinfo?v=2", SetupPages.parseLogin("""{"id":"4","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"/api/v1/userinfo?v=2"}}}""").signedInPath)
        val bad = listOf(
            """{"id":"1","url":"https://a.b","capture":{"cookies":true}}""",                                                    // nothing to ask
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{}}}""",
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"https://evil.com/x"}}}""",         // not the site's own path
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"//evil.com/x"}}}""",
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"api/x"}}}""",
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"/a b"}}}""",
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"/a#b"}}}""",
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"signedIn":{"fetch":"/${"x".repeat(300)}"}}}""",
            """{"id":"1","url":"https://a.b","capture":{"cookies":true,"localStorage":"k","signedIn":{"fetch":"/x"}}}""",      // one thing or the other
            """{"id":"1","url":"http://a.b","capture":{"cookies":true,"signedIn":{"fetch":"/x"}}}""",                          // the site is still https only
        )
        for (json in bad) assertFailsWith<IllegalArgumentException>(json) { SetupPages.parseLogin(json) }
    }

    @Test
    fun aPageSignInNamesTheAddressItWaitsFor() {
        val ok = SetupPages.parseLogin("""{"id":"5","url":"https://boards.yandex.ru/","title":"Доски","capture":{"page":{"match":"^https://boards\\.yandex\\.ru/whiteboard/.*hash=","hint":"Откройте доску"}}}""")
        assertTrue(ok.capturesPage)
        assertEquals("^https://boards\\.yandex\\.ru/whiteboard/.*hash=", ok.pageMatch)
        assertEquals("Откройте доску", ok.pageHint)
        assertEquals("", ok.storageKey)
        assertEquals("", ok.signedInPath)
        val re = ok.pageRegex!!
        assertTrue(re.containsMatchIn("https://boards.yandex.ru/whiteboard/?hash=abc"))
        assertFalse(re.containsMatchIn("https://boards.yandex.ru/"))
        assertFalse(re.containsMatchIn("https://evil.com/?u=https://boards.yandex.ru/whiteboard/x?hash=1"))
        // the button alone: no pattern
        val manual = SetupPages.parseLogin("""{"id":"5","url":"https://a.b","capture":{"page":true}}""")
        assertTrue(manual.capturesPage)
        assertNull(manual.pageRegex)
        assertEquals("", SetupPages.parseLogin("""{"id":"5","url":"https://a.b","capture":{"page":{}}}""").pageMatch)
        // a long hint is cut
        assertEquals(160, SetupPages.parseLogin("""{"id":"5","url":"https://a.b","capture":{"page":{"hint":"${"x".repeat(500)}"}}}""").pageHint.length)
        // cookies may ride with a page request: the match/button is the "signed in" signal and the session comes back
        val cookiePage = SetupPages.parseLogin("""{"id":"6","url":"https://pruffme.com/","title":"PRUFFME","capture":{"cookies":true,"page":{"match":"^https://pruffme\\.com/cabinet","hint":"Войдите"}}}""")
        assertTrue(cookiePage.capturesPage && cookiePage.cookies)
        assertEquals("^https://pruffme\\.com/cabinet", cookiePage.pageMatch)
        assertEquals("", cookiePage.signedInPath)
        val cookieManual = SetupPages.parseLogin("""{"id":"6","url":"https://pruffme.com/","capture":{"cookies":true,"page":true}}""")
        assertTrue(cookieManual.capturesPage && cookieManual.cookies && cookieManual.pageRegex == null)
        val bad = listOf(
            """{"id":"1","url":"https://a.b","capture":{"page":{"match":"("}}}""",                                          // not a regular expression
            """{"id":"1","url":"https://a.b","capture":{"page":{"match":"${"x".repeat(600)}"}}}""",
            """{"id":"1","url":"https://a.b","capture":{"page":false}}""",                                                  // nothing to capture
            """{"id":"1","url":"https://a.b","capture":{"page":true,"localStorage":"k"}}""",                                // one thing only
            """{"id":"1","url":"http://a.b","capture":{"page":true}}""",                                                    // https only
        )
        for (json in bad) assertFailsWith<IllegalArgumentException>(json) { SetupPages.parseLogin(json) }
    }

    @Test
    fun aPageCaptureIsPlainJsonWithTheAddressAndTheTitle() {
        val json = SetupPages.pageCapture("https://a.b/c?d=\"e\"#f", "Доска «1»")
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("https://a.b/c?d=\"e\"#f", (o["url"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Доска «1»", (o["title"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(setOf("url", "title"), o.keys)
    }

    @Test
    fun theCookieCaptureIsPlainJsonAndTheProbeAsksOnePathWithTheWindowsCookies() {
        val json = SetupPages.cookieCapture("a=1; b=\"2\"", "UA/1", "{\"email\":\"x\"}")
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("a=1; b=\"2\"", (o["cookie"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("UA/1", (o["ua"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("{\"email\":\"x\"}", (o["probe"] as kotlinx.serialization.json.JsonPrimitive).content)
        val js = SetupPages.signedInProbe("/api/v2/userinfo")
        assertTrue("fetch(\"/api/v2/userinfo\"," in js && "credentials:'include'" in js && "indexOf('json')" in js, js)
        assertTrue(js.startsWith("(async()=>{") && js.endsWith("})()"))
    }

    @Test
    fun theLoginIdSurvivesABadRequestSoThePromiseCanBeRejected() {
        assertEquals("7", SetupPages.loginId("""{"id":"7","url":"ftp://x"}"""))
        assertEquals("", SetupPages.loginId("nonsense"))
        assertEquals("", SetupPages.loginId("""{"url":"https://x.y"}"""))
    }

    @Test
    fun aSignInWindowMayOnlyReadTheSiteItWasOpenedOn() {
        val origin = SetupPages.httpsOrigin("https://Web.MAX.ru/some/page?x=1")
        assertEquals("https://web.max.ru", origin)
        assertEquals("https://web.max.ru:8443", SetupPages.httpsOrigin("https://web.max.ru:8443/"))
        assertTrue(SetupPages.isOn(origin!!, "https://web.max.ru/#/chat"))
        assertFalse(SetupPages.isOn(origin, "https://web.max.ru.evil.com/"))
        assertFalse(SetupPages.isOn(origin, "https://evil.com/web.max.ru"))
        assertFalse(SetupPages.isOn(origin, "http://web.max.ru/"))
        assertFalse(SetupPages.isOn(origin, "about:blank"))
        assertNull(SetupPages.httpsOrigin("https://"))
        assertNull(SetupPages.httpsOrigin("web.max.ru"))
    }

    @Test
    fun theLoginResultReachesThePageAsPlainJsonWhateverTheValueHolds() {
        // The value is the site's own localStorage text: quotes, newlines, a closing script tag.
        val value = "{\"token\":\"a\\\"b\",\"note\":\"line1\\nline2 </script>\"}"
        val js = SetupPages.loginResultScript("12", true, value)
        assertTrue(js.startsWith("window.${SetupPages.LOGIN_DONE}&&window.${SetupPages.LOGIN_DONE}(\"12\","))
        val arg = js.removePrefix("window.${SetupPages.LOGIN_DONE}&&window.${SetupPages.LOGIN_DONE}(\"12\",").removeSuffix(");")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(arg) as kotlinx.serialization.json.JsonObject
        assertEquals("true", parsed["ok"].toString())
        assertEquals(value, (parsed["value"] as kotlinx.serialization.json.JsonPrimitive).content)
        val failed = SetupPages.loginResultScript("12", false, error = "Назад")
        assertTrue("\"ok\":false" in failed && "Назад" in failed && "value" !in failed)
    }

    @Test
    fun aSignInWindowStartsSignedOutOnceWithAMarkerOfItsOwn() {
        val js = SetupPages.clearStorageScript(listOf("__oneme_auth", "we\"ird"))
        assertTrue("sessionStorage.getItem('__openflux_clean')" in js, "a reload after the sign-in must not wipe the new token")
        assertTrue("localStorage.removeItem" in js)
        // the keys travel as a JSON array, quotes escaped, so a key cannot break out of the script
        assertTrue("""["__oneme_auth","we\"ird"]""" in js, js)
        assertTrue(js.startsWith("(function(){") && js.endsWith("})();"))
    }

    @Test
    fun onlyLoopbackHttpIsAnOwnServerAddress() {
        assertEquals("http://127.0.0.1:41234/", SetupPages.loopbackOrigin("http://127.0.0.1:41234/setup?a=1"))
        assertEquals("http://localhost:8080/", SetupPages.loopbackOrigin("HTTP://localhost:8080"))
        assertEquals("http://[::1]:9/", SetupPages.loopbackOrigin("http://[::1]:9/x"))
        assertNull(SetupPages.loopbackOrigin("https://127.0.0.1:41234/"))
        assertNull(SetupPages.loopbackOrigin("http://example.com:80/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1:0/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1:70000/"))
        assertNull(SetupPages.loopbackOrigin("http://127.0.0.1:80@evil.com:80/"))
        assertNull(SetupPages.loopbackOrigin("http://192.168.1.1:80/"))
        assertNull(SetupPages.loopbackOrigin("javascript:alert(1)"))
        assertNull(SetupPages.loopbackOrigin("file:///etc/passwd"))
    }

    @Test
    fun aCoreBeforeTheOwnFieldStillMeansAnOwnPageWhenItSendsHtml() {
        assertTrue(IpcCookiesRequest("t", "", html = "<p>x</p>").isOwn)
        assertTrue(IpcCookiesRequest("t", "http://127.0.0.1:5/", own = true).isOwn)
        assertFalse(IpcCookiesRequest("t", "https://docs.yandex.ru/d").isOwn)
    }

    @Test
    fun aSubmissionIsReadLikeTheCoreReadsIt() {
        assertEquals(
            mapOf("token" to "abc", "port" to "8080", "on" to "true"),
            SetupPages.flatten("""{"token":"abc","port":8080,"on":true}"""),
        )
        assertEquals(mapOf("a" to "1"), SetupPages.flatten("""{"client":{"a":"1"},"node":{"b":"2"}}"""))
        assertEquals(mapOf("a" to "1"), SetupPages.flatten("""{"a":"1","o":{"x":1},"l":[1],"n":null}"""))
        assertEquals(mapOf("v" to "1.50"), SetupPages.flatten("""{"v":1.50}"""))
        assertEquals(mapOf("id" to "12345678901234567890"), SetupPages.flatten("""{"id":12345678901234567890}"""))
        assertEquals(mapOf("a" to "1"), SetupPages.flatten("""{"":"x","a":"1"}"""))
        for (bad in listOf("{}", """{"o":{}}""", """{"client":{},"node":{"b":"2"}}""", "nope", "[1]")) {
            assertFailsWith<IllegalArgumentException>(bad) { SetupPages.flatten(bad) }
        }
    }

    @Test
    fun scopedSubmissionReturnsBothHalves() {
        val flat = SetupPages.flattenScoped("""{"token":"abc"}""")
        assertEquals(mapOf("token" to "abc"), flat.client)
        assertNull(flat.node)

        val scoped = SetupPages.flattenScoped("""{"client":{"token":"a","uid":"1"},"node":{"token":"b","exit":"true"}}""")
        assertEquals(mapOf("token" to "a", "uid" to "1"), scoped.client)
        assertEquals(mapOf("token" to "b", "exit" to "true"), scoped.node)

        val clientOnly = SetupPages.flattenScoped("""{"client":{"a":"1"}}""")
        assertEquals(mapOf("a" to "1"), clientOnly.client)
        assertNull(clientOnly.node)

        assertFailsWith<IllegalArgumentException> {
            SetupPages.flattenScoped("""{"client":{},"node":{"b":"2"}}""")
        }
    }
}
