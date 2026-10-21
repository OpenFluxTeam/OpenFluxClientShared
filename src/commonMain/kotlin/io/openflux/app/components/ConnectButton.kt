package io.openflux.app.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.pressScale
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** What the big button shows. */
enum class ConnectLook { Off, Busy, On, Error }

/**
 * Home's big button (spec 6): one thick ring, alive with light.
 * - Off: a gleam slides slowly round the dark ring, a nebula drifts inside, stars twinkle round it.
 * - Connecting: a comet races round the ring throwing sparks, and the stars are drawn in towards it as in a jump.
 * - Connected: the comet's tail closes the ring, a shock wave and a burst of sparks go out, then light flows
 *   round the ring and [timer] counts inside.
 * - Leaving: the light drains off the ring. After a failure the ring flares red and shudders.
 * [size] is the ring; the stars and the waves take the room around it. [label] names the action.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ConnectButton(
    look: ConnectLook,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 184.dp,
    shortcut: String? = null,
    timer: String? = null,
) {
    if (shortcut == null || AppTheme.touch) return Ring(look, label, onClick, modifier, size, timer)
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text("$label · $shortcut") } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) { Ring(look, label, onClick, modifier, size, timer) }
}

/** The room around the ring, as a multiple of its size: stars, waves and sparks live there. */
private const val OUTER = 1.7f

/** The second colour of space: a cold blue-cyan for the nebula and the sparks. */
private val Cyan = Color(0xFF5CC8FF)

/** The time that drives everything that moves, the comet's place on the ring (in turns) and how far the stars have flowed in. */
@Stable
private class SpaceClock {
    var t by mutableFloatStateOf(0f)
    var head by mutableFloatStateOf(0.75f)
    var warp by mutableFloatStateOf(0f)
}

/** A cheap, repeatable random number in 0..1 for particle [n], channel [k]. */
private fun rnd(n: Int, k: Int): Float {
    val x = sin(n * 12.9898f + k * 78.233f) * 43758.547f
    return x - floor(x)
}

private class Star(val depth: Float, val angle: Float, val twinkle: Float, val phase: Float, val size: Float)

private val Stars = List(56) { i -> Star(rnd(i, 1), rnd(i, 2) * 2f * PI.toFloat(), 0.8f + 2.2f * rnd(i, 3), rnd(i, 4) * 6.28f, 0.5f + rnd(i, 5)) }

@Composable
private fun Ring(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier, size: Dp, timer: String?) {
    val c = AppTheme.colors
    val dark = c.isDark
    val busy = look == ConnectLook.Busy
    val on = look == ConnectLook.On
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // How the light behaves in each state; every change is eased, so one state flows into the next.
    val litLength = animateFloatAsState(
        when (look) {
            ConnectLook.Off -> 0.16f
            ConnectLook.Busy -> 0.5f
            ConnectLook.On -> 1f
            ConnectLook.Error -> 0.16f
        },
        tween(if (on) 750 else 900, easing = FastOutSlowInEasing),
    )
    val tailLight = animateFloatAsState(if (on) 0.75f else 0f, tween(700))
    val headLight = animateFloatAsState(
        when (look) {
            ConnectLook.Off -> 0.35f
            ConnectLook.Busy -> 1f
            ConnectLook.On -> 0.85f
            ConnectLook.Error -> 0.6f
        },
        tween(600),
    )
    val energy = animateFloatAsState(
        when (look) {
            ConnectLook.Off -> 0.3f
            ConnectLook.Busy -> 0.7f
            ConnectLook.On -> 1f
            ConnectLook.Error -> 0.45f
        },
        tween(900),
    )
    val busyness = animateFloatAsState(if (busy) 1f else 0f, tween(700))
    val red = animateFloatAsState(if (look == ConnectLook.Error) 1f else 0f, tween(350))
    val speed = animateFloatAsState(
        when (look) {
            ConnectLook.Off -> 0.08f
            ConnectLook.Busy -> 1.15f
            ConnectLook.On -> 0.16f
            ConnectLook.Error -> 0.05f
        },
        tween(900, easing = FastOutSlowInEasing),
    )
    val hover = animateFloatAsState(if (hovered) 1f else 0f, tween(250))
    val shock = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val shake = remember { Animatable(1f) }

    val clock = remember { SpaceClock() }
    val focused = rememberWindowFocused()
    LaunchedEffect(clock) {
        throttledFrames(focused) { dt ->
            clock.t += dt
            clock.head = (clock.head + dt * speed.value) % 1f
            clock.warp += dt * busyness.value * 0.32f
        }
    }
    // One-shot moments: the ring closing into "connected", a failure.
    var before by remember { mutableStateOf(look) }
    LaunchedEffect(look) {
        val from = before
        before = look
        if (look == from) return@LaunchedEffect
        when (look) {
            ConnectLook.On -> {
                launch { kotlinx.coroutines.delay(420); shock.snapTo(0f); shock.animateTo(1f, tween(1200, easing = LinearOutSlowInEasing)) }
                launch { kotlinx.coroutines.delay(420); burst.snapTo(0f); burst.animateTo(1f, tween(1500, easing = LinearEasing)) }
            }
            ConnectLook.Error -> { shake.snapTo(0f); shake.animateTo(1f, tween(650, easing = LinearEasing)) }
            else -> Unit
        }
    }

    val accent = lerp(c.accent, c.danger, red.value)
    val light = lerp(c.accentText, c.danger, red.value)
    val cyan = lerp(Cyan, c.danger, red.value)
    val track = if (dark) c.surfaceHigh else c.border
    val disc = c.surface

    BoxWithConstraints(
        modifier.graphicsLayer {
            val s = shake.value
            translationX = sin(s * PI.toFloat() * 7f) * (1f - s) * 10.dp.toPx()
        },
        contentAlignment = Alignment.Center,
    ) {
        val outer = minOf(size * OUTER, maxWidth)
        val ringSize = outer / OUTER
        Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = ringSize.toPx() * 0.065f
                val r = ringSize.toPx() / 2 - w / 2
                val t = clock.t
                val e = energy.value
                // Stars round the ring: they twinkle, drift, and while connecting flow in towards it with trails.
                if (dark) stars(r, t, clock.warp, e, busyness.value, light, cyan)
                // The glow on the page.
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.30f * e * (0.85f + 0.15f * sin(t * 2.2f))), Color.Transparent), center, r * 1.75f),
                    r * 1.75f,
                )
                // The disc with a nebula drifting inside it.
                val inner = r - w / 2
                drawCircle(disc, inner)
                clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, inner)) }) {
                    val a = t * 0.35f
                    val p1 = center + Offset(cos(a), sin(a)) * (inner * 0.38f)
                    val p2 = center - Offset(cos(a * 0.8f), sin(a * 0.8f)) * (inner * 0.42f)
                    val k = if (dark) 1f else 0.55f
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.26f * e * k), Color.Transparent), p1, inner * 0.95f), inner * 2f)
                    drawCircle(Brush.radialGradient(listOf(cyan.copy(alpha = 0.16f * e * k), Color.Transparent), p2, inner * 0.85f), inner * 2f)
                    // The flash when the ring closes.
                    val f = (1f - shock.value).pow(3)
                    if (f > 0f) drawCircle(light.copy(alpha = 0.35f * f), inner)
                }
                // The track, a touch brighter under the pointer.
                drawCircle(lerp(track, accent, 0.12f * hover.value), r, style = Stroke(w))
                // The light on the ring: a comet whose tail grows into the whole ring.
                lightOnRing(r, w, clock.head, litLength.value, tailLight.value, headLight.value + 0.2f * hover.value, accent, light)
                if (busyness.value > 0.01f) sparks(r, t, clock.head, speed.value, busyness.value, light, cyan)
                // The shock wave and the burst when it connects.
                if (shock.value < 1f) shockwave(r, w, shock.value, light)
                if (burst.value < 1f) burst(r, burst.value, light, cyan)
            }
            // The button itself: the disc inside the ring.
            Box(
                Modifier.size(ringSize).pressScale(interaction, pressed = 0.95f, hovered = 1.02f).clip(CircleShape)
                    .clickable(interaction, ripple(color = c.accent), role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Face(look, label, timer, clock, hover.value) }
        }
    }
}

/** What is written inside the ring: the power sign and the action; once connected, the time. */
@Composable
private fun Face(look: ConnectLook, label: String, timer: String?, clock: SpaceClock, hover: Float) {
    val c = AppTheme.colors
    AnimatedContent(
        look == ConnectLook.On && timer != null,
        transitionSpec = { (fadeIn(tween(400, 250)) + scaleIn(tween(500, 250), 0.8f)) togetherWith (fadeOut(tween(200)) + scaleOut(tween(250), 1.1f)) },
        modifier = Modifier.clearAndSetSemantics { },
    ) { showTime ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showTime) {
                Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(22.dp), tint = c.accentText)
                Clock(timer.orEmpty())
                Text(label, style = AppTheme.type.caption, color = c.textSecondary)
            } else {
                val busy = look == ConnectLook.Busy
                val tint = when (look) {
                    ConnectLook.Error -> c.danger
                    ConnectLook.Busy -> c.accentText
                    else -> lerp(c.textSecondary, c.accentText, hover)
                }
                Icon(
                    Icons.Rounded.PowerSettingsNew, null,
                    Modifier.size(46.dp).alpha(if (busy) 0.55f + 0.45f * abs(sin(clock.t * 3.2f)) else 1f),
                    tint = tint,
                )
                Text(label, style = AppTheme.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
            }
        }
    }
}

/** The time, each digit rolling up as it changes. */
@Composable
private fun Clock(text: String) {
    val c = AppTheme.colors
    Row {
        text.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(i) {
                AnimatedContent(
                    ch,
                    contentAlignment = Alignment.Center,
                    transitionSpec = {
                        ((slideInVertically(tween(320)) { it } + fadeIn(tween(320))) togetherWith (slideOutVertically(tween(320)) { -it } + fadeOut(tween(200))))
                            .using(SizeTransform(clip = true) { _, _ -> snap() })
                    },
                ) { d -> Text(d.toString(), fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, color = c.text, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")) }
            }
        }
    }
}

/** A point on a circle of [radius] at [turns] (0 = 3 o'clock, clockwise). */
private fun DrawScope.at(radius: Float, turns: Float): Offset {
    val a = turns * 2f * PI.toFloat()
    return center + Offset(cos(a), sin(a)) * radius
}

private fun DrawScope.lightOnRing(r: Float, w: Float, head: Float, length: Float, tail: Float, glow: Float, accent: Color, light: Color) {
    // The gradient's seam sits a little ahead of the head, so the head's round end is drawn in the head's colour.
    val lead = 12f
    val sweep = length * 360f
    val end = (360f - lead) / 360f
    val start = ((360f - lead - sweep) / 360f).coerceAtLeast(0f)
    val stops = arrayOf(
        0f to accent.copy(alpha = tail),
        start to accent.copy(alpha = tail),
        (start + (end - start) * 0.55f) to accent.copy(alpha = maxOf(tail, 0.75f * glow)),
        end to light,
        // Past the head's round end the light falls back to the tail's, softly, so a lit ring shows no step there.
        (end + 4f / 360f) to light,
        1f to lerp(accent.copy(alpha = tail), light, 0.35f),
    )
    rotate(head * 360f + lead) {
        val brush = Brush.sweepGradient(*stops, center = center)
        val box = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        // A soft bloom under the light, then the light itself.
        drawArc(brush, -lead - sweep, sweep, false, center - Offset(r, r), box, alpha = 0.22f * glow, style = Stroke(w * 2.6f, cap = StrokeCap.Round))
        drawArc(brush, -lead - sweep, sweep, false, center - Offset(r, r), box, style = Stroke(w, cap = StrokeCap.Round))
    }
    // The comet's head: a white core in a halo.
    val p = at(r, head)
    drawCircle(Brush.radialGradient(listOf(light.copy(alpha = 0.7f * glow), Color.Transparent), p, w * 2.4f), w * 2.4f, p)
    drawCircle(Color.White.copy(alpha = 0.9f * glow), w * 0.36f, p)
}

private fun DrawScope.stars(r: Float, t: Float, warp: Float, energy: Float, busy: Float, light: Color, cyan: Color) {
    val near = r * 1.18f
    val far = r * OUTER * 0.98f
    for ((i, s) in Stars.withIndex()) {
        // Depth runs from the far edge (1) in to the ring (0); the warp pulls every star inwards.
        val d = ((s.depth - warp) % 1f + 1f) % 1f
        val radius = near + (far - near) * d
        val a = s.angle + t * 0.02f
        val dir = Offset(cos(a), sin(a))
        val p = center + dir * radius
        val edge = sin(PI.toFloat() * d)
        val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * s.twinkle + s.phase))
        val alpha = (0.18f + 0.82f * energy) * twinkle * edge
        val color = if (i % 3 == 0) cyan else if (i % 3 == 1) light else Color.White
        val size = s.size * 1.1.dp.toPx()
        if (busy > 0.02f) {
            // A trail behind a star rushing in.
            drawLine(color.copy(alpha = alpha * 0.5f * busy), p, p + dir * (r * 0.1f * busy * (0.4f + s.size)), size * 0.7f, StrokeCap.Round)
        }
        drawCircle(color.copy(alpha = alpha), size, p)
    }
}

/** Sparks thrown off the comet while connecting: born at its head, they fall behind and drift out, fading. */
private fun DrawScope.sparks(r: Float, t: Float, head: Float, speed: Float, busy: Float, light: Color, cyan: Color) {
    val rate = 26f
    val count = 34
    val life = count / rate
    val newest = floor(t * rate).toInt()
    for (k in 0 until count) {
        val n = newest - k
        val age = t - n / rate
        val f = (age / life).coerceIn(0f, 1f)
        val born = head - speed * age
        val out = r * (0.04f + 0.3f * rnd(n, 1)) * f * (if (rnd(n, 2) > 0.3f) 1f else -0.6f)
        val p = at(r + out, born - 0.02f * f * rnd(n, 3))
        val alpha = (1f - f).pow(2) * busy * (0.5f + 0.5f * rnd(n, 4))
        drawCircle(if (rnd(n, 5) > 0.5f) cyan.copy(alpha = alpha) else light.copy(alpha = alpha), (0.8f + 1.4f * rnd(n, 6)).dp.toPx() * (1f - 0.6f * f), p)
    }
}

private fun DrawScope.shockwave(r: Float, w: Float, s: Float, light: Color) {
    for ((delay, strength) in listOf(0f to 1f, 0.18f to 0.55f)) {
        val f = ((s - delay) / (1f - delay)).coerceIn(0f, 1f)
        if (f <= 0f || f >= 1f) continue
        val eased = 1f - (1f - f).pow(3)
        drawCircle(light.copy(alpha = (1f - f).pow(1.6f) * 0.9f * strength), r + r * 0.68f * eased, style = Stroke(w * (1.2f - f) + 1.dp.toPx()))
    }
}

private fun DrawScope.burst(r: Float, b: Float, light: Color, cyan: Color) {
    val eased = 1f - (1f - b).pow(3)
    for (i in 0 until 64) {
        val a = i / 64f + 0.01f * rnd(i, 7) + 0.03f * b * (if (i % 2 == 0) 1f else -1f)
        val dist = r * (0.08f + 0.6f * rnd(i, 8)) * eased
        val p = at(r + dist, a)
        val alpha = (1f - b).pow(1.3f) * (0.5f + 0.5f * rnd(i, 9))
        val color = when (i % 3) {
            0 -> Color.White
            1 -> cyan
            else -> light
        }
        drawCircle(color.copy(alpha = alpha), (1f + 1.8f * rnd(i, 10)).dp.toPx() * (1f - 0.5f * b), p)
    }
}
