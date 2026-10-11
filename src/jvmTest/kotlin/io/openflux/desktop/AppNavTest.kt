package io.openflux.desktop

import io.openflux.desktop.ui.nav.AppNav
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.nav.Section
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppNavTest {
    @Test
    fun startsAtHomeWithNowhereToGoBack() {
        val nav = AppNav()
        assertEquals(Section.Home, nav.section)
        assertEquals(Dest.Home, nav.top)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back(), "at Home back is the system's: leave the app")
    }

    @Test
    fun eachPlaceKeepsItsOwnStack() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.Profile("a"))
        nav.push(Dest.ProfileEdit("a"))
        nav.select(Section.Nodes)
        nav.push(Dest.Node("n"))
        // Back to Connections: where the person left, two screens deep.
        nav.select(Section.Connections)
        assertEquals(Dest.ProfileEdit("a"), nav.top)
        assertEquals(Dest.Profile("a"), nav.below)
        nav.select(Section.Nodes)
        assertEquals(Dest.Node("n"), nav.top)
    }

    @Test
    fun backGoesUpTheStackThenHome() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.Profile("a"))
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(Dest.Connections, nav.top)
        assertTrue(nav.back(), "from a place's first screen: to Home")
        assertEquals(Section.Home, nav.section)
        assertFalse(nav.back())
    }

    @Test
    fun tappingThePlaceAgainReturnsToItsFirstScreen() {
        val nav = AppNav()
        nav.select(Section.Nodes)
        nav.push(Dest.Node("n"))
        nav.push(Dest.ChannelEdit("s", "n"))
        nav.select(Section.Nodes)
        assertEquals(Dest.Nodes, nav.top)
        assertEquals(1, nav.depth)
        assertEquals(Section.Nodes, nav.section)
    }

    @Test
    fun aDetailOpensOnTheCurrentPlaceSoBackReturnsToWhereTheyCameFrom() {
        val nav = AppNav()
        nav.select(Section.Nodes)
        nav.push(Dest.Node("n"))
        // "Параметры профиля" from the node: the profile's fields open in Nodes, on top of the node.
        nav.push(Dest.ProfileEdit("n"))
        assertEquals(Section.Nodes, nav.section)
        nav.back()
        assertEquals(Dest.Node("n"), nav.top)
    }

    @Test
    fun goInSwitchesPlaceAndKeepsWhatWasThere() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.AddConnection)
        // "Своя нода" in the ways to connect: the Nodes place, the wizard on top of the nodes list.
        nav.goIn(Section.Nodes, Dest.CreateChannel("s"))
        assertEquals(Section.Nodes, nav.section)
        assertEquals(Dest.CreateChannel("s"), nav.top)
        assertEquals(Dest.Nodes, nav.below)
        nav.back()
        nav.back()
        // Back from the Nodes list is Home; the Connections stack is still where it was.
        assertEquals(Section.Home, nav.section)
        nav.select(Section.Connections)
        assertEquals(Dest.AddConnection, nav.top)
    }

    @Test
    fun theSameScreenIsNotPushedTwice() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.Profile("a"))
        nav.push(Dest.Profile("a"))
        assertEquals(2, nav.depth)
        nav.push(Dest.Profile("b"))
        assertEquals(3, nav.depth)
    }

    @Test
    fun replaceTopTurnsAFinishedWizardIntoItsResult() {
        val nav = AppNav()
        nav.goIn(Section.Nodes, Dest.CreateHosting)
        nav.replaceTop(Dest.Node("new"))
        assertEquals(Dest.Node("new"), nav.top)
        assertEquals(Dest.Nodes, nav.below)
        assertEquals(2, nav.depth)
    }

    @Test
    fun signingInReplacesTheAuthScreenWithTheAccount() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.AddConnection)
        nav.push(Dest.Auth)
        nav.popTo(Dest.AddConnection)
        nav.replaceTop(Dest.Account)
        assertEquals(Dest.Account, nav.top)
        assertEquals(Dest.Connections, nav.below, "the sign-in screens are gone; the account sits on the list")
    }

    @Test
    fun aDeletedProfileLeavesNoScreenBehind() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.Profile("a"))
        nav.push(Dest.ProfileEdit("a"))
        nav.select(Section.Nodes)
        nav.push(Dest.Node("a"))
        nav.forget("a")
        assertEquals(Dest.Nodes, nav.top)
        nav.select(Section.Connections)
        assertEquals(Dest.Connections, nav.top)
        assertEquals(1, nav.depth)
        assertNull(nav.below)
    }

    @Test
    fun pushingAFirstScreenGoesThere() {
        val nav = AppNav()
        nav.select(Section.Connections)
        nav.push(Dest.Profile("a"))
        nav.push(Dest.Settings)
        assertEquals(Section.Settings, nav.section)
        assertEquals(Dest.Settings, nav.top)
        nav.push(Dest.Logs)
        assertEquals(Dest.Settings, nav.below)
    }

    @Test
    fun everyScreenTakenOffAStackIsReported() {
        val nav = AppNav()
        val gone = mutableListOf<Dest>()
        nav.onRemoved = { gone += it }
        nav.select(Section.Nodes)
        nav.push(Dest.CreateChannel("s"))
        nav.push(Dest.Node("n"))
        nav.back()
        assertEquals(listOf<Dest>(Dest.Node("n")), gone)
        nav.replaceTop(Dest.Node("made"))
        assertEquals(listOf<Dest>(Dest.Node("n"), Dest.CreateChannel("s")), gone)
        nav.popToRoot()
        assertEquals(Dest.Node("made"), gone.last())
        nav.push(Dest.Node("x"))
        nav.forget("x")
        assertEquals(Dest.Node("x"), gone.last())
    }
}
