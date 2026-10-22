package io.openflux.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import io.openflux.app.platform.NoScrollbars
import io.openflux.app.platform.Shortcuts
import io.openflux.app.shell.OpenFluxApp
import kotlin.test.Test
import kotlin.test.assertTrue

/** Home's Клиент / Выходная нода switch sits under the button it switches, on both tabs. */
@OptIn(ExperimentalTestApi::class)
class HomeModeSwitchTest {
    @Test
    fun theSwitchIsUnderTheConnectButtonAndUnderTheNodesButton() = runComposeUiTest {
        val c = homeWorld()
        c.settings.update { it.copy(simpleSwitch = true) }
        setContent { OpenFluxApp(c, NoScrollbars, Shortcuts()) }
        val button = onNodeWithContentDescription("Подключить").getBoundsInRoot()
        val client = onNodeWithText("Клиент").getBoundsInRoot()
        assertTrue(client.top >= button.bottom, "the switch is under the connect button")

        onNodeWithText("Выходная нода").performClick()
        val start = onNodeWithContentDescription("Запустить ноду").getBoundsInRoot()
        val back = onNodeWithText("Клиент").performScrollTo().getBoundsInRoot()
        assertTrue(back.top >= start.bottom, "the switch is under the node's start button")

        onNodeWithText("Клиент").performClick()
        onNodeWithContentDescription("Подключить").assertIsDisplayed()
    }
}
