package io.openflux.desktop.data

import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.BotSession
import io.openflux.desktop.model.BotSessionItem
import io.openflux.desktop.model.BotSubscription
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.service.BotAccountManager
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
 * (the sign-in answers with it): the app keeps it, never a cookie.
 */
class HttpBotApi(private val base: String = OpenFluxBot.API, private val appId: String = "") : BotApi {
    private val json = Json { ignoreUnknownKeys = true }

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

    override suspend fun exchange(code: String, verifier: String, redirectUri: String): BotSession {
        if (appId.isEmpty()) throw BotException("no_app_id", "Вход в этой сборке выключен: в ней нет идентификатора приложения")
        val body = JsonObject(
            mapOf("code" to JsonPrimitive(code), "code_verifier" to JsonPrimitive(verifier), "redirect_uri" to JsonPrimitive(redirectUri)),
        )
        val o = try {
            request("POST", "/api/v1/auth/app/exchange", null, body, mapOf("X-App-Id" to appId)) as? JsonObject
        } catch (e: BotException) {
            throw signInError(e)
        }
        if (o == null) throw BotException("bad_response", "Сервис ответил непонятно: обновите приложение")
        val token = o.str("token")
        if (token.isEmpty()) throw BotException("bad_response", "Сервис не выдал сессию: обновите приложение")
        val userId = o.long("userId").takeIf { it > 0 } ?: o.long("user_id")
        val expiresAt = o.long("expiresAt").takeIf { it > 0 } ?: o.long("expires_at")
        return BotSession(token, userId, "", expiresAt)
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

    /** The service answers every failed sign-in alike (401): wrong or used code, unconfirmed email, foreign address, unknown app id. */
    private fun signInError(e: BotException): BotException = when (e.code) {
        "unauthorized" -> BotException("sign_in_failed", "Не получилось войти. Если входили по почте, подтвердите адрес по ссылке из письма и попробуйте снова")
        "forbidden" -> BotException("blocked", "Этот аккаунт заблокирован. Напишите в поддержку в @${OpenFluxBot.USERNAME}")
        "not_found" -> BotException("not_ready", "Сервис пока не принимает вход из приложения. Попробуйте позже")
        "http_503" -> BotException("unavailable", "Вход временно недоступен, попробуйте через пару минут")
        "http_400" -> BotException("bad_request", "Приложение отправило неверный запрос входа: обновите приложение")
        else -> e
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
                    code == 401 -> throw BotException("unauthorized", "Войдите заново")
                    code == 403 -> throw BotException("forbidden", serverWords(text) ?: BotAccountManager.HttpForbidden)
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

    /**
     * What the service said in its own words (gRPC-gateway: {"code":7,"message":"…"}), made fit to show: the
     * bot's HTML and emoji out, the technical prefix and line breaks out. Null when it said nothing usable.
     */
    private fun serverWords(body: String): String? {
        val message = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.str("message") }.getOrNull().orEmpty()
        val clean = message
            .replace(Regex("<[^>]+>"), "")
            .removePrefix("session creation blocked:")
            .filterNot { it.isSurrogate() || it == '⚠' || it == '☁' || it == '️' }
            .replace(Regex("\\s+"), " ")
            .trim()
        return clean.takeIf { it.isNotEmpty() }?.take(400)
    }

    private companion object {
        const val MAX_BYTES = 1 shl 20
    }
}
