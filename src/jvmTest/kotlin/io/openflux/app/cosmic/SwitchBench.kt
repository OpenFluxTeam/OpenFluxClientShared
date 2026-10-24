package io.openflux.app.cosmic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.openflux.app.components.ConnectButton
import io.openflux.app.components.ConnectLook
import io.openflux.app.components.rememberWindowFocused
import io.openflux.app.components.throttledFrames
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * A window with only Home's switch, for measuring what each look costs the graphics card:
 * `SwitchBench scene|lottie|ring|video|still|empty off|on`. The switch sits as on Home: full width,
 * 430 dp tall. «lottie» plays the recording LottieSceneExport writes (-Dopenflux.lottie=<dir>, by default
 * build/lottie of the shared module). «video» is the floor of a pre-rendered loop: a new picture of the switch's
 * size every frame (decoded into one bitmap and uploaded, as a GIF or a video would be); «still» draws the same
 * picture every frame; «empty» only redraws the window.
 */
fun main(args: Array<String>) {
    val kind = args.getOrElse(0) { "scene" }
    val look = if (args.getOrElse(1) { "off" } == "on") ConnectLook.On else ConnectLook.Off
    application {
        Window(onCloseRequest = ::exitApplication, title = "SwitchBench $kind ${look.name}", state = rememberWindowState(size = DpSize(520.dp, 640.dp))) {
            Box(Modifier.fillMaxSize().background(Color(0xFF070A12)), contentAlignment = Alignment.Center) {
                val m = Modifier.fillMaxWidth().height(430.dp)
                when (kind) {
                    "lottie" -> LottieSwitch(look, "switch", {}, m)
                    "ring" -> ConnectButton(look, "switch", {})
                    "video", "still", "empty" -> Frames(kind, m)
                    else -> CosmicSwitch(look, "switch", {}, m)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Frames(kind: String, modifier: Modifier) {
    val tick = remember { mutableIntStateOf(0) }
    val focused = rememberWindowFocused()
    LaunchedEffect(Unit) { throttledFrames(focused) { tick.intValue++ } }
    val size = IntSize(650, 540)
    // Thirty different pictures, as a loop's frames would be.
    val frames = remember {
        List(30) { f ->
            IntArray(size.width * size.height) { i ->
                val x = i % size.width; val y = i / size.width
                val v = ((x + y + f * 9) and 255)
                (0xFF shl 24) or (v shl 16) or (((x * 3 + f) and 255) shl 8) or ((y + f * 5) and 255)
            }
        }
    }
    val bitmap = remember { Bitmap().apply { allocPixels(ImageInfo.makeN32(size.width, size.height, ColorAlphaType.PREMUL)) } }
    val bytes = remember { ByteArray(size.width * size.height * 4) }
    var still: androidx.compose.ui.graphics.ImageBitmap? = null
    Canvas(modifier) {
        val n = tick.intValue
        if (kind == "empty") return@Canvas
        if (kind == "video" || n == 0) {
            val src = frames[n % frames.size]
            for (i in src.indices) {
                val c = src[i]
                bytes[i * 4] = c.toByte(); bytes[i * 4 + 1] = (c shr 8).toByte(); bytes[i * 4 + 2] = (c shr 16).toByte(); bytes[i * 4 + 3] = (c shr 24).toByte()
            }
            bitmap.installPixels(bytes)
            bitmap.notifyPixelsChanged()
        }
        val image = if (kind == "still") still ?: bitmap.asComposeImageBitmap().also { still = it } else bitmap.asComposeImageBitmap()
        drawImage(image, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}
