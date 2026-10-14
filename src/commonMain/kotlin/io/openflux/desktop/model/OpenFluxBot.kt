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
)

/** A failed API call; [code] is "network", "unauthorized", "forbidden", "not_found", "rate_limited" or "http_<n>", and for a sign-in "no_app_key", "sign_in_failed", "blocked", "not_ready", "unavailable" or "bad_request". */
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

/** How the person signed in, for the account screen: a number below [OpenFluxBot.EMAIL_ACCOUNT_IDS] is a Telegram id. */
fun BotSession.accountLabel(): String =
    if (userId in 1 until OpenFluxBot.EMAIL_ACCOUNT_IDS) "Telegram, id $userId" else "по почте, № $userId"
