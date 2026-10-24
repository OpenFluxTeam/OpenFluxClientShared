package io.openflux.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.pressScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the big button shows. */
enum class ConnectLook { Off, Busy, On, Error }

/**
 * Home's button, drawn as the OpenFlux mark in thin lines of one colour. Off it is a round power button.
 * Connecting, its ring takes the accent and a second ring shows round it. Connected, the circle fills and draws in
 * to the mark's core, and its eight rays reach out to the mark's devices: four monitors up, down, left and right,
 * four phones on the diagonals. A failure turns the ring red.
 *
 * It moves only on its way from one state to another: standing in any state it draws nothing new, so the window is
 * not redrawn for it at all. [label] names what pressing it does, [shortcut] is shown beside it on a pointer screen.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConnectButton(
    look: ConnectLook,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 264.dp,
    shortcut: String? = null,
) {
    if (shortcut == null || AppTheme.touch) return Mark(look, label, onClick, modifier, size)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text("$label · $shortcut") } },
        state = rememberTooltipState(),
    ) { Mark(look, label, onClick, modifier, size) }
}

@Composable
private fun Mark(look: ConnectLook, label: String, onClick: () -> Unit, modifier: Modifier, size: Dp) {
    val c = AppTheme.colors
    val on = look == ConnectLook.On
    // Each of these runs only while the state changes, then stands still.
    val spread by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 1000 else 500, easing = FastOutSlowInEasing))
    val busy by animateFloatAsState(if (look == ConnectLook.Busy) 1f else 0f, tween(400))
    val line by animateColorAsState(
        when (look) {
            ConnectLook.Off -> c.textMuted
            ConnectLook.Busy, ConnectLook.On -> c.accent
            ConnectLook.Error -> c.danger
        },
        tween(400),
    )
    val glyph by animateColorAsState(
        when (look) {
            ConnectLook.Off -> c.textSecondary
            ConnectLook.Busy -> c.accent
            ConnectLook.On -> c.onAccent
            ConnectLook.Error -> c.danger
        },
        tween(400),
    )
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hover by animateFloatAsState(if (hovered) 1f else 0f, tween(150))
    val text = c.text

    Box(modifier.size(size).pressScale(interaction), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            // The mark's own measures (its devices 113 from the middle), scaled to the button.
            val k = this.size.minDimension / 2 / 160f
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val width = 3f * k
            // A big power button while off; the mark's core once the devices are out.
            val radius = lerp(54f, 36f, spread) * k
            val ring = if (look == ConnectLook.Off) lerp(line, text, hover * 0.6f) else line
            if (spread > 0f) devices(center, k, spread, radius, line, width)
            if (busy > 0f) drawCircle(line.copy(alpha = 0.35f * busy), radius + 9f * k, center, style = Stroke(width * 0.7f))
            if (spread > 0f) drawCircle(line.copy(alpha = spread), radius, center)
            drawCircle(ring, radius, center, style = Stroke(width))
            power(center, radius * 0.4f, width * 1.4f, glyph)
        }
        // The core is the button.
        Box(
            Modifier.size(size * 0.42f).clip(CircleShape)
                .hoverable(interaction)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        )
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** The power sign: a ring open at the top and a bar through the gap. */
private fun DrawScope.power(center: Offset, r: Float, width: Float, color: Color) {
    drawArc(color, -60f, 300f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(width, cap = StrokeCap.Round))
    drawLine(color, Offset(center.x, center.y - r * 1.25f), Offset(center.x, center.y - r * 0.15f), width, StrokeCap.Round)
}

/**
 * The eight rays and what is at their ends, [spread] 0..1 of the way out: the monitors first, the phones a little
 * after, each ray drawn before its device arrives.
 */
private fun DrawScope.devices(center: Offset, k: Float, spread: Float, core: Float, color: Color, width: Float) {
    val reach = 113f * k
    for (i in 0 until 8) {
        val diagonal = i % 2 == 1
        val angle = i * PI.toFloat() / 4 - PI.toFloat() / 2
        val delay = if (diagonal) 0.2f else 0f
        val t = ((spread - delay) / 0.8f).coerceIn(0f, 1f)
        if (t <= 0f) continue
        val ray = (t / 0.5f).coerceAtMost(1f)
        val arrive = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val dx = cos(angle)
        val dy = sin(angle)
        val end = if (diagonal) reach * 0.92f else reach
        val gap = (if (diagonal) 29f else 33f) * k
        val start = core + 7f * k
        val stop = start + (end - gap - start) * ray
        drawLine(color, Offset(center.x + dx * start, center.y + dy * start), Offset(center.x + dx * stop, center.y + dy * stop), width * 0.8f, StrokeCap.Round)
        if (arrive <= 0f) continue
        val s = 0.6f + 0.4f * arrive
        // Each device faces the core: its bottom (a monitor's stand, a phone's button) towards the middle.
        translate(center.x + dx * end, center.y + dy * end) {
            rotate(angle * 180f / PI.toFloat() + 90f, Offset.Zero) {
                if (diagonal) phone(k * s, color, width, arrive) else monitor(k * s, color, width, arrive)
            }
        }
    }
}

/** A monitor's outline centred on the origin, upright with a short stand (the caller turns it to face the core). */
private fun DrawScope.monitor(k: Float, color: Color, width: Float, alpha: Float) {
    val w = 58f * k
    val h = 40f * k
    val top = -h / 2 - 4f * k
    drawRoundRect(color, Offset(-w / 2, top), Size(w, h), CornerRadius(7f * k), style = Stroke(width), alpha = alpha)
    drawLine(color, Offset(-10f * k, top + h + 7f * k), Offset(10f * k, top + h + 7f * k), width, StrokeCap.Round, alpha = alpha)
}

/** A phone's outline centred on the origin, upright (the caller turns it to face the core). */
private fun DrawScope.phone(k: Float, color: Color, width: Float, alpha: Float) {
    val w = 26f * k
    val h = 44f * k
    drawRoundRect(color, Offset(-w / 2, -h / 2), Size(w, h), CornerRadius(7f * k), style = Stroke(width), alpha = alpha)
    drawLine(color, Offset(-3f * k, h / 2 - 6f * k), Offset(3f * k, h / 2 - 6f * k), width * 0.8f, StrokeCap.Round, alpha = alpha)
}
