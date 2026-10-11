package io.openflux.desktop.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.ui.Format
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.components.AppTextField
import io.openflux.desktop.ui.components.Banner
import io.openflux.desktop.ui.components.ButtonRow
import io.openflux.desktop.ui.components.ButtonStyle
import io.openflux.desktop.ui.components.PageHeader
import io.openflux.desktop.ui.components.SectionLabel
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource

/** Where each way of connecting leads; the page itself only offers them. */
class AddConnectionActions(
    val onAccount: () -> Unit,
    val onRestore: () -> Unit,
    val onOwnNode: () -> Unit,
    val onNoServer: () -> Unit,
    val onManual: () -> Unit,
    /** A pasted profile link (not a subscription key): the Profiles screen imports it. */
    val onProfileLink: (String) -> Unit,
)

/**
 * "Подключение": the first thing a new person sees. A field for a key (a
 * subscription key or a profile link), and below it the other ways, the
 * recommended one first: an OpenFlux account with a free trial.
 */
@Composable
fun AddConnectionPage(model: SubscriptionScreenModel, actions: AddConnectionActions, modifier: Modifier = Modifier) {
    val idle = model.busy == null
    Column(modifier.widthIn(max = 640.dp)) {
        KeyBox(model, actions)
        model.error?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Danger)
        }
        model.busy?.let {
            Spacer(Modifier.height(AppTheme.spacing.m))
            Banner(it, Tone.Accent, icon = Icons.Rounded.Info)
        }
        Spacer(Modifier.height(AppTheme.spacing.xxl))
        SectionLabel("Другие варианты подключения")
        Spacer(Modifier.height(AppTheme.spacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            ChoiceCard(
                title = "Аккаунт OpenFlux",
                subtitle = "Самый простой способ: войдите и подключайтесь. Пробный период бесплатно.",
                icon = Icons.Rounded.Star,
                badge = "Рекомендуется",
                highlighted = true,
                onClick = actions.onAccount,
            )
            ChoiceCard("Нода на своём сервере", "Поставить свою ноду на VDS за пару минут", Icons.Rounded.Dns, onClick = actions.onOwnNode)
            ChoiceCard("Файл с настройками", "Ключ или ссылка из файла", Icons.Rounded.FolderOpen, enabled = idle, onClick = model::keyFromFile)
            ChoiceCard("QR-код", "Отсканировать код или выбрать картинку", Icons.Rounded.QrCode2, enabled = idle, onClick = model::keyFromQr)
            ChoiceCard("Без сервера", "Нода на обычном PHP-хостинге", Icons.Rounded.Cloud, onClick = actions.onNoServer)
            ChoiceCard("Создать вручную", "Для опытных: транспорты и ключи самому", Icons.Rounded.Edit, onClick = actions.onManual)
            ChoiceCard("Восстановить доступ", "Забыли пароль? Понадобится ключ восстановления", Icons.Rounded.Restore, onClick = actions.onRestore)
        }
    }
}

/** The field for a key and what the pasted text turned out to be. */
@Composable
private fun KeyBox(model: SubscriptionScreenModel, actions: AddConnectionActions) {
    Column {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
            AppTextField(
                value = model.keyText,
                onValueChange = model::keyChanged,
                modifier = Modifier.weight(1f),
                placeholder = "Вставьте ключ",
                monospace = true,
            )
            AppButton("Вставить", model::pasteKey, style = ButtonStyle.Secondary)
        }
        when (val key = model.keyState) {
            KeyState.Empty -> Unit
            KeyState.Checking -> {
                Spacer(Modifier.height(AppTheme.spacing.m))
                Text("Проверяю ключ…", style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary)
            }
            is KeyState.Invalid -> {
                Spacer(Modifier.height(AppTheme.spacing.m))
                Banner(key.message, Tone.Danger)
            }
            is KeyState.ProfileLink -> {
                Spacer(Modifier.height(AppTheme.spacing.m))
                Banner(
                    "Это ссылка на профиль. Она добавит одну ноду в список профилей.",
                    Tone.Accent, icon = Icons.Rounded.Info,
                    action = { AppButton("Добавить профиль", { actions.onProfileLink(key.link); model.clearKey() }) },
                )
            }
            is KeyState.Subscription -> {
                Spacer(Modifier.height(AppTheme.spacing.m))
                SubscriptionPreview(model, key)
            }
        }
    }
}

@Composable
private fun SubscriptionPreview(model: SubscriptionScreenModel, key: KeyState.Subscription) {
    val doc = key.preview.document
    val offset = model.platform.utcOffsetSeconds
    AppCard {
        Text(doc.title.ifBlank { key.preview.invite.name.ifBlank { "Подписка" } }, style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text)
        Spacer(Modifier.height(AppTheme.spacing.xs))
        val line = when {
            doc.status == SubStatus.REVOKED -> "Доступ по этому ключу отключён"
            doc.status == SubStatus.EXPIRED -> "Срок этого ключа вышел" + if (doc.expires > 0) " ${Format.date(doc.expires, offset)}" else ""
            doc.expires == 0L -> "Без ограничения срока"
            else -> "До ${Format.date(doc.expires, offset)}"
        }
        Text(line, style = AppTheme.typography.body, color = AppTheme.colors.textSecondary)
        if (doc.profiles.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.spacing.s))
            Text(
                "Локации: " + doc.profiles.joinToString(", ") { it.name },
                style = AppTheme.typography.bodySmall, color = AppTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(AppTheme.spacing.l))
        ButtonRow {
            AppButton("Подключить", model::addKey, enabled = doc.status == SubStatus.ACTIVE && model.busy == null)
            AppButton("Очистить", model::clearKey, style = ButtonStyle.Ghost)
        }
    }
}

/**
 * A row of the page: an icon, a title, a line under it and a chevron. The
 * [highlighted] one (what we recommend) has an accent border and a [badge].
 */
@Composable
fun ChoiceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val shape = AppTheme.shapes.card
    Box(modifier.fillMaxWidth().padding(top = if (badge != null) 10.dp else 0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (highlighted) colors.accentSoft else colors.surface)
                .border(BorderStroke(if (highlighted) 1.5.dp else 1.dp, if (highlighted) colors.accent else colors.border), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.l),
        ) {
            Icon(icon, null, tint = if (highlighted) colors.accent else colors.textSecondary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = AppTheme.typography.bodyStrong, color = colors.text)
                Text(subtitle, style = AppTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Icon(painterResource(AppIcons.ChevronRight), null, tint = colors.textHint, modifier = Modifier.size(20.dp))
        }
        if (badge != null) {
            Text(
                badge,
                style = AppTheme.typography.caption,
                color = colors.onAccent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = AppTheme.spacing.l, y = (-10).dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}
