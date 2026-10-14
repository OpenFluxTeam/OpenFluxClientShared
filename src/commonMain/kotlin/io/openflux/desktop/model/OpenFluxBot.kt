package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/**
 * The OpenFlux Telegram bot (@openflux_bot) and its API at api.openflux.dev: people buy there, the bot gives each
 * connection a short link (https://link.openflux.dev/<token>) that resolves to its `openflux://` profile, and a
 * Telegram sign-in gives the app a session to read the subscription with.
 */
object OpenFluxBot {
    const val USERNAME = "openflux_bot"
    const val CHAT_URL = "https://t.me/$USERNAME"
    const val API = "https://api.openflux.dev"
    const val LINK_HOST = "link.openflux.dev"
    /** Accounts made by an email sign-in get numbers from here up; below it the number is the person's Telegram id. */
    const val EMAIL_ACCOUNT_IDS = 10_000_000_000_000_000L

    private val TOKEN = Regex("^[A-Za-z0-9_-]{16,64}$")

    /** The token of a bot connection link (https://link.openflux.dev/<token>, with or without the scheme), or null. */
    fun connectToken(text: String): String? {
        val s = text.trim().substringBefore('#').substringBefore('?').removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (!s.startsWith("$LINK_HOST/")) return null
        return s.removePrefix("$LINK_HOST/").takeIf(TOKEN::matches)
    }

    fun looksLike(text: String): Boolean = connectToken(text) != null

    /** The link of a token, as the bot shows it. */
    fun linkOf(token: String) = "https://$LINK_HOST/$token"
}

/** The bot API's session, from a Telegram sign-in: a bearer token good for up to 30 days. */
@Serializable
data class BotSession(
    val token: String,
    val userId: Long = 0,
    val username: String = "",
    /** Unix seconds: the session's ceiling (an idle week may end it sooner). */
    val expiresAt: Long = 0,
) {
    /** Signed in through Telegram (the account the bot knows); else by email, an account the bot never sees. */
    val viaTelegram: Boolean get() = userId in 1 until OpenFluxBot.EMAIL_ACCOUNT_IDS

    override fun toString() = "BotSession($userId)"
}

/** The subscription bought in the bot, as GET /api/v1/subscription reports it. */
@Serializable
data class BotSubscription(
    val planId: String = "",
    val planName: String = "",
    val status: String = "",
    /** Unix seconds; 0 when there is none. */
    val expiresAt: Long = 0,
    val daysLeft: Int = 0,
    val maxSessions: Int = 0,
    val trafficUsedBytes: Long = 0,
    val trafficLimitBytes: Long = 0,
    val isActive: Boolean = false,
)

/** A connection from the bot kept on this device: its short link's token and the profile made from it. */
@Serializable
data class BotLink(
    val token: String,
    val profileId: String,
    val name: String = "",
    /** The platform clock's milliseconds of the last answer about it. */
    val checkedAt: Long = 0,
    /** The bot no longer serves it: the subscription ended, or the connection was replaced by a new one. */
    val gone: Boolean = false,
    /** The connection of the signed-in account (made or found by the app), not one added by hand: sign-out takes it away. */
    val account: Boolean = false,
) {
    override fun toString() = "BotLink($profileId, gone=$gone)"
}

/** What the app keeps about the bot: the signed-in session (if any), its subscription, and the connections added. */
@Serializable
data class BotState(
    val session: BotSession? = null,
    val subscription: BotSubscription? = null,
    val links: List<BotLink> = emptyList(),
    val lastError: String? = null,
    /** The account's connection is being made (or its link is not ready yet): the app asks again soon. */
    val preparing: Boolean = false,
    /** Why the signed-in account has no connection (no free slot, subscription ended), in words for the person. */
    val connectionNote: String? = null,
    /** The purchase made from the app last: waiting for its payment, or paid/expired until the person has seen it. */
    val pendingInvoice: BotPendingInvoice? = null,
)

/**
 * A purchase of a slot on the term [termCode]. [key] is its Idempotency-Key, kept from before the call: a call lost on
 * the way is repeated with it and gets the same invoice, never a second one. [invoice] is null until the service answered.
 */
@Serializable
data class BotPendingInvoice(
    val termCode: String,
    val key: String,
    val invoice: BotInvoice? = null,
) {
    /** Still to be paid (or not known yet): asked about until it is paid or ends. */
    val waiting: Boolean get() = invoice == null || (!invoice.paid && !invoice.expired)
    override fun toString() = "BotPendingInvoice($termCode, ${invoice?.status})"
}

/**
 * A failed API call; [code] is "network", "unauthorized", "forbidden", "not_found", "rate_limited" or "http_<n>", for a
 * sign-in "no_app_id", "sign_in_failed", "blocked", "not_ready", "unavailable" or "bad_request", and for an invoice
 * "slot_limit", "blocked", "plan_gone", "busy", "payments_off" or "bad_request".
 */
class BotException(val code: String, message: String) : Exception(message) {
    val unauthorized: Boolean get() = code == "unauthorized"
    val forbidden: Boolean get() = code == "forbidden"
    val notFound: Boolean get() = code == "not_found"
}

/** One of the account's connections as GET /api/v1/sessions reports it: [connectToken] resolves to its `openflux://` link. */
data class BotSessionItem(val id: String, val status: String, val connectToken: String) {
    val ready: Boolean get() = status == "running" && connectToken.isNotEmpty()
    override fun toString() = "BotSessionItem($id, $status)"
}

/**
 * One term on sale, as GET /api/v1/plans lists it. [id] is the term's code, what an invoice takes back; [name] is the
 * tariff's and repeats on each of its terms. Every purchase buys a new slot (one connection), never a renewal.
 */
@Serializable
data class BotPlan(
    val id: String,
    val name: String = "",
    val durationDays: Int = 0,
    /** 0 when the term has no traffic limit. */
    val trafficLimitBytes: Long = 0,
    val description: String = "",
    /** In minor units of [currency] (kopecks for RUB). */
    val price: Long = 0,
    val currency: String = "RUB",
) {
    /** The price as people read it: "30 ₽", "30,50 ₽", "1 250 ₽". */
    val priceText: String
        get() {
            val whole = (price / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
            val cents = price % 100
            val amount = if (cents == 0L) whole else "$whole," + cents.toString().padStart(2, '0')
            return "$amount ${if (currency == "RUB") "₽" else currency}"
        }
}

/** An invoice of a purchase, as the service reports it; [id] is ours (GET /api/v1/subscription/invoices/{id}). */
@Serializable
data class BotInvoice(
    val id: String,
    /** The payment page (https); empty when there is nothing to pay (the free tier settles at once). */
    val payUrl: String = "",
    /** The amount as the service writes it ("30.00"). */
    val amount: String = "",
    /** "active" (waiting for the payment), "paid" or "expired". */
    val status: String = "",
    /** Unix seconds. */
    val createdAt: Long = 0,
    /** Unix seconds: an unpaid invoice ends then (a day after it was made). */
    val expiresAt: Long = 0,
) {
    val paid: Boolean get() = status == "paid"
    val expired: Boolean get() = status == "expired"
}

/** A banner of the service (GET /api/v1/banners): what the bot shows everyone right now. */
@Serializable
data class BotBanner(
    val id: Long,
    /** "info", "success", "warning" or "error". */
    val kind: String = "info",
    val textRu: String = "",
    val textEn: String = "",
) {
    /** The text in [lang] ("ru" or "en"), or in the other language when this one is missing. */
    fun text(lang: String): String = if (lang == "en") textEn.ifBlank { textRu } else textRu.ifBlank { textEn }
}

/** How the person signed in, for the account screen: a number below [OpenFluxBot.EMAIL_ACCOUNT_IDS] is a Telegram id. */
fun BotSession.accountLabel(): String =
    if (viaTelegram) "Telegram, id $userId" else "по почте, № $userId"
