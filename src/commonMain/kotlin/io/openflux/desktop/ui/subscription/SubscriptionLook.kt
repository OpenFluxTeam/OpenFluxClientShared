package io.openflux.desktop.ui.subscription

import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.ui.Format
import io.openflux.desktop.ui.components.Tone

/** How a subscription reads on a card: the badge, its color, a line about the end, and whether it wants the person's attention. */
data class SubLook(val badge: String, val tone: Tone, val line: String, val attention: Boolean)

/** Days left at which the card starts asking to renew. */
const val RENEW_SOON_DAYS = 3

/**
 * [nowMillis] is the platform clock; [offsetSeconds] its zone east of UTC.
 * The service's `expires` is unix seconds.
 */
fun SubscriptionState.look(nowMillis: Long, offsetSeconds: Int): SubLook {
    val nowSeconds = nowMillis / 1000
    val trial = account?.plan?.id == "trial"
    return when (status) {
        SubStatus.REVOKED -> SubLook("Отключена", Tone.Danger, "Доступ отключён. Обратитесь в поддержку.", attention = true)
        SubStatus.EXPIRED -> if (expires == 0L) {
            SubLook("Нет подписки", Tone.Neutral, "Подписка не оформлена.", attention = true)
        } else {
            SubLook("Срок вышел", Tone.Danger, "Закончилась ${Format.date(expires, offsetSeconds)}.", attention = true)
        }
        else -> {
            val left = daysLeft(nowSeconds)
            when {
                expires == 0L -> SubLook("Активна", Tone.Success, "Без ограничения срока.", attention = false)
                left == null -> SubLook("Срок вышел", Tone.Danger, "Закончилась ${Format.date(expires, offsetSeconds)}.", attention = true)
                left <= RENEW_SOON_DAYS -> SubLook(
                    if (trial) "Пробный период" else "Скоро закончится", Tone.Warning,
                    "До ${Format.date(expires, offsetSeconds)}: ${Format.daysLeft(left)}.", attention = true,
                )
                else -> SubLook(
                    if (trial) "Пробный период" else "Активна", if (trial) Tone.Accent else Tone.Success,
                    "До ${Format.date(expires, offsetSeconds)}: ${Format.daysLeft(left)}.", attention = false,
                )
            }
        }
    }
}

/** "Обновлено 5 мин назад" for the card, or null when it has never been refreshed. */
fun SubscriptionState.refreshedText(nowMillis: Long): String? =
    if (lastRefreshed == 0L) null else "Обновлено " + Format.ago(nowMillis - lastRefreshed)
