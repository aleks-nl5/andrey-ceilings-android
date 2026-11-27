package com.andreyceilings.shared.calendar

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CalendarIntegrationPolicyTest {
    private val date = LocalDate(2026, 11, 27)
    private val key = WorkOccurrenceKey("work", date)

    @Test fun followingChangesKeepIndependentOccurrenceAndFieldOverrides() {
        val custom = ReminderRule(ReminderDay.FIRST, 0, 30)
        val first = CalendarWorkIntegration(ReminderMode.ADD, setOf(custom), CalendarSyncMode.ON)
        val later = key.copy(originalDate = date.shift(1))
        val latest = later.copy(originalDate = date.shift(2))
        val snapshot = CalendarIntegrationSnapshot(CalendarIntegrationSettings(), emptyMap(),
            mapOf(later to CalendarWorkIntegration(ReminderMode.OFF)),
            mapOf(key to first, latest to CalendarWorkIntegration(syncMode = CalendarSyncMode.OFF)))
        val work = sample(WorkTimeMode.DATE_ONLY)
        assertTrue(custom in snapshot.rules(work))
        assertTrue(snapshot.managed(work.copy(key = later)))
        assertTrue(snapshot.rules(work.copy(key = later)).isEmpty())
        assertTrue(custom in snapshot.rules(work.copy(key = latest)))
        assertFalse(snapshot.managed(work.copy(key = latest)))
    }

    @Test fun timedDaysHaveSeparateIntervalsAndRemindersAcrossDst() {
        val date = LocalDate(2026, 3, 29)
        val work = sample(WorkTimeMode.TIMED, date)
        val alerts = calendarReminders(work, standardReminderRules)
        assertEquals(setOf(
            Instant.parse("2026-03-28T01:30:00Z"),
            Instant.parse("2026-03-29T00:30:00Z"),
            Instant.parse("2026-03-30T23:30:00Z"),
        ), alerts.map { it.trigger }.toSet())
        assertEquals(listOf(0, 2), calendarExportParts(work).map { it.dayOffset })
    }

    @Test fun dateOnlyExportsUtcSpanAsFreeAndNotifiesAtLocalTimes() {
        val work = sample(WorkTimeMode.DATE_ONLY)
        val part = calendarExportParts(work).single()
        assertTrue(part.allDay && part.free)
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), part.interval.start)
        assertEquals(Instant.parse("2026-11-30T00:00:00Z"), part.interval.endExclusive)
        assertTrue(Instant.parse("2026-11-26T17:00:00Z") in
            calendarReminders(work, standardReminderRules).map { it.trigger })
        assertTrue(calendarReminders(work.copy(draft = work.draft.copy(status = WorkStatus.COMPLETED)),
            standardReminderRules).isEmpty())
    }

    private fun sample(mode: WorkTimeMode, day: LocalDate = date): CalendarWork {
        val days = listOf(0, 2).map {
            if (mode == WorkTimeMode.TIMED) WorkDay(it, LocalTime(2, 30), LocalTime(4, 30)) else WorkDay(it)
        }
        return CalendarWork(key.copy(originalDate = day), 1,
            CalendarWorkDraft(day, WorkSchedule(mode, TimeZone.of("Europe/Berlin"), days), "", "MEASURE"),
            null, WorkContact())
    }
}
