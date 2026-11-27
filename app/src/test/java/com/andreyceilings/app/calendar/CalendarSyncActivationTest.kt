package com.andreyceilings.app.calendar

import com.andreyceilings.shared.calendar.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CalendarSyncActivationTest {
    private val date = LocalDate(2026, 11, 27)
    private val existing = work("prior")
    private val new = work("new")
    private val on = CalendarWorkIntegration(syncMode = CalendarSyncMode.ON)

    @Test fun disableSuspendsThePreviousRuleAndNewIndividualOptInStillWorks() {
        val previous = snapshot(true, mapOf(existing.key to on))
        val disabled = snapshot(false, mapOf(existing.key to on, new.key to on))
        val suspended = previous.enabledSyncRules().mapTo(mutableSetOf()) { it.deviceToken() }
        assertFalse(syncAllowed(disabled, existing, suspended))
        assertTrue(syncAllowed(disabled, new, suspended))
        assertTrue(syncAllowed(disabled, existing, emptySet()))
        assertFalse(reconcileSyncCandidate(true, leftHistory = true, activeMapping = false))
        assertTrue(reconcileSyncCandidate(true, leftHistory = false, activeMapping = false))
    }

    @Test fun followingRuleStaysSuspendedForFutureOccurrencesUntilExplicitOverride() {
        val recurring = existing.copy(key = existing.key.copy(originalDate = LocalDate(2030, 1, 1)))
        val before = CalendarIntegrationSnapshot(CalendarIntegrationSettings(syncEnabled = true),
            emptyMap(), emptyMap(), mapOf(existing.key to on))
        val suspended = before.enabledSyncRules().mapTo(mutableSetOf()) { it.deviceToken() }
        val disabled = before.copy(settings = CalendarIntegrationSettings(syncEnabled = false))
        assertFalse(syncAllowed(disabled, recurring, suspended))
        assertTrue(syncAllowed(disabled.copy(occurrences = mapOf(recurring.key to on)), recurring, suspended))
    }

    private fun snapshot(sync: Boolean, rules: Map<WorkOccurrenceKey, CalendarWorkIntegration>) =
        CalendarIntegrationSnapshot(CalendarIntegrationSettings(syncEnabled = sync),
            mapOf("MEASURE" to CalendarTypeIntegration(syncEnabled = true)), rules, emptyMap())

    private fun work(root: String) = CalendarWork(WorkOccurrenceKey(root, date), 1,
        CalendarWorkDraft(date, WorkSchedule(WorkTimeMode.DATE_ONLY, TimeZone.UTC, listOf(WorkDay(0))),
            "Fixture", "MEASURE"), null, WorkContact())
}
