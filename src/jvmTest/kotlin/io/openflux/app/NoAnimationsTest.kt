package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import io.openflux.app.text.Fmt
import io.openflux.desktop.model.TrafficStats
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings → Внешний вид → «Анимации» off: Home shows nothing that ticks, so the window is not redrawn every second. */
@OptIn(ExperimentalTestApi::class)
class NoAnimationsTest {
    @Test
    fun connectedHomeShowsTheStartNotATickingTimeOrSpeed() = runComposeUiTest {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        c.settings.update { it.copy(animations = false, simpleSwitch = true) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithContentDescription("Подключить").performClick()
        conn.traffic.value = TrafficStats(downBytesPerSec = 2048, upBytesPerSec = 1024)
        waitForIdle()
        onNodeWithText("Подключено").assertIsDisplayed()
        onNodeWithText("с 00:00").assertIsDisplayed()
        onAllNodes(hasText("/с", substring = true)).assertCountEquals(0)
    }

    @Test
    fun withAnimationsTheSpeedIsLive() = runComposeUiTest {
        val conn = ScriptedConnection()
        val c = homeWorld(conn)
        c.settings.update { it.copy(simpleSwitch = true) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        onNodeWithContentDescription("Подключить").performClick()
        conn.traffic.value = TrafficStats(downBytesPerSec = 2048, upBytesPerSec = 1024)
        waitForIdle()
        onNodeWithText(Fmt.speed(2048)).assertIsDisplayed()
    }

    @Test
    fun clockIsTheTimeOfDayInTheZone() {
        assertEquals("00:00", Fmt.clock(0))
        assertEquals("14:05", Fmt.clock((14 * 3600 + 5 * 60 + 59) * 1000L))
        assertEquals("03:00", Fmt.clock(0, 3 * 3600))
        assertEquals("23:00", Fmt.clock(0, -3600))
    }
}
