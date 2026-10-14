package io.openflux.desktop.data

import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.LoginPoll
import io.openflux.desktop.model.LoginStart
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.TelegramUser
import io.openflux.desktop.service.BotApi
import io.openflux.desktop.service.BotConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The OpenFlux bot's API over HTTPS (the usual certificate checks: a real domain). Its JSON is gRPC-gateway's:
 * camelCase names, 64-bit numbers as strings, empty fields left out. A session goes as a bearer token
 * (X-Session-Transport: bearer at sign-in): the app keeps it, never a cookie.
 */
class HttpBotApi(private val base: String = OpenFluxBot.API, private val appKey: String = "") : BotApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun signIn(user: TelegramUser): BotSession {
        val body = JsonObject(
            mapOf(
                "id" to JsonPrimitive(user.id), "first_name" to JsonPrimitive(user.firstName), "last_name" to JsonPrimitive(user.lastName),
                "username" to JsonPrimitive(user.username), "photo_url" to JsonPrimitive(user.photoUrl),
                "auth_date" to JsonPrimitive(user.authDate), "hash" to JsonPrimitive(user.hash),
            ),
        )
        val o = request("POST", "/api/v1/auth/telegram", null, body, mapOf("X-Session-Transport" to "bearer")).jsonObject
        val token = o.str("token")
        if (token.isEmpty()) throw BotException("bad_response", "Бот не выдал сессию: обновите приложение")
        return BotSession(token, o.long("userId"), o.str("username"), o.long("expiresAt"))
    }

    override suspend fun subscription(token: String): BotSubscription {
        val o = request("GET", "/api/v1/subscription", token, null).jsonObject
        return BotSubscription(
            planId = o.str("planId"), planName = o.str("planName"), status = o.str("status"), expiresAt = o.long("expiresAt"),
            daysLeft = o.long("daysLeft").toInt(), maxSessions = o.long("maxSessions").toInt(),
            trafficUsedBytes = o.long("trafficUsedBytes"), trafficLimitBytes = o.long("trafficLimitBytes"),
            isActive = (o["isActive"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    override suspend fun signOut(token: String) {
        request("POST", "/api/v1/auth/logout", token, JsonObject(emptyMap()))
    }

    override suspend fun resolve(connectToken: String): BotConnection {
        val o = request("POST", "/api/v1/connect/resolve", null, JsonObject(mapOf("token" to JsonPrimitive(connectToken)))).jsonObject
        val link = o.str("link")
        if (!link.startsWith("openflux://")) throw BotException("bad_response", "Бот ответил непонятно: обновите приложение")
        return BotConnection(o.str("name"), link)
    }

    override suspend fun startLogin(): LoginStart {
        val o = appRequest("/api/v1/auth/app/start", JsonObject(emptyMap())).jsonObject
        val nonce = o.str("nonce")
        val link = o.str("deepLink")
        if (nonce.isEmpty() || !link.startsWith("https://t.me/")) throw BotException("bad_response", "Бот ответил непонятно: обновите приложение")
        return LoginStart(nonce, link, o.long("expiresAt"))
    }

    override suspend fun pollLogin(nonce: String): LoginPoll {
        val o = appRequest("/api/v1/auth/app/poll", JsonObject(mapOf("nonce" to JsonPrimitive(nonce)))).jsonObject
        return when (o.str("status")) {
            "pending" -> LoginPoll.Pending
            "denied" -> LoginPoll.Denied
            "expired", "unknown" -> LoginPoll.Expired
            "approved" -> {
                val auth = o["auth"] as? JsonObject
                val token = auth?.str("token").orEmpty()
                if (auth == null || token.isEmpty()) throw BotException("bad_response", "Бот не выдал сессию: обновите приложение")
                LoginPoll.Approved(BotSession(token, auth.long("userId"), auth.str("username"), auth.long("expiresAt")))
            }
            else -> throw BotException("bad_response", "Бот ответил непонятно: обновите приложение")
        }
    }

    override suspend fun sessions(token: String): List<BotSessionItem> {
        val e = request("GET", "/api/v1/sessions", token, null)
        val array = e as? JsonArray ?: (e as? JsonObject)?.get("sessions") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::sessionItem) }
    }

    override suspend fun createSession(token: String): BotSessionItem {
        val o = request("POST", "/api/v1/sessions", token, JsonObject(emptyMap())).jsonObject
        return sessionItem((o["session"] as? JsonObject) ?: o)
    }

    private fun sessionItem(o: JsonObject) = BotSessionItem(o.str("sessionId"), o.str("status"), o.str("connectToken"))

    /** A sign-in call: the app's key goes in X-App-Key; a key the service refuses means this build may no longer sign in. */
    private suspend fun appRequest(path: String, body: JsonObject): JsonElement {
        if (appKey.isEmpty()) throw BotException("no_app_key", "Вход через Telegram в этой сборке выключен: в ней нет ключа приложения")
        return try {
            request("POST", path, null, body, mapOf("X-App-Key" to appKey))
        } catch (e: BotException) {
            if (e.unauthorized || e.forbidden) throw BotException("app_disabled", "Эта версия приложения больше не может войти. Обновите приложение")
            throw e
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L

    private suspend fun request(method: String, path: String, token: String?, body: JsonObject?, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val conn = try {
                URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw BotException("network", "Бот OpenFlux не отвечает. Проверьте сеть.")
            }
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { it.readNBytes(MAX_BYTES).toString(Charsets.UTF_8) }.orEmpty()
                when {
                    code == 401 -> throw BotException("unauthorized", "Войдите через Telegram заново")
                    code == 403 -> throw BotException("forbidden", "Нет доступа: подписка не действует или нет свободного места")
                    code == 404 -> throw BotException("not_found", "Не найдено")
                    code == 429 -> throw BotException("rate_limited", "Слишком много запросов к боту, подождите минуту")
                    code >= 300 -> throw BotException("http_$code", "Бот ответил ошибкой $code")
                }
                try {
                    json.parseToJsonElement(text.ifBlank { "{}" })
                } catch (e: Exception) {
                    throw BotException("bad_response", "Бот ответил непонятно: обновите приложение")
                }
            } catch (e: IOException) {
                throw BotException("network", "Бот OpenFlux не отвечает. Проверьте сеть.")
            } finally {
                conn.disconnect()
            }
        }

    private companion object {
        const val MAX_BYTES = 1 shl 20
    }
}
