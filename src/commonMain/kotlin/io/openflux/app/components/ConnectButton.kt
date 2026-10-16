package io.openflux.app.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.pressScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the big button shows. */
enum class ConnectLook { Off, Busy, On, Error }

/**
 * Home's big round button (spec 6), the 2.x circle inside two thin rings, alive in every state:
 * - off: a deep disc with a slowly turning ring of the accent's gradient, two small lights orbiting it, the power
 *   sign in the gradient and the action written under it;
 * - connecting: the lights race, an arc runs round the disc, waves go out;
 * - connected: the disc fills with the gradient, a sheen turns over it, it glows and breathes;
 * - failed: the ring and the lights turn red.
 * [size] is the disc; the rings and the glow take the room around it. [label] names the action.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ConnectButton(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 164.dp, shortcut: String? = null) {
    if (shortcut == null || AppTheme.touch) return ConnectCircle(look, label, onClick, modifier, size)
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { androidx.compose.material3.Text("$label · $shortcut") } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) { ConnectCircle(look, label, onClick, modifier, size) }
}

/** How much room the rings and the glow take around the disc. */
private const val OUTER = 1.55f

@Composable
private fun ConnectCircle(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier, size: Dp) {
    val c = AppTheme.colors
    val busy = look == ConnectLook.Busy
    val on = look == ConnectLook.On
    val error = look == ConnectLook.Error
    // What colours the ring, the lights and the glow: the accent, or red after a failure.
    val tone by animateColorAsState(if (error) c.danger else c.accent, tween(450))
    val toneEnd by animateColorAsState(if (error) c.danger else c.accentEnd, tween(450))
    val filled by animateFloatAsState(if (on) 1f else 0f, tween(600, easing = FastOutSlowInEasing))
    val halo by animateFloatAsState(
        when (look) {
            ConnectLook.On -> 1f
            ConnectLook.Busy -> 0.7f
            ConnectLook.Error -> 0.5f
            ConnectLook.Off -> 0.3f
        },
        tween(700),
    )
    // The lights speed up while connecting.
    val speed by animateFloatAsState(if (busy) 4f else 1f, tween(800))
    val contentColor by animateColorAsState(
        when (look) {
            ConnectLook.On -> c.onAccent
            ConnectLook.Error -> c.danger
            else -> c.text
        },
        tween(450),
    )
    val clock = rememberInfiniteTransition()
    // One slow turn: everything that goes round reads it (times [speed]); read while drawing, it redraws without recomposing.
    val spin = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)))
    val wave = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing)))
    val breath = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val arc = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing)))
    val ringBase = c.border
    val discTop = c.surfaceHigh
    val discBottom = c.background
    val accent = c.accent
    val accentEnd = c.accentEnd
    val light = c.accentText
    val dark = c.isDark
    val interaction = remember { MutableInteractionSource() }

    Box(modifier.size(size * OUTER), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outerR = this.size.minDimension / 2
            val coreR = outerR / OUTER
            val gap = outerR - coreR
            val b = breath.value
            val turn = spin.value * speed
            // The glow under it all.
            val a = (if (dark) 0.42f else 0.3f) * halo * (0.7f + 0.3f * b)
            drawCircle(
                Brush.radialGradient(
                    0f to tone.copy(alpha = a),
                    (coreR / outerR) to toneEnd.copy(alpha = a * 0.7f),
                    1f to Color.Transparent,
                    center = center, radius = outerR,
                ),
                radius = outerR,
            )
            // Waves going out: two quick ones while connecting, one slow one when connected.
            if (busy || on) {
                val w = wave.value
                for (p in if (busy) listOf(w, (w + 0.5f) % 1f) else listOf(w)) {
                    drawCircle(tone.copy(alpha = (if (busy) 0.6f else 0.35f) * (1f - p)), coreR + gap * p, style = Stroke((2.2f - 1.4f * p).dp.toPx()))
                }
            }
            // The two rings of 2.x: the inner one a turning band of the gradient, the outer one thin and plain.
            val r1 = coreR + gap * 0.3f
            val r2 = coreR + gap * 0.68f
            rotate(turn * 360f) {
                drawCircle(
                    Brush.sweepGradient(listOf(tone.copy(alpha = 0.05f), tone.copy(alpha = 0.7f), toneEnd.copy(alpha = 0.9f), tone.copy(alpha = 0.05f)), center),
                    r1, style = Stroke(1.5.dp.toPx()),
                )
            }
            drawCircle(lerp(ringBase, tone, 0.35f * halo).copy(alpha = 0.6f), r2, style = Stroke(1.dp.toPx()))
            // Two lights orbiting the rings, one each way.
            orbiter(r1, turn * 360f + 90f, 3.5.dp.toPx(), light)
            orbiter(r2, -turn * 360f * 0.7f + 230f, 2.5.dp.toPx(), toneEnd)
        }
        Box(
            Modifier.size(size)
                .pressScale(interaction, pressed = 0.94f, hovered = 1.03f)
                .clip(CircleShape)
                .drawBehind {
                    val r = this.size.minDimension / 2
                    // A deep disc, lit from above.
                    drawCircle(Brush.radialGradient(listOf(discTop, discBottom), Offset(r, r * 0.55f), r * 1.35f), r)
                    if (filled > 0f) {
                        drawCircle(Brush.linearGradient(listOf(accent, accentEnd), Offset.Zero, Offset(this.size.width, this.size.height)), r, alpha = filled)
                        // A sheen turning slowly over the filled disc, and a highlight on the upper left, as on glass.
                        rotate(spin.value * 720f) {
                            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.18f * filled), Color.Transparent, Color.Transparent), center), r)
                        }
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.25f * filled), Color.Transparent), Offset(r * 0.62f, r * 0.42f), r), r)
                    }
                    // The rim: the gradient (a moving arc while connecting), white on the filled disc, red after a failure.
                    val stroke = 3.dp.toPx()
                    val inner = r - stroke / 2
                    when {
                        busy -> {
                            drawCircle(ringBase, inner, style = Stroke(stroke))
                            rotate(arc.value * 360f) {
                                drawCircle(Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.3f), accentEnd, accent), center), inner, style = Stroke(stroke))
                            }
                        }
                        on -> drawCircle(Color.White.copy(alpha = 0.3f), inner, style = Stroke(stroke))
                        else -> drawCircle(
                            Brush.linearGradient(listOf(tone, toneEnd), Offset.Zero, Offset(this.size.width, this.size.height)),
                            inner, style = Stroke(stroke), alpha = if (error) 1f else 0.85f,
                        )
                    }
                }
                .clickable(interaction, ripple(color = c.onAccent), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Off and connecting, the power sign is drawn in the gradient; connected, in white.
                Icon(
                    Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = contentColor,
                    modifier = Modifier.size(size * 0.3f)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            if (!on && !error) drawRect(Brush.linearGradient(listOf(light, accentEnd), Offset.Zero, Offset(this.size.width, this.size.height)), blendMode = BlendMode.SrcIn)
                        },
                )
                AnimatedContent(label, transitionSpec = { (fadeIn(tween(250, 100)) + scaleIn(initialScale = 0.85f)) togetherWith fadeOut(tween(100)) }) {
                    BasicText(
                        it.uppercase(),
                        modifier = Modifier.padding(horizontal = size * 0.14f),
                        style = AppTheme.type.label.copy(color = contentColor.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = AppTheme.type.label.fontSize, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

/** A small light on a ring at [degrees], with a soft glow round it. */
private fun DrawScope.orbiter(radius: Float, degrees: Float, dot: Float, color: Color) {
    val rad = degrees * PI.toFloat() / 180f
    val p = Offset(center.x + radius * cos(rad), center.y + radius * sin(rad))
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), p, dot * 4f), dot * 4f, p)
    drawCircle(color, dot, p)
}
