package com.andreyceilings.shared.calendar

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarWeekNumberTest {
    @Test fun isoWeeksAcrossYearBoundaries() {
        assertEquals(53, isoWeekNumber(LocalDate(2020, 12, 31)))
        assertEquals(53, isoWeekNumber(LocalDate(2021, 1, 1)))
        assertEquals(1, isoWeekNumber(LocalDate(2021, 1, 4)))
        assertEquals(1, isoWeekNumber(LocalDate(2024, 12, 30)))
        assertEquals(1, isoWeekNumber(LocalDate(2025, 1, 5)))
        assertEquals(52, isoWeekNumber(LocalDate(2026, 12, 27)))
    }
}
