package io.openflux.desktop.ui

import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.ui.components.Tone
import kotlin.math.roundToLong

object Format {
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value Б"
        value < 1024 * 1024 -> "${oneDecimal(value / 1024.0)} КБ"
        value < 1024L * 1024 * 1024 -> "${oneDecimal(value / (1024.0 * 1024))} МБ"
        else -> "${oneDecimal(value / (1024.0 * 1024 * 1024))} ГБ"
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/с"

    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}"
    }

    private fun two(n: Long) = n.toString().padStart(2, '0')

    private val MONTHS = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** "14 октября 2026" for unix [seconds] in the zone [offsetSeconds] east of UTC. */
    fun date(seconds: Long, offsetSeconds: Int = 0): String {
        // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm), with no date library.
        val z = (seconds + offsetSeconds).floorDiv(86_400L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return "$day ${MONTHS[(month - 1).toInt()]} $year"
    }

    /** "1 день", "3 дня", "12 дней". */
    fun days(n: Int): String = "$n " + plural(n, "день", "дня", "дней")

    /** "осталось 3 дня" / "остался 1 день". */
    fun daysLeft(n: Int): String = (if (n % 10 == 1 && n % 100 != 11) "остался " else "осталось ") + days(n)

    /** "только что", "5 мин назад", "3 ч назад", "вчера", "4 дн. назад" for the time [agoMillis] ago. */
    fun ago(agoMillis: Long): String {
        val min = agoMillis.coerceAtLeast(0) / 60_000
        return when {
            min < 1 -> "только что"
            min < 60 -> "$min мин назад"
            min < 24 * 60 -> "${min / 60} ч назад"
            min < 48 * 60 -> "вчера"
            else -> "${min / (24 * 60)} дн. назад"
        }
    }

    private fun plural(n: Int, one: String, few: String, many: String): String = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }

    private fun oneDecimal(v: Double): String {
        val tenths = (v * 10).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
    }
}

/** How the UI names and colors a connection state. */
data class StateLook(val title: String, val tone: Tone)

fun ConnectionState.look(): StateLook = when (this) {
    ConnectionState.Idle -> StateLook("Отключено", Tone.Neutral)
    is ConnectionState.Connecting -> StateLook(if (mode == ConnectionMode.Exit) "Запуск ноды…" else "Подключение…", Tone.Warning)
    is ConnectionState.Connected -> StateLook(if (mode == ConnectionMode.Exit) "Нода работает" else "Подключено", Tone.Accent)
    is ConnectionState.Reconnecting -> StateLook("Переподключение…", Tone.Warning)
    is ConnectionState.Disconnecting -> StateLook("Отключение…", Tone.Warning)
    is ConnectionState.Failed -> StateLook("Ошибка", Tone.Danger)
}

/** The mode's one-line description, in the device's words. */
fun ConnectionMode.describe(android: Boolean): String = when {
    !android -> description
    this == ConnectionMode.Client -> "Трафик телефона идёт через ноду"
    else -> "Этот телефон выпускает в интернет других"
}

/** Hides document addresses and keys, as the Android log does. */
object Redact {
    private val url = Regex("""https?://(docs|disk|cloud)\.[^\s"')]+""")
    private val hex = Regex("""\b[0-9a-f]{32,}\b""")
    private val link = Regex("""openflux://v1/[A-Za-z0-9_-]+""")

    fun apply(text: String): String = text
        .replace(link, "openflux://v1/[скрыто]")
        .replace(url, "[адрес скрыт]")
        .replace(hex, "[ключ скрыт]")
}
