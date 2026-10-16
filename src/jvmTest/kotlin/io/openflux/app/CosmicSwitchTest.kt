package io.openflux.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.openflux.app.components.ConnectLook
import io.openflux.app.cosmic.CosmicSwitch
import io.openflux.app.theme.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CosmicSwitchTest {
    @Test
    fun theBodyIsTheButton() = runComposeUiTest {
        var clicks = 0
        setContent {
            AppTheme(dark = true, touch = false) {
                CosmicSwitch(ConnectLook.Off, "Подключить", { clicks++ }, Modifier.fillMaxSize())
            }
        }
        onNodeWithContentDescription("Подключить").performClick()
        assertEquals(1, clicks)
    }

    /** With SHOTS=<dir>: each state as Home opens on it, and the switch on and off again, frame by frame. */
    @Test
    fun shots() {
        val dir = System.getenv("SHOTS") ?: return
        File(dir).mkdirs()
        for (look in ConnectLook.entries) {
            render(File(dir, "cosmic-${look.name.lowercase()}.png"), look, settle = 1500)
        }
        sequence(File(dir, "cosmic-seq"))
    }

    private fun render(file: File, look: ConnectLook, settle: Long) = runSkikoComposeUiTest(size = Size(412f * 2, 380f * 2), density = Density(2f)) {
        mainClock.autoAdvance = false
        setContent {
            AppTheme(dark = true, touch = true) {
                Box(Modifier.fillMaxSize().background(AppTheme.colors.background).padding(12.dp)) {
                    CosmicSwitch(look, "Подключить", {}, Modifier.fillMaxSize(), traffic = 0.6f)
                }
            }
        }
        mainClock.advanceTimeBy(settle)
        ImageIO.write(captureToImage().toAwtImage(), "png", file)
    }

    private fun sequence(dir: File) = runSkikoComposeUiTest(size = Size(412f * 2, 380f * 2), density = Density(2f)) {
        dir.mkdirs()
        mainClock.autoAdvance = false
        var look by mutableStateOf(ConnectLook.Off)
        setContent {
            AppTheme(dark = true, touch = true) {
                Box(Modifier.fillMaxSize().background(AppTheme.colors.background).padding(12.dp)) {
                    CosmicSwitch(look, "Подключить", {}, Modifier.fillMaxSize(), traffic = 0.6f)
                }
            }
        }
        var n = 0
        fun shoot(frames: Int) = repeat(frames) {
            mainClock.advanceTimeBy(100)
            ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "f%03d.png".format(n++)))
        }
        shoot(10)
        look = ConnectLook.Busy
        shoot(15)
        look = ConnectLook.On
        shoot(45)
        look = ConnectLook.Off
        shoot(35)
    }
}
