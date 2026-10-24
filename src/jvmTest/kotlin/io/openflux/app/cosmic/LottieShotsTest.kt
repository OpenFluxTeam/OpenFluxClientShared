package io.openflux.app.cosmic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** The Lottie scene as Compottie draws it, for review by eye next to the scene's own shots (SHOTS=<dir>). */
@OptIn(ExperimentalTestApi::class)
class LottieShotsTest {
    @Test
    fun frames() {
        val dir = System.getenv("SHOTS") ?: return
        for (name in listOf("moon", "sun")) {
            val text = File(lottieDir(), "$name.json").takeIf { it.exists() }?.readText() ?: return
            val composition = runBlocking { LottieCompositionSpec.JsonString(text).load() }
            for (progress in listOf(0.02f, 0.31f, 0.97f)) {
                runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1120f, 1000f), density = Density(2f)) {
                    setContent {
                        Box(Modifier.fillMaxSize().background(Color(0xFF070A12))) {
                            Image(rememberLottiePainter(composition, progress = { progress }), null, Modifier.fillMaxSize())
                        }
                    }
                    mainClock.advanceTimeBy(2_000)
                    waitForIdle()
                    ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "lottie-$name-${(progress * 100).toInt()}.png"))
                }
            }
        }
    }
}
