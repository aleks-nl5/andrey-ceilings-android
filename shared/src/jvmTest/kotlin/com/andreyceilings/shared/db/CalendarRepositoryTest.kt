package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calendar.*
import com.andreyceilings.shared.objects.ObjectsClientsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant

class CalendarRepositoryTest {
    private val today = LocalDate(2026, 11, 27)
    private val zone = TimeZone.UTC
    private val clock = object : Clock { override fun now() = Instant.parse("2026-11-27T08:00:00Z") }

    @Test fun exactMovedBeyondSearchHorizonAndStaleFormCannotReviveTombstone() = fixture { _, calendar ->
        val first = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(today, WorkFrequency.WEEKLY)))))
        val second = WorkOccurrenceKey(first.rootUuid, today.shift(7))
        val work = assertNotNull(calendar.exact(second))
        val moved = today.shift(400)
        val command = CalendarSave(work.draft.copy(date = moved, title = "Moved work"), second, work.version)
        assertEquals(second, saved(calendar.save(command)))
        assertTrue(calendar.period(WorkDateRange(second.originalDate, second.originalDate), zone).isEmpty())
        assertEquals(second, calendar.period(WorkDateRange(moved, moved), zone).first { it.draft.title == "Moved work" }.key)
        assertEquals(second, calendar.search("moved").single().key)
        val current = assertNotNull(calendar.exact(second))
        assertTrue(calendar.delete(second, current.version, CalendarEditScope.OCCURRENCE))
        assertNull(calendar.exact(second))
        assertEquals(CalendarSaveResult.Stale, calendar.save(command))
    }

    @Test fun forwardSplitInheritsFieldsAndKeepsTerminalHistory() = fixture { _, calendar ->
        val rule = WorkRecurrence(today, WorkFrequency.DAILY, count = 5)
        val first = saved(calendar.save(CalendarSave(draft(recurrence = rule))))
        fun key(offset: Int) = WorkOccurrenceKey(first.rootUuid, today.shift(offset))
        val future = assertNotNull(calendar.exact(key(3)))
        saved(calendar.save(CalendarSave(future.draft.copy(notes = "personal"), future.key, future.version)))
        val past = assertNotNull(calendar.exact(key(1)))
        saved(calendar.save(CalendarSave(past.draft.copy(status = WorkStatus.COMPLETED), past.key, past.version)))
        val split = assertNotNull(calendar.exact(key(2)))
        saved(calendar.save(CalendarSave(split.draft.copy(title = "New series", recurrence = null),
            split.key, split.version, CalendarEditScope.THIS_AND_FOLLOWING)))
        assertEquals("Service visit", calendar.exact(first)?.draft?.title)
        assertEquals("Service visit", calendar.exact(past.key)?.draft?.title)
        assertEquals(WorkStatus.COMPLETED, calendar.exact(past.key)?.draft?.status)
        assertEquals("New series", calendar.exact(future.key)?.draft?.title)
        assertEquals("personal", calendar.exact(future.key)?.draft?.notes)
        assertNull(calendar.exact(key(5)))
    }

    @Test fun earlierSeriesMoveKeepsBothSlotsAndTheirExactRoutes() = fixture { _, calendar ->
        val first = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(today, WorkFrequency.DAILY)))))
        val future = WorkOccurrenceKey(first.rootUuid, today.shift(2))
        val before = assertNotNull(calendar.exact(future))
        val move = CalendarSave(before.draft.copy(date = today, recurrence = null, title = "Shifted series"),
            future, before.version, CalendarEditScope.THIS_AND_FOLLOWING)
        assertEquals(future, saved(calendar.save(move)))
        val sameDay = calendar.period(WorkDateRange(today, today), zone)
        assertEquals(setOf(first, future), sameDay.map { it.key }.toSet())
        assertEquals("Service visit", calendar.exact(first)?.draft?.title)
        assertEquals("Shifted series", calendar.exact(future)?.draft?.title)
        assertEquals(today.shift(1), calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(3)))?.draft?.date)
    }

    @Test fun incompatibleForwardRuleRetainsChangedWorkAndNeverRevivesATombstone() = fixture { _, calendar ->
        val first = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(today, WorkFrequency.DAILY)))))
        fun key(offset: Int) = WorkOccurrenceKey(first.rootUuid, today.shift(offset))
        val original = assertNotNull(calendar.exact(key(3)))
        saved(calendar.save(CalendarSave(original.draft.copy(title = "Exception retained"), original.key, original.version)))
        val deleted = assertNotNull(calendar.exact(key(4)))
        assertTrue(calendar.delete(deleted.key, deleted.version, CalendarEditScope.OCCURRENCE))
        val changed = assertNotNull(calendar.exact(key(1)))
        val weekly = WorkRecurrence(changed.draft.date, WorkFrequency.WEEKLY)
        val result = assertIs<CalendarSaveResult.Saved>(calendar.save(CalendarSave(
            changed.draft.copy(recurrence = weekly), changed.key, changed.version, CalendarEditScope.THIS_AND_FOLLOWING)))
        assertEquals(1, result.detached)
        assertEquals("Exception retained", calendar.exact(original.key)?.draft?.title)
        assertNull(calendar.exact(original.key)?.draft?.recurrence)
        assertNull(calendar.exact(deleted.key))
        assertEquals(original.key, calendar.search("Exception retained").single().key)
    }

    @Test fun conflictsAreTimeBoundAndConfirmationRechecksFreshIntervals() = fixture { _, calendar ->
        val original = draft(timed = true)
        val key = saved(calendar.save(CalendarSave(original)))
        val command = CalendarSave(draft(timed = true).copy(title = "Another visit"))
        val first = assertIs<CalendarSaveResult.Conflicts>(calendar.save(command))
        assertEquals(1, first.confirmation.conflicts.size)
        val existing = assertNotNull(calendar.exact(key))
        val changed = original.copy(schedule = WorkSchedule(WorkTimeMode.TIMED, zone,
            listOf(WorkDay(0, LocalTime(9, 15), LocalTime(11, 0)))))
        saved(calendar.save(CalendarSave(changed, key, existing.version)))
        val refreshed = assertIs<CalendarSaveResult.Conflicts>(calendar.save(command, first.confirmation))
        assertNotEquals(first.confirmation, refreshed.confirmation)
        saved(calendar.save(command, refreshed.confirmation))
        val dateOnly = assertIs<CalendarSaveResult.Saved>(calendar.save(CalendarSave(draft())))
        assertNotNull(calendar.exact(dateOnly.key))
    }

    @Test fun linkedDeletionNeedsFreshConfirmationAndKeepsHistory() = fixture { db, calendar ->
        val client = ClientRepository(db)
        val clientId = client.createReturningId("Client contact", phone = "555", createdAt = 12)
        val clientUuid = assertNotNull(client.find(clientId)).uuid
        val projects = ProjectRepository(db)
        val projectId = projects.createReturningId(null, "Site", "Site address", createdAt = 13)
        val projectUuid = assertNotNull(projects.find(projectId)).uuid
        val measurements = MeasurementRepository(db)
        val ceilingId = measurements.createReturningId(projectId, "Ceiling", createdAt = 14)
        val ceilingUuid = assertNotNull(measurements.find(ceilingId)).uuid
        val standalone = saved(calendar.save(CalendarSave(draft().copy(clientUuid = clientUuid))))
        val installation = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(today, WorkFrequency.DAILY))
            .copy(projectUuid = projectUuid, typeCode = "INSTALL", ceilings = listOf(WorkCeiling(ceilingUuid, "")), explicitCeilings = true))))
        val objects = ObjectsClientsStore(db) { clock.now().toEpochMilliseconds() }
        assertFailsWith<IllegalStateException> { objects.deleteProject(projectId) }
        val ceiling = assertFailsWith<CalendarDeletionConfirmationRequired> { measurements.delete(ceilingId) }.impact
        val beforeRemoval = assertNotNull(calendar.exact(installation))
        saved(calendar.save(CalendarSave(beforeRemoval.draft.copy(title = "Updated installation"), installation, beforeRemoval.version)))
        val refreshed = assertFailsWith<CalendarDeletionConfirmationRequired> { measurements.delete(ceilingId, ceiling) }.impact
        assertNotEquals(ceiling, refreshed)
        assertNotNull(measurements.find(ceilingId))
        measurements.delete(ceilingId, refreshed)
        val linked = assertNotNull(calendar.exact(installation))
        assertTrue(linked.draft.ceilings.single().removed)
        assertTrue(linked.draft.explicitCeilings)
        val removeClient = assertFailsWith<CalendarDeletionConfirmationRequired> { client.delete(clientId) }.impact
        client.delete(clientId, removeClient)
        assertEquals("555", calendar.exact(standalone)?.contact?.phone)
        assertEquals(clientUuid, calendar.exact(standalone)?.draft?.clientUuid)
        val removeProject = assertFailsWith<CalendarDeletionConfirmationRequired> { objects.deleteProject(projectId) }.impact
        objects.deleteProject(projectId, removeProject)
        val cancelled = assertNotNull(calendar.exact(installation))
        assertEquals("Site", cancelled.projectLabel)
        assertEquals(WorkStatus.CANCELLED, cancelled.draft.status)
        assertNull(calendar.exact(WorkOccurrenceKey(installation.rootUuid, today.shift(20))))
    }

    @Test fun confirmingAnExactCompletedInstallationUpdatesOnlyProjectWorkStage() = fixture { db, calendar ->
        val projects = ProjectRepository(db, clock)
        val projectId = projects.createReturningId(null, "Install location", createdAt = 33)
        projects.updatePaymentStatus(projectId, "partial", updatedAt = 34)
        val uuid = assertNotNull(projects.find(projectId)).uuid
        val command = CalendarSave(draft().copy(projectUuid = uuid, typeCode = "INSTALL"))
        val key = saved(calendar.save(command))
        val objects = ObjectsClientsStore(db) { clock.now().toEpochMilliseconds() }
        runBlocking {
            assertFalse(objects.completeCalendarProject(uuid, key))
            val fresh = assertNotNull(calendar.exact(key))
            val result = assertIs<CalendarSaveResult.Saved>(calendar.save(CalendarSave(
                fresh.draft.copy(status = WorkStatus.COMPLETED), key, fresh.version)))
            assertTrue(result.installationCompleted)
            assertEquals("measurement", projects.find(projectId)?.status)
            assertTrue(objects.completeCalendarProject(uuid, key))
            assertEquals("completed", projects.find(projectId)?.status)
            assertEquals("partial", projects.find(projectId)?.paymentStatus)
        }
    }

    @Test fun sessionCacheClearsWhenClosedAndFindsDistantWork() = fixture { db, calendar ->
        val future = today.shift(620)
        val key = saved(calendar.save(CalendarSave(draft().copy(date = future))))
        assertEquals(key, calendar.upcoming(today, null, 4).single().key)
        runBlocking {
            val store = CalendarStore(db, clock)
            val range = WorkDateRange(future, future)
            assertEquals(key, assertIs<CalendarRead.Ready<List<CalendarWork>>>(store.period(range, zone).first()).value.single().key)
            assertNotNull(store.cached(range, zone))
            store.closeAndAwait()
            assertNull(store.cached(range, zone))
            assertNull(assertIs<CalendarRead.Unavailable<List<CalendarWork>>>(store.period(range, zone).first()).previous)
        }
    }

    private fun fixture(assertions: (PrototypeDatabase, CalendarRepository) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            assertions(db, CalendarRepository(db, clock) { zone })
        }
    }

    private fun draft(timed: Boolean = false, recurrence: WorkRecurrence? = null) = CalendarWorkDraft(
        today,
        if (timed) WorkSchedule(WorkTimeMode.TIMED, zone, listOf(WorkDay(0, LocalTime(9, 0), LocalTime(10, 0))))
        else WorkSchedule(WorkTimeMode.DATE_ONLY, zone, listOf(WorkDay(0))),
        "Service visit", "MEASURE", recurrence = recurrence
    )

    private fun saved(result: CalendarSaveResult) = assertIs<CalendarSaveResult.Saved>(result).key
}
