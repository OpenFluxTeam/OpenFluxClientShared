package io.openflux.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import io.openflux.app.theme.Motion
import io.openflux.app.theme.appear
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.openflux.app.theme.AppTheme
import kotlinx.coroutines.delay

enum class Tone { Info, Success, Warning, Danger }

/** One line of what needs attention, with what to do about it. A screen shows at most one (spec 3). */
@Composable
fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    secondAction: String? = null,
    onSecondAction: (() -> Unit)? = null,
    more: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val c = AppTheme.colors
    val (icon, color) = when (tone) {
        Tone.Info -> Icons.Rounded.Info to c.accentText
        Tone.Success -> Icons.Rounded.CheckCircle to c.success
        Tone.Warning -> Icons.Rounded.Warning to c.warning
        Tone.Danger -> Icons.Rounded.Error to c.danger
    }
    val shape = RoundedCornerShape(AppTheme.shapes.medium)
    // Tinted by its tone, with a bar of the tone on its leading edge; it rises in when it appears.
    Row(
        modifier.appear().fillMaxWidth().clip(shape).background(c.surface).background(color.copy(alpha = if (c.isDark) 0.10f else 0.07f))
            .border(1.dp, color.copy(alpha = 0.32f), shape)
            .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .padding(start = AppTheme.spacing.m, end = AppTheme.spacing.xs, top = AppTheme.spacing.xs, bottom = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(
            text,
            modifier = Modifier.weight(1f).padding(vertical = AppTheme.spacing.s),
            style = AppTheme.type.bodySmall,
            color = c.text,
            maxLines = 3,
        )
        if (secondAction != null && onSecondAction != null) TextAction(secondAction, onSecondAction, color = c.textSecondary)
        if (action != null && onAction != null) TextAction(action, onAction)
        if (more.isNotEmpty()) Box {
            var open by remember { mutableStateOf(false) }
            IconAction(Icons.Rounded.MoreVert, "Ещё", { open = true })
            DropdownMenu(open, { open = false }) {
                more.forEach { (label, run) -> DropdownMenuItem({ Text(label) }, { open = false; run() }) }
            }
        }
    }
}

/** A short note at the bottom of the window ("Скопировано"); a new one replaces the old. */
@Stable
class Toaster {
    var current by mutableStateOf<String?>(null)
        private set
    internal var serial by mutableStateOf(0)
        private set

    fun show(text: String) {
        current = text
        serial++
    }

    internal fun clear(at: Int) {
        if (serial == at) current = null
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val serial = toaster.serial
    LaunchedEffect(serial) {
        delay(2_500)
        toaster.clear(serial)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val c = AppTheme.colors
        val shape = RoundedCornerShape(AppTheme.shapes.medium)
        AnimatedVisibility(
            toaster.current != null,
            enter = slideInVertically(Motion.bouncy()) { it / 2 } + fadeIn() + scaleIn(initialScale = 0.92f),
            exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            Row(
                Modifier.widthIn(max = 480.dp).shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape).background(c.surfaceHigh).border(1.dp, c.border, shape)
                    .padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = c.accentText, modifier = Modifier.size(18.dp))
                Text(toaster.current.orEmpty(), style = AppTheme.type.body, color = c.text)
            }
        }
    }
}

/** "Are you sure?" for what cannot be undone; [danger] colours the confirming button. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    dismiss: String = "Отмена",
) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textSecondary,
        shape = RoundedCornerShape(AppTheme.shapes.large),
        modifier = Modifier.border(1.dp, c.border, RoundedCornerShape(AppTheme.shapes.large)),
        title = { Text(title, style = AppTheme.type.title) },
        text = text?.let { { Text(it, style = AppTheme.type.body) } },
        confirmButton = { if (danger) DangerButton(confirm, { onConfirm() }) else PrimaryButton(confirm, { onConfirm() }) },
        dismissButton = { TextAction(dismiss, onDismiss, color = c.textSecondary) },
    )
}
