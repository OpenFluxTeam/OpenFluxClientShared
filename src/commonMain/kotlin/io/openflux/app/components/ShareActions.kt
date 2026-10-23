package io.openflux.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.service.LocalAppContainer

/**
 * What to do with a link to give away. Android opens the system share sheet with every installed app; the desktop
 * has no such sheet to call from here, so it offers the common messengers and mail, each with the link filled in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareLinkActions(link: String, modifier: Modifier = Modifier) {
    val platform = LocalAppContainer.current.platform
    val toaster = LocalToaster.current
    val copy = { platform.setClipboardText(link); toaster.show("Ссылка скопирована") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        if (platform.shareSupported) {
            PrimaryButton("Поделиться ссылкой", { if (!platform.shareText(link)) copy() }, Modifier.fillMaxWidth(), leading = Icons.Rounded.Share)
            SecondaryButton("Копировать ссылку", copy, Modifier.fillMaxWidth())
        } else {
            val q = percentEncode(link)
            PrimaryButton("Копировать ссылку", copy, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                SecondaryButton("Telegram", { platform.openUrl("https://t.me/share/url?url=$q") })
                SecondaryButton("WhatsApp", { platform.openUrl("https://wa.me/?text=$q") })
                SecondaryButton("Почта", { platform.openUrl("mailto:?subject=OpenFlux&body=$q") })
            }
        }
    }
}

/**
 * [text] for a URL query, as java.net.URLEncoder with spaces as %20 would give
 * it, but in common code (iOS has no java.net): UTF-8 bytes, everything but
 * letters, digits and -._* percent-encoded.
 */
internal fun percentEncode(text: String): String = buildString {
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '*') append(ch)
        else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 0xF]) }
    }
}
