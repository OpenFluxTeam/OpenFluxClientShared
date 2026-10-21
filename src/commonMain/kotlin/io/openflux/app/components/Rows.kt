package io.openflux.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.Motion
import io.openflux.app.theme.rowMinHeight

/** The copy icon, the same everywhere. */
val CopyIcon = Icons.Rounded.ContentCopy

/** What a thing is doing, as a dot: off, busy, on, needs a look, broken. */
enum class Status { Off, Busy, On, Warning, Error }

/** The dot; a running thing breathes (a ring grows out of it and fades), a busy one does it faster. */
@Composable
fun StatusDot(status: Status, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val color by animateColorAsState(
        when (status) {
            Status.Off -> c.textMuted
            Status.Busy, Status.Warning -> c.warning
            Status.On -> c.success
            Status.Error -> c.danger
        },
    )
    // Only a live dot runs a clock; it is read while drawing, so the pulse redraws without recomposing.
    val pulse = if ((status == Status.On || status == Status.Busy) && io.openflux.app.theme.LocalAnimations.current) {
        val period = if (status == Status.Busy) 900 else 1800
        rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart))
    } else null
    Box(
        modifier.size(8.dp).drawBehind {
            val p = pulse?.value
            if (p != null) drawCircle(color.copy(alpha = 0.45f * (1f - p)), radius = size.minDimension / 2 * (1f + 1.3f * p))
            drawCircle(color)
        },
    )
}

/** A rounded tile with an icon in the accent's wash (as in 2.x lists), a status dot in its corner when [status] is given. */
@Composable
fun IconBubble(icon: ImageVector, modifier: Modifier = Modifier, status: Status? = null, tint: Color = AppTheme.colors.accentText, size: Dp = 36.dp) {
    val c = AppTheme.colors
    Box(modifier.size(size)) {
        Box(
            Modifier.matchParentSize().clip(RoundedCornerShape(AppTheme.shapes.small)).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f)) }
        if (status != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(14.dp).clip(CircleShape).background(c.surface),
                contentAlignment = Alignment.Center,
            ) { StatusDot(status) }
        }
    }
}

/**
 * A line of a list: what almost every screen is made of. No frame of its own; a [Section] groups rows.
 * [selected] marks the chosen one with a wash of the accent, a rounded bar at its edge and an accent title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onDoubleClick: (() -> Unit)? = null,
) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = onClick != null || onLongClick != null || onDoubleClick != null
    val clickable = if (active) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = ripple(color = c.accent),
            enabled = enabled,
            onClick = onClick ?: {},
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
        )
    } else Modifier
    val chosen by animateFloatAsState(if (selected) 1f else 0f, Motion.soft())
    val wash by animateColorAsState(if (hovered && active && enabled) c.surfaceHigh else c.surfaceHigh.copy(alpha = 0f))
    val mark = Modifier.drawBehind {
        drawRect(wash)
        if (chosen > 0f) {
            drawRect(c.accentSoft.copy(alpha = c.accentSoft.alpha * chosen))
            val h = size.height * (0.3f + 0.4f * chosen)
            drawRoundRect(
                c.accent.copy(alpha = chosen),
                topLeft = Offset(0f, (size.height - h) / 2),
                size = Size(3.dp.toPx(), h),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = rowMinHeight()).then(mark).then(clickable)
            .padding(horizontal = sp.l, vertical = sp.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(sp.m))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTheme.type.body,
                color = when {
                    !enabled -> c.textMuted
                    selected -> c.accentText
                    else -> c.text
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = AppTheme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(sp.s))
            trailing()
        }
    }
}

/** "Name · value", the value copyable: how facts are shown instead of cards. A value too long for the line goes under the name whole. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValueRow(title: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    Row(
        modifier.fillMaxWidth().heightIn(min = rowMinHeight()).padding(start = sp.l, end = if (onCopy != null) sp.xs else sp.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            Modifier.weight(1f).padding(vertical = sp.s),
            horizontalArrangement = Arrangement.spacedBy(sp.m, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = AppTheme.type.body, color = c.textSecondary, maxLines = 1)
            Text(value, style = (if (mono) AppTheme.type.mono else AppTheme.type.body).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.text)
        }
        if (onCopy != null) IconAction(Icons.Rounded.ContentCopy, "Копировать", onCopy)
    }
}

/**
 * A group of rows on one card with a thin edge, a line between the rows and an optional small heading in
 * capitals above. Children are laid out top to bottom.
 */
@Composable
fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    val sp = AppTheme.spacing
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = AppTheme.type.label,
                color = c.textSecondary,
                modifier = Modifier.padding(start = sp.xs, bottom = sp.s),
            )
        }
        // Where one row ends and the next begins, written by the layout and read by the drawing of the same frame.
        val seams = remember { Seams() }
        val inset = sp.l
        val shape = RoundedCornerShape(AppTheme.shapes.medium)
        val seam = c.border.copy(alpha = 0.7f)
        Layout(
            content = content,
            modifier = Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .drawWithContent {
                    drawContent()
                    val x = inset.toPx()
                    val stroke = 1.dp.toPx()
                    for (y in seams.ys) drawLine(seam, Offset(x, y - stroke / 2), Offset(size.width, y - stroke / 2), stroke)
                },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            val visible = placeables.filter { it.height > 0 }
            seams.ys = visible.dropLast(1).runningFold(0f) { y, p -> y + p.height }.drop(1)
            layout(constraints.maxWidth, placeables.sumOf { it.height }) {
                var y = 0
                placeables.forEach { p -> p.placeRelative(0, y); y += p.height }
            }
        }
    }
}

private class Seams {
    var ys: List<Float> = emptyList()
}

/** A group with a heading whose content is not rows: a switcher, a field, a line of text, padded like rows. */
@Composable
fun Group(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Section(title, modifier) {
        Column(Modifier.fillMaxWidth().padding(AppTheme.spacing.l), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), content = content)
    }
}

/** A heading row that opens and closes what is under it: the rare settings ("Дополнительно"). */
@Composable
fun Collapsible(title: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.soft())
    Column(modifier.fillMaxWidth()) {
        RowItem(
            title,
            trailing = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.rotate(turn)) },
            onClick = { open = !open },
        )
        AnimatedVisibility(
            open,
            enter = expandVertically(Motion.soft()) + fadeIn(tween(220, delayMillis = 60)),
            exit = shrinkVertically(Motion.soft()) + fadeOut(tween(120)),
        ) { Column(content = content) }
    }
}
