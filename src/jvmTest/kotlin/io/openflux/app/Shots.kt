package io.openflux.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.openflux.app.theme.AppTheme2
import java.io.File
import javax.imageio.ImageIO

/** Renders the new UI off-screen; with SHOTS=<dir> writes <name>[-light][-small|-wide].png there. */
@OptIn(ExperimentalTestApi::class)
object Shots {
    data class Frame(val widthDp: Float, val heightDp: Float, val fontScale: Float, val suffix: String)

    val PHONE = Frame(412f, 870f, 1f, "")
    val SMALL = Frame(320f, 640f, 1.3f, "-small")
    val WIDE = Frame(1280f, 800f, 1f, "-wide")
    private const val SCALE = 2f

    fun render(name: String, frame: Frame = PHONE, touch: Boolean = frame != WIDE, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            runSkikoComposeUiTest(
                size = Size(frame.widthDp * SCALE, frame.heightDp * SCALE),
                density = Density(SCALE, frame.fontScale),
            ) {
                mainClock.autoAdvance = false
                setContent {
                    AppTheme2(dark, touch) {
                        Box(Modifier.fillMaxSize().background(AppTheme2.colors.background)) { content() }
                    }
                }
                mainClock.advanceTimeBy(400)
                val image = captureToImage().toAwtImage()
                val dir = System.getenv("SHOTS") ?: return@runSkikoComposeUiTest
                File(dir).mkdirs()
                ImageIO.write(image, "png", File(dir, name + (if (dark) "" else "-light") + frame.suffix + ".png"))
            }
        }
    }
}
