package io.openflux.desktop

import io.openflux.desktop.data.RedirectSignIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedirectSignInTest {
    private val redirect = "io.openflux.client:/oauth2redirect"
    private val opened = mutableListOf<String>()
    private val s = RedirectSignIn("openflux-android", redirect, open = { opened += it })

    @Test
    fun theWayBackIsTheRegisteredAddress() = runBlocking { assertEquals(redirect, s.openReturn()) }

    @Test
    fun aReturnOnTheAddressIsTakenAndHandedOver() = runBlocking {
        s.openReturn()
        assertTrue(s.deliver("$redirect?code=c&state=s"))
        assertEquals("$redirect?code=c&state=s", s.awaitReturn())
    }

    @Test
    fun otherLinksAreNotTaken() {
        assertFalse(s.deliver("openflux://abc"))
        assertFalse(s.deliver("https://link.openflux.dev/AAAAAAAAAAAAAAAAAAAA"))
        assertFalse(s.deliver("io.openflux.client:/oauth2redirectX?code=c"))
    }

    @Test
    fun aReturnFromAnEarlierAttemptIsDroppedWhenANewOneBegins() = runBlocking {
        s.deliver("$redirect?code=old&state=old")
        s.openReturn()
        assertNull(withTimeoutOrNull(200) { s.awaitReturn() })
    }

    @Test
    fun thePageOpensThroughTheGivenOpener() {
        s.openPage("https://id.openflux.dev/x")
        assertEquals(listOf("https://id.openflux.dev/x"), opened)
    }
}
