package io.openflux.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme
import io.openflux.app.theme.MinTouch
import io.openflux.app.theme.accentBrush
import io.openflux.app.theme.glow
import io.openflux.app.theme.pressScale

private val ButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

@Composable
private fun ButtonLabel(text: String, leading: ImageVector?) {
    if (leading != null) {
        Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(AppTheme.spacing.s))
    }
    // A narrow button (two side by side on a small phone) shrinks or wraps its label rather than cutting it.
    val touch = AppTheme.touch
    BasicText(
        text,
        style = AppTheme.type.title.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
        maxLines = if (touch) 2 else 1,
        softWrap = touch,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = AppTheme.type.title.fontSize, stepSize = 0.5.sp),
    )
}

/** The one main action of a screen: the accent's gradient with a soft light under it. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(AppTheme.shapes.medium)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by androidx.compose.animation.core.animateDpAsState(if (enabled) (if (hovered) 14.dp else 8.dp) else 0.dp)
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = MinTouch).pressScale(interaction)
            .shadow(lift, shape, ambientColor = c.glow, spotColor = c.glow)
            .background(if (enabled) c.accentBrush else androidx.compose.ui.graphics.SolidColor(c.surfaceHigh), shape),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = c.onAccent,
            disabledContainerColor = Color.Transparent, disabledContentColor = c.textMuted,
        ),
        contentPadding = ButtonPadding,
        interactionSource = interaction,
    ) { ButtonLabel(text, leading) }
}

/** A second action: a raised tile with a thin edge that brightens under the pointer. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (hovered && enabled) c.surfaceHigh else c.surface)
    val edge by animateColorAsState(if (hovered && enabled) c.accent.copy(alpha = 0.55f) else c.border)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = MinTouch).pressScale(interaction),
        enabled = enabled,
        shape = RoundedCornerShape(AppTheme.shapes.medium),
        border = BorderStroke(1.dp, edge),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = fill, contentColor = c.text, disabledContentColor = c.textMuted),
        contentPadding = ButtonPadding,
        interactionSource = interaction,
    ) { ButtonLabel(text, leading) }
}

/** Deletes or breaks something: the colour says so before the dialog does. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(c.danger.copy(alpha = if (hovered && enabled) 0.18f else 0.08f))
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = MinTouch).pressScale(interaction),
        enabled = enabled,
        shape = RoundedCornerShape(AppTheme.shapes.medium),
        border = BorderStroke(1.dp, c.danger.copy(alpha = if (enabled) 0.7f else 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = fill, contentColor = c.danger, disabledContentColor = c.textMuted),
        contentPadding = ButtonPadding,
        interactionSource = interaction,
    ) { ButtonLabel(text, null) }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = AppTheme.colors.accentText) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.pressScale(interaction),
        enabled = enabled,
        shape = RoundedCornerShape(AppTheme.shapes.small),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = AppTheme.colors.textMuted),
        interactionSource = interaction,
    ) { Text(text, style = AppTheme.type.title, textAlign = TextAlign.Center) }
}

/** An icon with a tooltip naming it (and its shortcut, when [description] carries one: "Добавить · Ctrl+N"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    val button = @Composable {
        val interaction = remember { MutableInteractionSource() }
        IconButton(
            onClick = onClick,
            modifier = modifier.pressScale(interaction, pressed = 0.88f),
            enabled = enabled,
            interactionSource = interaction,
            colors = IconButtonDefaults.iconButtonColors(contentColor = c.text, disabledContentColor = c.textMuted),
        ) { Icon(icon, contentDescription = description.substringBefore(" · ")) }
    }
    if (AppTheme.touch) {
        button()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(description) } },
            state = rememberTooltipState(),
        ) { button() }
    }
}
