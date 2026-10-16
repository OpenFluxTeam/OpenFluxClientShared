package io.openflux.app

import io.openflux.app.nav.AppNavigator
import io.openflux.app.nav.Dest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.service.AppContainer
import kotlin.test.Test

/** The pictures in the READMEs (SHOTS=<dir>): Home in each state on a phone and in a window, and the other places. */
class ReadmeShotsTest {
    private fun world(state: ConnectionState? = null, vararg stack: Dest): AppContainer {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        if (state != null) conn.state.value = state
        if (state is ConnectionState.Connected) {
            conn.traffic.value = TrafficStats(
                downBytesPerSec = 1_250_000, upBytesPerSec = 42_000, totalDown = 52_000_000,
                activeTransport = "vyandex", activeTransports = listOf("vyandex"), live = true,
            )
        }
        if (stack.isNotEmpty()) {
            val n = AppNavigator().apply { stack.forEach { open(it) } }
            c.settings.update { it.copy(navState = n.snapshot().encode()) }
        }
        return c
    }

    private val amsterdam = Profile(id = "a", name = "Амстердам")
    private val connected get() = ConnectionState.Connected(amsterdam, ConnectionMode.Client, 0)
    private val connecting get() = ConnectionState.Connecting(amsterdam, ConnectionMode.Client, 0)

    private fun shot(name: String, c: AppContainer, frame: Shots.Frame = Shots.PHONE) =
        Shots.render(name, frame) { OpenFluxApp(c, NoScrollbars, Shortcuts()) }

    @Test fun phoneOff() = shot("readme-home-off", world())
    @Test fun phoneBusy() = shot("readme-home-busy", world(connecting))
    @Test fun phoneOn() = shot("readme-home-on", world(connected))
    @Test fun phoneSettings() = shot("readme-settings", world(null, Dest.Settings))
    @Test fun phoneShare() = shot("readme-share", world(connected, Dest.Share))
    @Test fun desktopOn() = shot("readme-desktop-on", world(connected), Shots.WIDE)
    @Test fun desktopOff() = shot("readme-desktop-off", world(), Shots.WIDE)
    @Test fun desktopSettings() = shot("readme-desktop-settings", world(null, Dest.Settings), Shots.WIDE)
}
