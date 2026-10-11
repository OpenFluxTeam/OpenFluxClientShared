package io.openflux.desktop

import io.openflux.desktop.ui.Format
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDatesTest {
    @Test
    fun datesAreRussianCivilDates() {
        assertEquals("1 января 1970", Format.date(0))
        assertEquals("29 февраля 2024", Format.date(1_709_164_800))
        assertEquals("1 марта 2024", Format.date(1_709_251_200))
        assertEquals("31 декабря 1999", Format.date(946_598_400))
        assertEquals("12 октября 2026", Format.date(1_791_763_200 + 3_600))
        assertEquals("31 декабря 1969", Format.date(-1))
    }

    @Test
    fun theZoneMovesTheDay() {
        // 22:30 UTC on the 10th is already the 11th at UTC+3, and still the 10th at UTC-5.
        val t = 1_791_589_800L // 2026-10-09 22:30 UTC
        assertEquals("9 октября 2026", Format.date(t))
        assertEquals("10 октября 2026", Format.date(t, 3 * 3600))
        assertEquals("9 октября 2026", Format.date(t, -5 * 3600))
    }

    @Test
    fun countsAreInRussian() {
        for ((n, want) in mapOf(1 to "1 день", 2 to "2 дня", 4 to "4 дня", 5 to "5 дней", 11 to "11 дней", 12 to "12 дней", 21 to "21 день", 22 to "22 дня", 100 to "100 дней", 111 to "111 дней")) {
            assertEquals(want, Format.days(n))
        }
        assertEquals("остался 1 день", Format.daysLeft(1))
        assertEquals("осталось 3 дня", Format.daysLeft(3))
        assertEquals("осталось 11 дней", Format.daysLeft(11))
        assertEquals("остался 21 день", Format.daysLeft(21))
        assertEquals("осталось меньше суток", Format.daysLeft(0))
    }

    @Test
    fun agoIsRoughOnPurpose() {
        assertEquals("только что", Format.ago(20_000))
        assertEquals("5 мин назад", Format.ago(5 * 60_000L))
        assertEquals("3 ч назад", Format.ago(3 * 3_600_000L))
        assertEquals("вчера", Format.ago(30 * 3_600_000L))
        assertEquals("4 дн. назад", Format.ago(4 * 86_400_000L))
        assertEquals("только что", Format.ago(-5))
    }
}
