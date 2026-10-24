package io.openflux.app.cosmic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.alexzhirkevich.compottie.LottieComposition
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import io.openflux.app.components.ConnectLook
import io.openflux.app.components.rememberWindowFocused
import io.openflux.app.components.throttledFrames
import java.io.File

/** Where LottieSceneExport writes moon.json and sun.json; the bench reads them there (-Dopenflux.lottie=<dir>). */
fun lottieDir(): File = File(System.getProperty("openflux.lottie") ?: System.getenv("LOTTIE_OUT") ?: "build/lottie")

/**
 * Home's switch from Lottie, for the GPU bench only (the app draws the scene): the scene itself recorded (LottieSceneExport writes moon.json and sun.json from the
 * same universe, camera and shaders), played instead of drawn. The Moon while off, the Sun while connected, the
 * two crossfading; connecting shows the Sun through the Moon, a failure glows red. The files are stepped at the
 * scene's own rate ([throttledFrames]), so the two can be compared fairly; with «Анимации» off they stand still.
 */
@Composable
fun LottieSwitch(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val moon by rememberLottieComposition { LottieCompositionSpec.JsonString(File(lottieDir(), "moon.json").readText()) }
    val sun by rememberLottieComposition { LottieCompositionSpec.JsonString(File(lottieDir(), "sun.json").readText()) }
    val seconds = remember { mutableFloatStateOf(0f) }
    val animate = io.openflux.app.theme.LocalAnimations.current
    val scene = LocalCosmicScene.current
    val focused = rememberWindowFocused()
    LaunchedEffect(animate, scene) {
        if (!animate || !scene) return@LaunchedEffect
        throttledFrames(focused) { dt -> seconds.floatValue += dt }
    }
    val sunShare by animateFloatAsState(
        when (look) { ConnectLook.On -> 1f; ConnectLook.Busy -> 0.45f; else -> 0f },
        if (animate) tween(900) else tween(0),
    )
    val red by animateFloatAsState(if (look == ConnectLook.Error) 1f else 0f, if (animate) tween(400) else tween(0))
    val moonPainter = rememberLottiePainter(moon, progress = { progressAt(moon, seconds.floatValue) })
    val sunPainter = rememberLottiePainter(sun, progress = { progressAt(sun, seconds.floatValue) })
    Box(
        modifier.drawBehind {
            if (red > 0f) drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFF3B3B).copy(alpha = 0.45f * red), Color.Transparent), center, size.minDimension / 3),
                radius = size.minDimension / 3, center = Offset(size.width / 2, size.height / 2),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (sunShare < 1f) Image(moonPainter, null, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - sunShare * 0.6f })
        if (sunShare > 0f) Image(sunPainter, null, Modifier.fillMaxSize().graphicsLayer { alpha = sunShare })
        // The body is the button, as in the drawn scene.
        Box(
            Modifier.fillMaxSize(0.32f).clip(CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        )
    }
}

/** Where a looping file is at [seconds]: each file has its own length (the body's turn). */
private fun progressAt(c: LottieComposition?, seconds: Float): Float {
    if (c == null) return 0f
    val length = c.durationFrames / c.frameRate
    return if (length > 0f) (seconds % length) / length else 0f
}
