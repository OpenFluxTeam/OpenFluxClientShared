package io.openflux.app.add

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.openflux.app.components.Banner
import io.openflux.app.components.Field
import io.openflux.app.components.Section
import io.openflux.app.components.Tone
import io.openflux.app.components.ValueRow
import io.openflux.app.text.Fmt
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.service.LocalAppContainer

/*
 * "Ключ, ссылка или файл" of a build with the OpenFlux service: what it adds to reading a profile link.
 * Reached only through io.openflux.app.shell.BuildExtras, so a build without the service does not carry it.
 */

/** The field's wording where a subscription key is read too. */
@Composable
internal fun ServiceKeyField(m: AddKeyModel) {
    Field(m.text, { m.onText(it) }, "Ключ или ссылка", placeholder = "Ключ подписки или ссылка openflux://", singleLine = false, mono = true)
}

/** The button's words for what the service gave; null: the usual ones. */
internal fun serviceKeyButton(r: KeyResult): String? = if (r is KeyResult.Subscription) "Подключить подписку" else null

/** What a bot link or a subscription key turned out to be. */
@Composable
internal fun ServiceKeyPreview(r: KeyResult) {
    val container = LocalAppContainer.current
    val platform = container.platform
    when (r) {
            is KeyResult.Bot -> Section("Подключение из бота OpenFlux") {
                ValueRow("Название", r.connection.name.ifBlank { "OpenFlux" })
                ValueRow("Обновление", "само, пока подключение действует в боте")
            }
            is KeyResult.Subscription -> {
                val doc = r.preview.document
                val offset = platform.utcOffsetSeconds
                Section("Подписка") {
                    ValueRow("Название", doc.title.ifBlank { r.preview.invite.name.ifBlank { "Подписка" } })
                    ValueRow(
                        "Срок",
                        when {
                            doc.status == SubStatus.REVOKED -> "доступ отключён"
                            doc.status == SubStatus.EXPIRED -> "истёк" + if (doc.expires > 0) " ${Fmt.date(doc.expires, offset)}" else ""
                            doc.expires == 0L -> "без ограничения"
                            else -> "до ${Fmt.date(doc.expires, offset)}"
                        },
                    )
                    if (doc.profiles.isNotEmpty()) ValueRow("Локации", doc.profiles.joinToString(", ") { it.name })
                }
                if (!r.canAdd) Banner("По этому ключу подключиться нельзя: срок истёк или доступ отключён", Tone.Warning)
                val current = container.subscription.state.collectAsState().value
                if (current != null && r.canAdd) Banner("Новая подписка заменит ту, что есть на этом устройстве", Tone.Info)
            }
        else -> Unit
    }
}

internal const val NOTHING_TO_SHARE_WITH_SERVICE =
    "Пока нечем делиться. Поставьте свою ноду в «Серверах» или добавьте подключение через ＋. Локациями подписки делиться нельзя."
