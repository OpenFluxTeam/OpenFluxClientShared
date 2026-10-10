package io.openflux.desktop.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.openflux.desktop.model.UpdateState
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.TextAction
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.theme.AppTheme

/**
 * A new release of the app: what it is, and one button. The person can also skip that release or ask later;
 * nothing is downloaded before "Обновить". Quiet when there is nothing to say.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val updates = container.updates
    val platform = container.platform
    val state by updates.state.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> Column(modifier.padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
            Banner(
                "Вышла версия ${s.release.version}. У вас ${platform.appVersion}.",
                Tone.Accent,
                icon = Icons.Rounded.Download,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        TextAction("Позже", updates::dismiss)
                        TextAction("Пропустить", updates::skip)
                        AppButton(if (s.asset == null) "Скачать" else "Обновить", updates::install)
                    }
                },
            )
        }
        is UpdateState.Downloading -> Column(modifier.padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
            Banner("Скачиваю версию ${s.release.version}: ${megabytes(s.done)}${if (s.total > 0) " из ${megabytes(s.total)}" else ""}", Tone.Accent, icon = Icons.Rounded.Download)
            Spacer(Modifier.height(AppTheme.spacing.xs))
            if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Installing -> Column(modifier.padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
            Banner(
                s.note ?: "Устанавливаю версию ${s.release.version}…",
                Tone.Success,
                icon = Icons.Rounded.Download,
                action = { TextAction("Закрыть", updates::dismiss) },
            )
        }
        is UpdateState.Failed -> if (s.release != null) {
            Column(modifier.padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.s)) {
                Banner(
                    s.message,
                    Tone.Danger,
                    icon = Icons.Rounded.ErrorOutline,
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
                            TextAction("Закрыть", updates::dismiss)
                            AppButton("Повторить", updates::install, style = ButtonStyle.Secondary)
                        }
                    },
                )
            }
        }
        else -> Unit
    }
}

/** "12,4 МБ". */
internal fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return "${tenths / 10},${tenths % 10} МБ"
}
