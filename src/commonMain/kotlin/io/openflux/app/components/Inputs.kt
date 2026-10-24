package io.openflux.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.openflux.app.theme.MinTouch
import io.openflux.app.theme.Motion
import io.openflux.app.theme.accentBrush
import io.openflux.app.theme.glow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openflux.app.theme.AppTheme

@Composable
private fun fieldColors() = AppTheme.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, disabledTextColor = c.textMuted,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, disabledContainerColor = c.surface,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.border, errorBorderColor = c.danger,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.textSecondary, errorLabelColor = c.danger,
        cursorColor = c.accent, errorSupportingTextColor = c.danger,
        focusedPlaceholderColor = c.textMuted, unfocusedPlaceholderColor = c.textMuted,
    )
}

/** A text field; [error] goes under it in one line. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) = TextInput(value, onValueChange, label, modifier, placeholder, error, singleLine, mono, keyboardType, trailing, VisualTransformation.None)

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    placeholder: String?,
    error: String?,
    singleLine: Boolean,
    mono: Boolean,
    keyboardType: KeyboardType,
    trailing: (@Composable () -> Unit)?,
    transformation: VisualTransformation,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
        singleLine = singleLine,
        textStyle = if (mono) AppTheme.type.mono else AppTheme.type.body,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        visualTransformation = transformation,
        shape = RoundedCornerShape(AppTheme.shapes.small),
        colors = fieldColors(),
    )
}

/** A password or a key: hidden until the eye is pressed. */
@Composable
fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null) {
    var shown by rememberSaveable { mutableStateOf(false) }
    TextInput(
        value, onValueChange, label, modifier, null, error, singleLine = true, mono = false, KeyboardType.Password,
        trailing = {
            IconAction(
                if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (shown) "Скрыть" else "Показать",
                onClick = { shown = !shown },
            )
        },
        transformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
    )
}

/**
 * One of a few: L4 / L3, Пароль / Ключ, Клиент / Выходная нода. A track with the accent's gradient sliding under
 * the chosen option.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(AppTheme.shapes.medium)
    val inner = RoundedCornerShape(AppTheme.shapes.medium - 3.dp)
    val index = options.indexOf(selected).coerceAtLeast(0)
    val height = if (AppTheme.touch) MinTouch else 42.dp
    // A large system font makes the track taller and the labels wrap, instead of cutting either.
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(min = height).clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(3.dp),
    ) {
        val cell = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(cell * index, Motion.bouncy())
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.offset(x = x).width(cell).fillMaxHeight()
                    .shadow(6.dp, inner, ambientColor = c.glow, spotColor = c.glow)
                    .background(c.accentBrush, inner),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                val on = i == index
                val can = enabled(option)
                val color by animateColorAsState(
                    when {
                        on -> c.onAccent
                        !can -> c.textMuted
                        else -> c.textSecondary
                    },
                )
                Box(
                    Modifier.weight(1f).heightIn(min = height).clip(inner)
                        .selectable(on, enabled = can, role = Role.RadioButton, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    // A narrow screen shrinks or wraps a long label rather than cutting it.
                    BasicText(
                        label(option),
                        modifier = Modifier.padding(horizontal = AppTheme.spacing.s, vertical = AppTheme.spacing.xs),
                        style = AppTheme.type.body.copy(
                            color = color,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = AppTheme.type.body.fontSize, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

/** A switch on a row: the whole row toggles it. */
@Composable
fun Toggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    val c = AppTheme.colors
    RowItem(
        title,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                    uncheckedThumbColor = c.textSecondary, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.border,
                    disabledCheckedTrackColor = c.accent.copy(alpha = 0.4f), disabledUncheckedTrackColor = c.surfaceHigh,
                ),
            )
        },
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val c = AppTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(text, style = AppTheme.type.bodySmall.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        // A chosen chip shows a tick that grows in; one with its own icon keeps it.
        leadingIcon = (leading ?: Icons.Rounded.Check.takeIf { selected })?.let { icon ->
            { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
        },
        shape = RoundedCornerShape(AppTheme.shapes.large),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.surface, labelColor = c.textSecondary, iconColor = c.textSecondary,
            selectedContainerColor = c.accentSoft, selectedLabelColor = c.accentText, selectedLeadingIconColor = c.accentText,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = c.border, selectedBorderColor = c.accent.copy(alpha = 0.6f)),
    )
}

/** A tick box on a row: the whole row toggles it. */
@Composable
fun CheckRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.textSecondary, checkmarkColor = c.onAccent),
        )
        Text(text, style = AppTheme.type.body, color = if (enabled) c.text else c.textSecondary)
    }
}
