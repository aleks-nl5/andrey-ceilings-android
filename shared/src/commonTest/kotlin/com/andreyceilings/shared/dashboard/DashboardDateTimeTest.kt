package com.andreyceilings.shared.dashboard

import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardDateTimeTest {
    @Test
    fun dateIsTheLocalCalendarDateNotUtcDate() {
        val millis = Instant.parse("2026-10-31T23:30:00Z").toEpochMilliseconds()
        val utc = TimeZone.UTC
        val kyiv = TimeZone.of("Europe/Kyiv")
        assertEquals("31 жовтня 2026", dashboardTodayLabel(millis, utc))
        assertEquals("1 листопада 2026", dashboardTodayLabel(millis, kyiv))
        assertEquals("31.10, 23:30", currencyLocalDateTimeLabel(millis, utc))
        assertEquals("01.11, 01:30", currencyLocalDateTimeLabel(millis, kyiv))
    }

    @Test
    fun dateOnlyFormattingMatchesExistingUkrainianMonthInflections() {
        val zone = TimeZone.UTC
        val millis = Instant.parse("2026-05-09T08:03:00Z").toEpochMilliseconds()
        assertEquals("9 травня 2026", dashboardTodayLabel(millis, zone))
        assertEquals("09.05, 08:03", currencyLocalDateTimeLabel(millis, zone))
        assertEquals("—", currencyLocalDateTimeLabel(0L, zone))
    }
}
