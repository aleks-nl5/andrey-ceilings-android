package com.andreyceilings.shared.calendar

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarWeekNumberTest {
    @Test fun displayedRowUsesItsOwnThursdayEvenWhenOnlyTheFirstDateIsAvailable() {
        assertEquals(53, calendarRowIsoWeekNumber(LocalDate(2020, 12, 27))) // Sunday start
        assertEquals(1, calendarRowIsoWeekNumber(LocalDate(2021, 1, 1))) // Friday start, next Thursday
        assertEquals(53, calendarRowIsoWeekNumber(LocalDate(2020, 12, 28))) // Monday start
        assertEquals(1, calendarRowIsoWeekNumber(LocalDate(2024, 12, 30)))
    }

    @Test fun isoWeeksAcrossYearBoundaries() {
        assertEquals(53, isoWeekNumber(LocalDate(2020, 12, 31)))
        assertEquals(53, isoWeekNumber(LocalDate(2021, 1, 1)))
        assertEquals(1, isoWeekNumber(LocalDate(2021, 1, 4)))
        assertEquals(1, isoWeekNumber(LocalDate(2024, 12, 30)))
        assertEquals(1, isoWeekNumber(LocalDate(2025, 1, 5)))
        assertEquals(52, isoWeekNumber(LocalDate(2026, 12, 27)))
    }
}
