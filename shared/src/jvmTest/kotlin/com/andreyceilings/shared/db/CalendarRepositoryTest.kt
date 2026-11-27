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

    @Test fun conflictPreviewUsesRemappedPersonalHoursAfterForwardMove() = fixture { _, calendar ->
        val first = saved(calendar.save(CalendarSave(draft(timed = true,
            recurrence = WorkRecurrence(today, WorkFrequency.DAILY)))))
        val custom = assertNotNull(calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(2))))
        val afternoon = WorkSchedule(WorkTimeMode.TIMED, zone, listOf(WorkDay(0, LocalTime(15, 0), LocalTime(16, 0))))
        saved(calendar.save(CalendarSave(custom.draft.copy(schedule = afternoon), custom.key, custom.version)))
        val other = saved(calendar.save(CalendarSave(draft(timed = true).copy(date = today.shift(3), schedule = afternoon))))
        val boundary = assertNotNull(calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(1))))
        val shift = CalendarSave(boundary.draft.copy(date = today.shift(2), recurrence = null),
            boundary.key, boundary.version, CalendarEditScope.THIS_AND_FOLLOWING)
        val preview = assertNotNull(calendar.previewConflicts(shift))
        assertEquals(1, preview.count)
        val warning = assertIs<CalendarSaveResult.Conflicts>(calendar.save(shift))
        assertEquals(custom.key, warning.confirmation.conflicts.single().proposed)
        assertEquals(other, warning.confirmation.conflicts.single().other)
        saved(calendar.save(shift, warning.confirmation))
        assertEquals(today.shift(3), calendar.exact(custom.key)?.draft?.date)
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

    @Test fun pastSeriesBoundaryCannotRewriteOtherPastOccurrences() = fixture { _, calendar ->
        val start = today.shift(-4)
        val first = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(start, WorkFrequency.DAILY)).copy(date = start))))
        val source = assertNotNull(calendar.exact(first))
        val following = CalendarSave(source.draft.copy(title = "Changed past", recurrence = null),
            source.key, source.version, CalendarEditScope.THIS_AND_FOLLOWING)
        assertNull(calendar.previewConflicts(following))
        assertFailsWith<IllegalArgumentException> { calendar.save(following) }
        assertFalse(calendar.cancelFollowing(source.key, source.version))
        assertFalse(calendar.delete(source.key, source.version, CalendarEditScope.THIS_AND_FOLLOWING))
        saved(calendar.save(following.copy(scope = CalendarEditScope.OCCURRENCE)))
        assertEquals("Changed past", calendar.exact(first)?.draft?.title)
        assertEquals("Service visit", calendar.exact(WorkOccurrenceKey(first.rootUuid, start.shift(1)))?.draft?.title)
        assertEquals("Service visit", calendar.exact(WorkOccurrenceKey(first.rootUuid, today))?.draft?.title)
    }

    @Test fun forwardEditsInheritDurationAndPreserveExplicitStartEvenAfterCoincidence() = fixture { _, calendar ->
        fun schedule(start: Int, end: Int) = WorkSchedule(WorkTimeMode.TIMED, zone,
            listOf(WorkDay(0, LocalTime(start, 0), LocalTime(end, 0))))
        val first = saved(calendar.save(CalendarSave(draft(timed = true, recurrence = WorkRecurrence(today, WorkFrequency.DAILY)))))
        fun occurrence(offset: Int) = assertNotNull(calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(offset))))
        val personal = occurrence(5)
        saved(calendar.save(CalendarSave(personal.draft.copy(schedule = schedule(15, 16)), personal.key, personal.version)))
        fun change(offset: Int, schedule: WorkSchedule) {
            val boundary = occurrence(offset)
            saved(calendar.save(CalendarSave(boundary.draft.copy(schedule = schedule, recurrence = null),
                boundary.key, boundary.version, CalendarEditScope.THIS_AND_FOLLOWING)))
        }
        change(1, schedule(9, 11))
        assertEquals(schedule(15, 17), occurrence(5).draft.schedule)
        change(2, schedule(15, 17))
        assertEquals(schedule(15, 17), occurrence(5).draft.schedule)
        change(3, schedule(12, 15))
        assertEquals(schedule(15, 18), occurrence(5).draft.schedule)
    }

    @Test fun forwardEditsInheritStartWhenOnlyDurationWasOverridden() = fixture { _, calendar ->
        fun schedule(start: Int, end: Int) = WorkSchedule(WorkTimeMode.TIMED, zone,
            listOf(WorkDay(0, LocalTime(start, 0), LocalTime(end, 0))))
        val first = saved(calendar.save(CalendarSave(draft(timed = true, recurrence = WorkRecurrence(today, WorkFrequency.DAILY)))))
        val key = WorkOccurrenceKey(first.rootUuid, today.shift(3))
        val personal = assertNotNull(calendar.exact(key))
        saved(calendar.save(CalendarSave(personal.draft.copy(schedule = schedule(9, 12)), key, personal.version)))
        val boundary = assertNotNull(calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(1))))
        saved(calendar.save(CalendarSave(boundary.draft.copy(schedule = schedule(10, 11), recurrence = null),
            boundary.key, boundary.version, CalendarEditScope.THIS_AND_FOLLOWING)))
        assertEquals(schedule(10, 13), calendar.exact(key)?.draft?.schedule)
    }

    @Test fun multidayExceptionOnlyOverridesTheIndividuallyEditedDay() = fixture { _, calendar ->
        fun schedule(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int) =
            WorkSchedule(WorkTimeMode.TIMED, zone, listOf(
                WorkDay(0, LocalTime(firstStart, 0), LocalTime(firstEnd, 0)),
                WorkDay(1, LocalTime(secondStart, 0), LocalTime(secondEnd, 0))))
        val first = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(today, WorkFrequency.WEEKLY))
            .copy(schedule = schedule(9, 10, 11, 12)))))
        val key = WorkOccurrenceKey(first.rootUuid, today.shift(14))
        val personal = assertNotNull(calendar.exact(key))
        saved(calendar.save(CalendarSave(personal.draft.copy(schedule = schedule(15, 16, 11, 12)), key, personal.version)))
        val boundary = assertNotNull(calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(7))))
        saved(calendar.save(CalendarSave(boundary.draft.copy(schedule = schedule(9, 11, 12, 14), recurrence = null),
            boundary.key, boundary.version, CalendarEditScope.THIS_AND_FOLLOWING)))
        assertEquals(schedule(15, 17, 12, 14), calendar.exact(key)?.draft?.schedule)
    }

    @Test fun deletingSeriesProjectDoesNotCancelRelinkedOccurrence() = fixture { db, calendar ->
        val projects = ProjectRepository(db, clock)
        val a = projects.createReturningId(null, "Project A", "Address A", createdAt = 12)
        val b = projects.createReturningId(null, "Project B", "Address B", createdAt = 13)
        val aUuid = assertNotNull(projects.find(a)).uuid
        val bUuid = assertNotNull(projects.find(b)).uuid
        val first = saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(today, WorkFrequency.DAILY))
            .copy(projectUuid = aUuid, typeCode = "INSTALL"))))
        val key = WorkOccurrenceKey(first.rootUuid, today.shift(40))
        val personal = assertNotNull(calendar.exact(key))
        saved(calendar.save(CalendarSave(personal.draft.copy(projectUuid = bUuid), key, personal.version)))
        assertEquals(key, calendar.upcoming(today, bUuid, 1).single().key)
        projects.delete(a, assertNotNull(calendar.projectDeletionImpact(aUuid)))
        val retained = assertNotNull(calendar.exact(key))
        assertEquals(bUuid, retained.draft.projectUuid)
        assertEquals("Address B", retained.contact.address)
        assertEquals(WorkStatus.PLANNED, retained.draft.status)
        assertNull(retained.draft.recurrence)
        assertEquals(key, calendar.upcoming(today, bUuid, 1).single().key)
        assertEquals(WorkStatus.CANCELLED, calendar.exact(first)?.draft?.status)
        assertNull(calendar.exact(WorkOccurrenceKey(first.rootUuid, today.shift(41))))
    }

    @Test fun expiredFiniteInstallationDoesNotSuppressScheduleAction() = fixture { db, calendar ->
        val projects = ProjectRepository(db, clock)
        val id = projects.createReturningId(null, "Site", createdAt = 12)
        val uuid = assertNotNull(projects.find(id)).uuid
        val start = today.shift(-7)
        saved(calendar.save(CalendarSave(draft(recurrence = WorkRecurrence(start, WorkFrequency.DAILY, count = 2))
            .copy(date = start, typeCode = "INSTALL", projectUuid = uuid))))
        assertFalse(calendar.hasActiveInstallation(uuid))
        val future = saved(calendar.save(CalendarSave(draft().copy(date = today.shift(2), typeCode = "INSTALL", projectUuid = uuid))))
        assertTrue(calendar.hasActiveInstallation(uuid))
        val work = assertNotNull(calendar.exact(future))
        saved(calendar.save(CalendarSave(work.draft.copy(status = WorkStatus.COMPLETED), future, work.version)))
        assertFalse(calendar.hasActiveInstallation(uuid))
    }

    @Test fun upcomingOrdersByNextWorkingDayBeforeApplyingLimit() = fixture { _, calendar ->
        val multiday = saved(calendar.save(CalendarSave(draft().copy(date = today.shift(-2),
            schedule = WorkSchedule(WorkTimeMode.DATE_ONLY, zone, listOf(WorkDay(0), WorkDay(4)))))))
        val nearer = saved(calendar.save(CalendarSave(draft().copy(date = today.shift(1)))))
        assertEquals(nearer, calendar.upcoming(today, null, 1).single().key)
        assertEquals(listOf(nearer, multiday), calendar.upcoming(today, null, 2).map { it.key })
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
            assertEquals(key, assertIs<CalendarRead.Ready<List<CalendarWork>>>(store.upcoming(today).first()).value.single().key)
            assertEquals(key, store.cachedUpcoming(today)?.single()?.key)
            assertIs<CalendarRead.Ready<CalendarFormOptions>>(store.options().first())
            assertNotNull(store.cachedOptions())
            store.closeAndAwait()
            assertNull(store.cached(range, zone))
            assertNull(store.cachedUpcoming(today))
            assertNull(store.cachedOptions())
            assertNull(assertIs<CalendarRead.Unavailable<List<CalendarWork>>>(store.period(range, zone).first()).previous)
        }
    }

    @Test fun calendarCreatesRealRegistryRecordsAndResolvesExactLinks() = fixture { db, _ ->
        runBlocking {
            val registry = ObjectsClientsStore(db) { clock.now().toEpochMilliseconds() }
            val store = registry.calendar
            try {
                val client = store.createClient(registry, com.andreyceilings.shared.objects.ClientDraft("New client", "123456"))
                val clientTarget = assertNotNull(store.clientTarget(null, client.uuid))
                val project = store.createProject(registry, com.andreyceilings.shared.objects.ProjectDraft("New site", clientTarget.id))
                assertEquals(client.uuid, project.clientUuid)
                val projectTarget = assertNotNull(store.projectTarget(project.uuid))
                assertEquals(project.uuid, registry.calendarProjectUuid(projectTarget.id))
                assertEquals(clientTarget, store.clientTarget(project.uuid, null))
                val options = assertIs<CalendarRead.Ready<CalendarFormOptions>>(store.options().first()).value
                assertTrue(options.projects.any { it.uuid == project.uuid && it.clientUuid == client.uuid })
                assertTrue(options.clients.any { it.uuid == client.uuid })
                val key = saved(store.save(CalendarSave(draft().copy(projectUuid = project.uuid, typeCode = "INSTALL"))))
                val work = assertNotNull(store.exact(key))
                assertEquals("New client", work.contact.name)
                assertNull(work.draft.clientUuid)
                val other = store.createClient(registry, com.andreyceilings.shared.objects.ClientDraft("Other client", "654321"))
                saved(store.save(CalendarSave(work.draft.copy(clientUuid = other.uuid), key, work.version)))
                assertEquals("Other client", store.exact(key)?.contact?.name)
                assertEquals(clientTarget, store.clientTarget(project.uuid, null))
            } finally { store.closeAndAwait(); registry.closeAndAwaitProjectReads() }
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
