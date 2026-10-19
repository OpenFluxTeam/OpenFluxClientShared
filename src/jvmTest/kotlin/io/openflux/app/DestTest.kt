package io.openflux.app

import io.openflux.app.nav.Dest
import io.openflux.app.nav.NavState
import io.openflux.app.nav.Section
import io.openflux.app.nav.aliveIn
import io.openflux.app.nav.rootOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestTest {
    @Test
    fun everySectionHasItsRoot() {
        for (s in Section.entries) {
            val root = rootOf(s)
            assertEquals(s, root.owner)
            assertTrue(root.isRoot)
        }
    }

    @Test
    fun owners() {
        assertEquals(Section.Home, Dest.Profile("p").owner)
        assertEquals(Section.Servers, Dest.Node("h:22:root", "of-a").owner)
        assertEquals(Section.Share, Dest.Give("p").owner)
        assertEquals("h:22:root", Dest.Node("h:22:root", "of-a").serverId)
    }

    /** A node that is not on a server has no server to be forgotten: its screens survive the check that drops those of a removed one. */
    @Test
    fun aNodeWithoutAServerIsNoScreenOfAForgottenOne() {
        for (placement in listOf("kit:manual", "kit:device", "kit:here")) {
            assertNull(Dest.NewNode(placement).serverId)
            assertNull(Dest.Node(placement, "of-a").serverId)
        }
        assertEquals("h:22:root", Dest.NewNode("h:22:root").serverId)
        val alive = aliveIn(testContainer())
        assertTrue(alive(Dest.NewNode("kit:manual")))
        assertTrue(alive(Dest.Node("kit:here", "of-a")))
        assertFalse(alive(Dest.NewNode("h:22:root")), "a server that is not known")
        assertFalse(alive(Dest.Node("h:22:root", "of-a")))
    }

    @Test
    fun stateRoundTrips() {
        val s = NavState(
            section = Section.Servers,
            stacks = NavState().stacks + (Section.Servers to listOf(Dest.Servers, Dest.Server("h:22:root"), Dest.Node("h:22:root", "of-a"))),
        )
        assertEquals(s, NavState.decode(s.encode()))
    }

    @Test
    fun decodeGarbageIsNull() {
        assertNull(NavState.decode(""))
        assertNull(NavState.decode(null))
        assertNull(NavState.decode("{not json"))
        assertNull(NavState.decode("""{"section":"Servers","stacks":{"Servers":[{"type":"no-such-screen"}]}}"""))
    }
}
