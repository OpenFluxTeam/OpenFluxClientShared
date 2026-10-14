package io.openflux.desktop

import io.openflux.desktop.data.AppKeys
import io.openflux.desktop.model.OpenFluxBot
import kotlin.test.Test
import kotlin.test.assertEquals

class AppKeysTest {
    @Test
    fun onlyAKeyOfTheRightShapeCounts() {
        assertEquals("ofk_abcdefgh12345678", AppKeys.valid("  ofk_abcdefgh12345678\n"))
        for (bad in listOf(null, "", "ofk_", "ofk_short", "abc_abcdefgh12345678", "ofk_abcdefgh 12345678", "ofk_" + "a".repeat(129))) {
            assertEquals("", AppKeys.valid(bad), bad.toString())
        }
    }

    @Test
    fun theApiAddressMayBeMovedOnlyToThisMachine() {
        assertEquals(OpenFluxBot.API, AppKeys.pickBase(null))
        assertEquals(OpenFluxBot.API, AppKeys.pickBase("https://evil.example"))
        assertEquals(OpenFluxBot.API, AppKeys.pickBase("http://evil.example:8080"))
        assertEquals(OpenFluxBot.API, AppKeys.pickBase("http://127.0.0.1.evil.example:8080"))
        assertEquals("http://127.0.0.1:8099", AppKeys.pickBase("http://127.0.0.1:8099"))
        assertEquals("http://localhost:8099", AppKeys.pickBase("http://localhost:8099"))
    }

    @Test
    fun theIssuerMovesOnlyToThisMachine() {
        assertEquals("https://id.openflux.dev/realms/openflux", AppKeys.pickIssuer(null))
        assertEquals("https://id.openflux.dev/realms/openflux", AppKeys.pickIssuer("https://evil.example/realms/openflux"))
        assertEquals("https://id.openflux.dev/realms/openflux", AppKeys.pickIssuer("http://127.0.0.1.evil.example/realms/openflux"))
        assertEquals("http://127.0.0.1:8099/realms/openflux", AppKeys.pickIssuer("http://127.0.0.1:8099/realms/openflux/"))
    }

    @Test
    fun theDesktopClientDefaultsToOurs() = assertEquals("openflux-desktop", AppKeys.desktopClientId())
}
