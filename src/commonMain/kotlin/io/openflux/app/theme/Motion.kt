package io.openflux.app.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Whether the app animates (Settings → Внешний вид → «Анимации»). Off, nothing moves by itself: the switch stands
 * still, a status dot does not breathe, a busy bar is a still bar; the desktop also makes every transition instant
 * (a zero MotionDurationScale over the window, see main.kt). The window is then drawn only when something changes.
 */
val LocalAnimations = androidx.compose.runtime.compositionLocalOf { true }

/** The springs everything moves on: quick, with no wobble a person would notice. */
object Motion {
    fun <T> soft() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
}

/** Sinks a little under a finger or a pressed mouse button and comes back with a spring. */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f, hovered: Float = 1f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        when {
            isPressed -> pressed
            isHovered -> hovered
            else -> 1f
        },
        Motion.bouncy(),
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Fades in and rises a few dp the first time it is shown: a banner or a note that has just appeared. */
fun Modifier.appear(delayMillis: Int = 0): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(280, delayMillis, FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}

/**
 * The page with two faint lights of the accent, fixed to the window rather than to the page: two panes side by
 * side and a screen sliding in show one continuous glow.
 */
fun Modifier.appBackground(): Modifier = composed {
    val c = AppTheme.colors
    val window = LocalWindowInfo.current.containerSize
    val origin = remember { mutableStateOf(Offset.Zero) }
    val strength = if (c.isDark) 1f else 0.6f
    onPlaced { origin.value = it.positionInRoot() }.drawBehind {
        drawRect(c.background)
        val w = window.width.toFloat().takeIf { it > 0f } ?: size.width
        val h = window.height.toFloat().takeIf { it > 0f } ?: size.height
        val o = origin.value
        val r = maxOf(w, h) * 0.55f
        val top = Offset(w * 0.85f, -h * 0.05f) - o
        val bottom = Offset(w * 0.05f, h * 1.02f) - o
        drawRect(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.13f * strength), Color.Transparent), top, r))
        drawRect(Brush.radialGradient(listOf(c.accentEnd.copy(alpha = 0.09f * strength), Color.Transparent), bottom, r * 0.9f))
    }
}
