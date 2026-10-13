package io.openflux.desktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

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

    /** The bot's id and the domain its Telegram sign-in is bound to (BotFather /setdomain). */
    const val BOT_ID = 8950210830L
    const val ORIGIN = "https://openflux.dev"

    /** Where Telegram sends the browser with the signed user in its fragment; the app stops there. */
    const val RETURN_TO = "$ORIGIN/app-login"
    const val AUTH_RESULT = "tgAuthResult="

    /** The Telegram sign-in page for the bot, in redirect mode (no window opener to post the result to). */
    val signInUrl: String = "https://oauth.telegram.org/auth?bot_id=$BOT_ID&origin=${percentEncode(ORIGIN)}" +
        "&request_access=write&lang=ru&return_to=${percentEncode(RETURN_TO)}"

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

    /**
     * The signed Telegram user in an address Telegram sent the browser to: base64 JSON after `#tgAuthResult=`.
     * Null when the address carries none (or a refusal: tgAuthResult=false).
     */
    fun authResult(url: String): TelegramUser? {
        val raw = url.substringAfter("#", "").split('&').firstOrNull { it.startsWith(AUTH_RESULT) }?.removePrefix(AUTH_RESULT) ?: return null
        val text = runCatching { percentDecode(raw) }.getOrDefault(raw).trim()
        if (text.isEmpty() || text == "false") return null
        val bytes = runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(text.replace('+', '-').replace('/', '_')) }.getOrNull()
            ?: return null
        val obj = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun num(k: String) = (obj[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
        val user = TelegramUser(num("id"), str("first_name"), str("last_name"), str("username"), str("photo_url"), num("auth_date"), str("hash"))
        return user.takeIf { it.id > 0 && it.hash.isNotEmpty() && it.authDate > 0 }
    }

    private fun percentEncode(s: String) = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c) else append('%').append((b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/** What Telegram signs for the bot: the fields the API checks the hash over. */
@Serializable
data class TelegramUser(
    val id: Long,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val username: String = "",
    @SerialName("photo_url") val photoUrl: String = "",
    @SerialName("auth_date") val authDate: Long = 0,
    val hash: String = "",
) {
    override fun toString() = "TelegramUser($id)"
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
)

/** A failed bot API call; [code] is "network", "unauthorized", "not_found" or "http_<n>". */
class BotException(val code: String, message: String) : Exception(message) {
    val unauthorized: Boolean get() = code == "unauthorized"
    val notFound: Boolean get() = code == "not_found"
}
