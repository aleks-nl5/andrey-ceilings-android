package com.andreyceilings.shared.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class WorkRecurrenceTest {
    @Test fun monthlyClampsAndRetainsOriginalAnchorWithCountOrInclusiveUntil() {
        val anchor = LocalDate(2024, 1, 31)
        val end = LocalDate(2024, 4, 30)
        val range = WorkDateRange(anchor, end)
        assertEquals(listOf("2024-01-31", "2024-02-29", "2024-03-31"),
            WorkRecurrence(anchor, WorkFrequency.MONTHLY, count = 3).occurrences(range).map(LocalDate::toString).toList())
        assertEquals(end, WorkRecurrence(anchor, WorkFrequency.MONTHLY, until = end).occurrences(range).last())
        assertEquals(LocalDate(2030, 2, 28),
            WorkRecurrence(anchor, WorkFrequency.MONTHLY).occurrences(
                WorkDateRange(LocalDate(2030, 2, 1), LocalDate(2030, 2, 28))).single())
    }

    @Test fun intervalAndCustomWeekSeekUseOriginalAnchorAndCount() {
        val from = LocalDate(2026, 1, 7)
        val range = WorkDateRange(from, LocalDate(2026, 2, 6))
        assertEquals(listOf(7, 9, 11), WorkRecurrence(from, WorkFrequency.DAILY, 2, count = 3)
            .occurrences(range).map { it.day }.toList())
        assertEquals(listOf(7, 21, 4), WorkRecurrence(from, WorkFrequency.WEEKLY, 2, count = 3)
            .occurrences(range).map { it.day }.toList())
        val custom = WorkRecurrence(from, WorkFrequency.CUSTOM, 2,
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), count = 4)
        assertEquals(listOf(7, 9, 19, 21), custom.occurrences(range).map { it.day }.toList())
        assertEquals(listOf(19, 21), custom.occurrences(WorkDateRange(LocalDate(2026, 1, 10), range.through))
            .map { it.day }.toList())
        assertEquals(3, custom.ordinal(LocalDate(2026, 1, 21)))
    }

    @Test fun gapShiftsAndOverlapChoosesEarlierInstant() {
        val zone = TimeZone.of("Europe/Berlin")
        val schedule = WorkSchedule(WorkTimeMode.TIMED, zone, listOf(WorkDay(0, LocalTime(2, 15), LocalTime(4, 0))))
        val gap = schedule.on(LocalDate(2026, 3, 29)).single()
        assertTrue(gap.shiftedByZone)
        assertEquals(LocalTime(3, 15), assertNotNull(gap.interval).start.toLocalDateTime(zone).time)
        val overlap = schedule.on(LocalDate(2026, 10, 25)).single()
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), assertNotNull(overlap.interval).start)
        val allDay = WorkSchedule(WorkTimeMode.ALL_DAY, zone, listOf(WorkDay(0)))
        assertEquals(23.hours, allDay.on(gap.date).single().interval.let { assertNotNull(it).endExclusive - it.start })
        assertEquals(25.hours, allDay.on(overlap.date).single().interval.let { assertNotNull(it).endExclusive - it.start })
    }

    @Test fun multidayDoesNotBlockOvernightAndDateOnlyDoesNotBlockTime() {
        val date = LocalDate(2026, 11, 12)
        val schedule = WorkSchedule(WorkTimeMode.TIMED, TimeZone.UTC,
            listOf(WorkDay(0, LocalTime(9, 0), LocalTime(12, 0)), WorkDay(2, LocalTime(15, 0), LocalTime(17, 0))))
        val adjacent = WorkInterval(Instant.parse("2026-11-12T12:00:00Z"), Instant.parse("2026-11-14T15:00:00Z"))
        assertTrue(schedule.on(date).none { assertNotNull(it.interval).overlaps(adjacent) })
        assertNull(WorkSchedule(WorkTimeMode.DATE_ONLY, TimeZone.UTC, listOf(WorkDay(0))).on(date).single().interval)
    }
}
