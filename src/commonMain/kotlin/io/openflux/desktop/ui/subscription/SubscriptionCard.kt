package io.openflux.desktop.ui.subscription

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.IconBubble
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.theme.AppTheme
import kotlinx.coroutines.delay

/** The platform clock, re-read every minute, for dates and "5 min ago" lines that must not go stale. */
@Composable
fun rememberNowMillis(platform: PlatformServices): Long {
    var now by remember { mutableStateOf(platform.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = platform.now()
        }
    }
    return now
}

/**
 * The subscription on Home: its title, how long it lasts and a way to the
 * Subscription screen. When it ends soon (or has) the button says "Продлить".
 */
@Composable
fun SubscriptionStrip(state: SubscriptionState, platform: PlatformServices, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNowMillis(platform)
    val look = state.look(now, platform.utcOffsetSeconds)
    AppCard(modifier.fillMaxWidth(), padding = AppTheme.spacing.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(AppIcons.Person)
            Spacer(Modifier.width(AppTheme.spacing.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.title.ifBlank { "Подписка" }, style = AppTheme.typography.bodyStrong, color = AppTheme.colors.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (look.attention) {
                        Spacer(Modifier.width(AppTheme.spacing.s))
                        StatusBadge(look.badge, look.tone)
                    }
                }
                Text(look.line, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(AppTheme.spacing.s))
            AppButton(
                if (look.attention) "Продлить" else "Открыть", onOpen,
                style = if (look.attention) ButtonStyle.Primary else ButtonStyle.Ghost,
            )
        }
    }
}
