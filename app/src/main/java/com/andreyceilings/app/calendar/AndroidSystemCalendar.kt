package com.andreyceilings.app.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andreyceilings.app.R
import com.andreyceilings.shared.calendar.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant

internal data class SystemCalendarChoice(val identity: String, val label: String, val writable: Boolean)

internal class AndroidSystemCalendar(
    context: Context,
    private val state: CalendarDeviceState = CalendarDeviceState(context.applicationContext),
    private val isolatedStore: CalendarStore? = null,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    private data class Destination(val id: Long, val identity: String, val label: String, val writable: Boolean)

    val canRead get() = has(Manifest.permission.READ_CALENDAR)
    val canWrite get() = canRead && has(Manifest.permission.WRITE_CALENDAR)
    val busySelection get() = state.busyCalendars
    val destinationSelection get() = state.destination
    val paused get() = state.paused
    private fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun calendars(): List<Destination> {
        check(canRead)
        val columns = arrayOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.OWNER_ACCOUNT, Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_ACCESS_LEVEL)
        return checkNotNull(resolver.query(Calendars.CONTENT_URI, columns, null, null, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val provenance = (0..4).joinToString("|") { index ->
                        val value = cursor.getString(index).orEmpty()
                        "${value.length}:$value"
                    }
                    val hash = MessageDigest.getInstance("SHA-256").digest(provenance.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    add(Destination(id, "$id:$hash", cursor.getString(5).orEmpty(),
                        cursor.getInt(6) >= Calendars.CAL_ACCESS_CONTRIBUTOR))
                }
            }
        }
    }

    suspend fun choices(): List<SystemCalendarChoice> = withContext(Dispatchers.IO) {
        if (!canRead) return@withContext emptyList()
        calendars().map { SystemCalendarChoice(it.identity, it.label, it.writable) }
    }

    suspend fun selectBusy(calendars: Set<String>) = operations.withLock {
        withContext(Dispatchers.IO) {
            check(calendars.isEmpty() || calendars.all { selection -> calendars().any { it.identity == selection } })
            state.save("busy_calendars", calendars)
            invalidate()
        }
    }

    suspend fun selectDestination(identity: String, move: Boolean) = operations.withLock {
        withContext(Dispatchers.IO) {
            withCalendarEffects(context, isolatedStore) {
                requireDestination(identity)
                check(!move || !state.paused && state.previousDestination == null)
                if (move) recoverPendingInserts()
                state.changeDestination(identity, move)
            }
            invalidate()
            enqueueLiveStore()
        }
    }

    suspend fun createLocalCalendar(name: String = "libellaro-work"): String = operations.withLock {
        withContext(Dispatchers.IO) {
            check(canWrite && !state.paused)
            val account = context.packageName
            val existing = calendars().firstOrNull { calendar ->
                resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendar.id),
                    arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.NAME),
                    null, null, null)?.use {
                    it.moveToFirst() && it.getString(0) == account && it.getString(1) == CalendarContract.ACCOUNT_TYPE_LOCAL &&
                        it.getString(2) == name
                } == true
            }
            if (existing != null) return@withContext existing.identity
            val uri = Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            val inserted = checkNotNull(resolver.insert(uri, ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.OWNER_ACCOUNT, account)
                put(Calendars.NAME, name)
                put(Calendars.CALENDAR_DISPLAY_NAME, "Libellaro")
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.CALENDAR_TIME_ZONE, TimeZone.currentSystemDefault().id)
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
            }))
            val id = ContentUris.parseId(inserted)
            calendars().single { it.id == id && it.writable }.identity
        }
    }

    fun observe(): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                invalidate()
                enqueue(context)
            }
        }
        if (canRead) resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    suspend fun availability(range: WorkDateRange, zone: TimeZone): CalendarAvailability =
        operations.withLock { withContext(Dispatchers.IO) {
            val selected = state.busyCalendars
            if (selected.isEmpty()) return@withContext CalendarAvailability.Disabled
            if (state.paused || !canRead) return@withContext CalendarAvailability.Unknown
            try {
                val selectedCalendars = calendars().filter { it.identity in selected }
                val ids = selectedCalendars.map { it.id }
                if (ids.size != selected.size) return@withContext CalendarAvailability.Unknown
                val from = range.from.atStartOfDayIn(zone).toEpochMilliseconds()
                val end = range.through.nextDay().atStartOfDayIn(zone).toEpochMilliseconds()
                val uri = Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, from - TimeUnit.DAYS.toMillis(1))
                    ContentUris.appendId(it, end + TimeUnit.DAYS.toMillis(1))
                }.build()
                val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_ID,
                    Instances.AVAILABILITY, Instances.STATUS, Instances.CALENDAR_ID)
                val identities = selectedCalendars.associate { it.id to it.identity }
                val owned = mappings().filter { it.mode == "managed" && it.encoded !in state.leaving &&
                    identities[it.calendarId] == it.identity }
                    .associateBy { it.eventId to it.calendarId }
                val rows = checkNotNull(resolver.query(uri, projection,
                    "${Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }})",
                    ids.map(Long::toString).toTypedArray(), null)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            if (cursor.isNull(4)) return@withContext CalendarAvailability.Unknown
                            if (cursor.getInt(4) == Events.AVAILABILITY_FREE || cursor.getInt(5) == Events.STATUS_CANCELED) continue
                            val eventId = cursor.getLong(3)
                            if (owned[eventId to cursor.getLong(6)]?.let(::isOwnedManagedRow) == true) continue
                            val allDay = cursor.getInt(2) == 1
                            val start = Instant.fromEpochMilliseconds(cursor.getLong(0))
                            val finish = Instant.fromEpochMilliseconds(cursor.getLong(1))
                            val localStart = if (allDay) start.toLocalDateTime(TimeZone.UTC).date.atStartOfDayIn(zone) else start
                            val localEnd = if (allDay) finish.toLocalDateTime(TimeZone.UTC).date.atStartOfDayIn(zone) else finish
                            if (localStart >= localEnd) continue
                            if (localStart.toEpochMilliseconds() < end && localEnd.toEpochMilliseconds() > from) {
                                add(ExternalBusyInterval(WorkInterval(localStart, localEnd), allDay))
                            }
                        }
                    }.toSet()
                }
                if (!canRead || state.paused || selected != state.busyCalendars ||
                    calendars().filter { it.identity in selected }.map { it.id }.toSet() != ids.toSet())
                    CalendarAvailability.Unknown
                else CalendarAvailability.Available(rows)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalendarAvailability.Unknown
            }
        } }

    suspend fun confirmRestore() = operations.withLock {
        withContext(Dispatchers.IO) {
            withCalendarEffects(context, isolatedStore) {
                val known = state.busyCalendars + listOfNotNull(state.destination, state.previousDestination)
                if (known.isNotEmpty()) {
                    check(canRead && calendars().map { it.identity }.containsAll(known))
                    state.destination?.let { requireDestination(it) }
                    state.previousDestination?.let { requireDestination(it) }
                }
                recoverPendingInserts()
                for (mapping in mappings().filter { it.mode == "managed" && it.encoded !in state.leaving }) {
                    val event = row(mapping.eventId)
                    check(event == null || event.calendarId == mapping.calendarId && event.marker == mapping.marker)
                }
                state.resumeAfterRestore()
            }
            invalidate()
            if (isolatedStore == null) CalendarReminderRuntime.enqueue(context)
            enqueueLiveStore()
        }
    }

    suspend fun manualExport(key: WorkOccurrenceKey, version: Long, managed: Boolean,
        confirmation: CalendarExportConfirmation? = null): CalendarManualExportResult = operations.withLock {
        withContext(Dispatchers.IO) {
            var changed = false
            try {
                withCalendarEffects(context, isolatedStore) {
                    check(!state.paused)
                    finishPendingSyncTransition()
                    val destination = requireDestination(state.destination)
                    val work = checkNotNull(exact(key))
                    check(work.version == version && work.draft.status.active)
                    recoverPendingInserts()
                    if (managed) {
                        val oneShots = ownedOneShots(destination, key)
                        if (oneShots.isNotEmpty()) {
                            val pending = CalendarExportConfirmation(destination.identity, oneShots.mapTo(mutableSetOf()) { it.eventId })
                            if (confirmation != pending) return@withCalendarEffects CalendarManualExportResult.ConfirmAdoption(pending)
                            changed = true
                            promoteOneShots(oneShots, version)
                        } else {
                            check(confirmation == null)
                            changed = true
                            activateManaged(key, version)
                        }
                    } else changed = true
                    val selected = snapshot()
                    export(work, selected.settings, types(), destination, if (managed) "managed" else "once")
                    CalendarManualExportResult.Exported
                }
            } finally {
                if (changed) enqueueLiveStore()
            }
        }
    }

    private fun ownedOneShots(destination: Destination, key: WorkOccurrenceKey): List<Mapping> =
        mappings().filter { it.identity == destination.identity && it.key == key && it.mode == "once" }.filter { mapping ->
            val existing = row(mapping.eventId) ?: return@filter false
            check(matchesOwnedRow(existing, mapping.calendarId, mapping.marker))
            if (existing.deleted) return@filter false
            check(find(destination.id, mapping.marker.substringBeforeLast("/once/") + "/").singleOrNull() == existing)
            true
        }

    private fun CalendarStore.IntegrationAccess.activateManaged(key: WorkOccurrenceKey, version: Long) {
        val suspended = suspendedRules(snapshot())
        check(setManagedExport(key, version))
        state.replaceSuspendedRules(suspended - CalendarSyncRule(key, false, CalendarSyncMode.ON).deviceToken())
    }

    private fun CalendarStore.IntegrationAccess.promoteOneShots(oneShots: List<Mapping>, version: Long) {
        val promoted = oneShots.map { old ->
            val marker = old.marker.substringBeforeLast("/once/") + "/managed/${state.calendarOwner}"
            old.copy(mode = "managed", marker = marker)
        }
        val tokens = promoted.map { "${it.identity}|${it.marker}|${it.eventId}|$version|${state.generation}" }
        state.save("pending_inserts", state.pendingInserts + tokens)
        val updates = arrayListOf<ContentProviderOperation>()
        for ((old, current) in oneShots.zip(promoted)) {
            updates += ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                .withValue(Events.CUSTOM_APP_URI, current.marker)
                .withSelection(OWNED, ownership(old)).withExpectedCount(1).build()
            updates += ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection(OWNED, ownership(current)).withExpectedCount(1).build()
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, updates)
        activateManaged(oneShots.first().key, version)
        for (index in tokens.indices) state.finishInsert(tokens[index], promoted[index].encoded, oneShots[index].encoded)
    }

    suspend fun disableSync(remove: Boolean) = operations.withLock {
        withContext(Dispatchers.IO) {
            withCalendarEffects(context, isolatedStore) {
                val current = snapshot()
                val managed = mappings().filter { it.mode == "managed" && it.encoded !in state.leaving }
                // Record the choice for mapped rows and interrupted inserts even if provider access is currently revoked.
                state.suspendSync(managed.mapTo(mutableSetOf()) { it.encoded },
                    current.enabledSyncRules().mapTo(mutableSetOf()) { it.deviceToken() }, remove)
                finishPendingSyncTransition()
            }
            enqueueLiveStore()
        }
    }

    suspend fun enableSync() = operations.withLock {
        withContext(Dispatchers.IO) {
            withCalendarEffects(context, isolatedStore) {
                check(!state.paused)
                requireDestination(state.destination)
                state.beginSyncEnable()
                finishPendingSyncTransition()
            }
            enqueueLiveStore()
        }
    }

    private fun CalendarStore.IntegrationAccess.finishPendingSyncTransition() {
        val transition = state.syncTransition ?: return
        val current = snapshot().settings
        if (current.syncEnabled != transition.enabled) saveSettings(current.copy(syncEnabled = transition.enabled))
        state.finishSyncTransition(transition)
    }

    private fun suspendedRules(snapshot: CalendarIntegrationSnapshot): Set<String> {
        if (state.values("sync_suspended").isEmpty()) return state.suspendedRules
        val manual = state.values("manual_after_disable")
        val activated = state.values("activated_legacy_rules")
        return snapshot.enabledSyncRules().filterNot {
            it.deviceToken() in activated || !it.following && it.key.deviceKey() in manual
        }
            .mapTo(mutableSetOf()) { it.deviceToken() }.also(state::replaceSuspendedRules)
    }

    suspend fun reconcile() = operations.withLock {
        withContext(Dispatchers.IO) {
            if (state.paused || state.destination == null) return@withContext
            withCalendarEffects(context, isolatedStore) {
                if (state.paused || state.destination == null) return@withCalendarEffects
                finishPendingSyncTransition()
                val zone = TimeZone.currentSystemDefault()
                val now = Clock.System.now()
                val today = now.toLocalDateTime(zone).date
                var plan = plan(today, today.shiftBy(89), zone, false)
                val destination: Destination
                val previous: Destination?
                try {
                    destination = requireDestination(state.destination)
                    previous = state.previousDestination?.let { requireDestination(it) }
                    if (state.pendingInserts.isNotEmpty()) {
                        recoverPendingInserts()
                        // A recovered confirmed promotion can commit an explicit sync rule.
                        plan = plan(today, today.shiftBy(89), zone, false)
                    }
                } catch (failure: Exception) {
                    _problems.value = plan.works.filter { syncAllowed(plan.snapshot, it.work, state.suspendedRules) }
                        .map { it.work.key }.toSet() + mappings().filter { it.mode == "managed" }.map { it.key }
                    throw failure
                }
                val suspended = suspendedRules(plan.snapshot)
                val allMappings = mappings()
                val leftKeys = allMappings.filter { it.mode == "managed" && it.encoded in state.leaving }.mapTo(mutableSetOf()) { it.key }
                val known = allMappings.filter { it.mode == "managed" && it.encoded !in state.leaving }
                val remove = state.values("remove_queue")
                val activeKeys = known.filterNot { it.encoded in remove }.mapTo(mutableSetOf()) { it.key }
                val eligible = plan.works.filter { (work, standalone) ->
                    work.draft.status.active && (standalone || work.upcoming(now)) &&
                        reconcileSyncCandidate(syncAllowed(plan.snapshot, work, suspended), work.key in leftKeys, work.key in activeKeys)
                }.associate { it.work.key to it.work }
                val exact = known.map { it.key }.distinct().associateWith { eligible[it] ?: exact(it) }
                for (mapping in known.filter { it.encoded in remove }) {
                    if (exact[mapping.key]?.draft?.status == WorkStatus.COMPLETED) {
                        state.save("left_events", state.leaving + mapping.encoded)
                    } else {
                        deleteOwned(mapping)
                        forget(mapping)
                    }
                    state.save("remove_queue", state.values("remove_queue") - mapping.encoded)
                }
                state.save("remove_queue", state.values("remove_queue").intersect(state.exports))
                val failures = mutableSetOf<WorkOccurrenceKey>()
                // Deletions must not wait behind repairs/exports of unrelated works.
                val terminal = known.filter { mapping ->
                    mapping.encoded !in remove && (exact[mapping.key] == null ||
                        exact[mapping.key]?.draft?.status == WorkStatus.CANCELLED)
                }
                for (mapping in terminal) {
                    try {
                        deleteOwned(mapping)
                        forget(mapping)
                    } catch (_: Exception) {
                        failures += mapping.key
                    }
                }
                for (work in eligible.values) {
                    try {
                        export(work, plan.snapshot.settings, plan.types, destination, "managed")
                    } catch (_: Exception) {
                        failures += work.key
                    }
                }
                for (mapping in known.filter { it.encoded !in remove && it !in terminal && it.key !in failures }) {
                    val work = exact[mapping.key]
                    when {
                        work?.draft?.status == WorkStatus.COMPLETED ||
                            work?.draft?.status?.active == true && !syncAllowed(plan.snapshot, work, suspended) ->
                            state.save("left_events", state.leaving + mapping.encoded)
                        work == null || work.draft.status == WorkStatus.CANCELLED ||
                            mapping.key !in eligible && work.upcoming(now) ||
                            mapping.key in eligible && mapping.calendarId == destination.id &&
                            calendarExportParts(work).none { it.dayOffset == mapping.offset } ||
                            previous?.id == mapping.calendarId && mapping.key in eligible -> {
                            try {
                                deleteOwned(mapping)
                                forget(mapping)
                            } catch (_: Exception) {
                                failures += mapping.key
                            }
                        }
                    }
                }
                if (previous != null && mappings().none { it.calendarId == previous.id && it.mode == "managed" &&
                        it.encoded !in state.leaving }) state.finishMove()
                _problems.value = failures
                if (failures.isNotEmpty()) error("Calendar reconciliation incomplete")
            }
        }
    }

    private fun CalendarStore.IntegrationAccess.recoverPendingInserts() {
        for (token in state.pendingInserts) {
            val fields = token.split('|')
            check(fields.size == 2 || fields.size == 5)
            val (identity, marker) = fields
            // A journal entry the user chose to leave never authorizes effects on an unavailable calendar.
            val destination = if (token in state.leftPending) {
                if (!canRead) continue
                calendars().singleOrNull { it.identity == identity } ?: continue
            } else requireDestination(identity)
            val prefix = marker.substringBeforeLast('/').substringBeforeLast('/') + "/"
            val parts = marker.removePrefix("libellaro://work/").split('/')
            check(marker.startsWith("libellaro://work/") && parts.size == 5 &&
                parts[3] in setOf("managed", "once") && parts[4] == state.calendarOwner)
            val key = checkNotNull(parseWorkDeviceKey(parts.take(2).joinToString("/")))
            val offset = parts[2].toInt()
            if (fields.size == 5) {
                val eventId = fields[2].toLong()
                val expectedVersion = fields[3].toLong()
                val old = mappings().single {
                    it.identity == identity && it.key == key && it.offset == offset && it.eventId == eventId &&
                        it.mode == "once"
                }
                val event = row(eventId)
                if (event == null || event.deleted || matchesOwnedRow(event, destination.id, old.marker)) {
                    if (event != null) {
                        check(matchesOwnedRow(event, destination.id, old.marker) ||
                            matchesOwnedRow(event, destination.id, marker))
                    }
                    state.finishInsert(token, null)
                    continue
                }
                check(matchesOwnedRow(event, destination.id, marker) &&
                    find(destination.id, prefix).singleOrNull() == event)
                if (fields[4] == state.generation && !state.paused &&
                    token !in state.leftPending && token !in state.values("remove_pending")) {
                    val current = exact(key)
                    if (current?.version == expectedVersion && current.draft.status.active) activateManaged(key, expectedVersion)
                }
                state.finishInsert(token, old.copy(mode = "managed", marker = marker).encoded, old.encoded)
                continue
            }
            val prior = mappings().singleOrNull {
                it.identity == identity && it.key == key && it.offset == offset && it.marker == marker
            }
            val proven = verifiedExportEvent(destination.id, marker, prior?.let { row(it.eventId) },
                find(destination.id, prefix), pendingInsert = true)
            val mapping = proven?.let { Mapping(identity, it.id, key, offset, parts[3], marker) }
            state.finishInsert(token, mapping?.encoded, prior?.encoded)
        }
    }

    private fun export(work: CalendarWork, settings: CalendarIntegrationSettings, types: List<CalendarWorkType>,
        destination: Destination, mode: String) {
        val type = types.firstOrNull { it.code == work.draft.typeCode }?.label ?: context.getString(when (work.draft.typeCode) {
            "MEASURE" -> R.string.p7b_measure
            "INSTALL" -> R.string.p7b_install
            "DELIVER" -> R.string.p7b_deliver
            else -> R.string.p7b_other
        })
        for (part in calendarExportParts(work)) {
            val prefix = "libellaro://work/${work.key.deviceKey()}/${part.dayOffset}/"
            val marker = "$prefix$mode/${state.calendarOwner}"
            val old = mappings().singleOrNull { it.identity == destination.identity &&
                it.key == work.key && it.offset == part.dayOffset && it.mode == mode && it.encoded !in state.leaving }
            val oldRow = old?.let { row(it.eventId) }
            val pending = "${destination.identity}|$marker"
            val pendingInserts = state.pendingInserts
            val owned = verifiedExportEvent(destination.id, marker, oldRow, find(destination.id, prefix),
                pending in pendingInserts)
            val values = eventValues(work, part, settings.exportFields, type)
            val id = if (owned != null) {
                val known = Mapping(destination.identity, owned.id, work.key, part.dayOffset, mode, marker)
                check(known.encoded !in state.leaving)
                if (mode == "managed" && !matchesValues(owned.id, values)) {
                    check(requireDestination(destination.identity).id == destination.id)
                    val args = ownership(known)
                    check(resolver.update(Events.CONTENT_URI, values, OWNED, args) == 1)
                }
                owned.id
            } else {
                values.put(Events.CALENDAR_ID, destination.id)
                values.put(Events.CUSTOM_APP_PACKAGE, context.packageName)
                values.put(Events.CUSTOM_APP_URI, marker)
                check(requireDestination(destination.identity).id == destination.id)
                state.save("pending_inserts", pendingInserts + pending)
                val operations = arrayListOf(
                    ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build(),
                    ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                        .withSelection("${Events.CALENDAR_ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI}=?",
                            arrayOf(destination.id.toString(), context.packageName, marker))
                        .withExpectedCount(1).build(),
                )
                val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
                ContentUris.parseId(checkNotNull(results[0].uri))
            }
            val mapping = Mapping(destination.identity, id, work.key, part.dayOffset, mode, marker)
            state.finishInsert(pending, mapping.encoded, old?.encoded)
        }
    }

    private fun eventValues(work: CalendarWork, part: CalendarExportPart, fields: Set<CalendarExportField>, type: String) =
        ContentValues().apply {
            put(Events.TITLE, listOfNotNull(type, work.projectLabel.takeIf { CalendarExportField.PROJECT in fields }).joinToString(" · "))
            put(Events.DTSTART, part.interval.start.toEpochMilliseconds())
            put(Events.DTEND, part.interval.endExclusive.toEpochMilliseconds())
            put(Events.EVENT_TIMEZONE, if (part.allDay) "UTC" else work.draft.schedule.zone.id)
            put(Events.EVENT_END_TIMEZONE, if (part.allDay) "UTC" else work.draft.schedule.zone.id)
            put(Events.ALL_DAY, if (part.allDay) 1 else 0)
            put(Events.AVAILABILITY, if (part.free) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
            put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE)
            put(Events.STATUS, Events.STATUS_CONFIRMED)
            putNull(Events.RRULE)
            putNull(Events.RDATE)
            put(Events.EVENT_LOCATION, work.contact.address.takeIf { CalendarExportField.ADDRESS in fields })
            put(Events.DESCRIPTION, listOfNotNull(
                work.contact.name.takeIf { CalendarExportField.CONTACT_NAME in fields },
                work.contact.phone.takeIf { CalendarExportField.CONTACT_PHONE in fields },
                work.draft.notes.takeIf { CalendarExportField.NOTES in fields && it.isNotBlank() },
            ).joinToString("\n").ifEmpty { null })
        }

    private fun matchesValues(id: Long, expected: ContentValues): Boolean {
        val columns = expected.keySet().toTypedArray()
        return checkNotNull(resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), columns, null, null, null)).use {
            it.moveToFirst() && columns.indices.all { index ->
                it.getString(index).orEmpty() == expected.getAsString(columns[index]).orEmpty()
            }
        }
    }

    private fun deleteOwned(mapping: Mapping) {
        val destination = requireDestination(mapping.identity)
        check(destination.id == mapping.calendarId && mapping.mode == "managed" && mapping.encoded !in state.leaving &&
            mapping.marker.endsWith("/${state.calendarOwner}"))
        val existing = row(mapping.eventId) ?: return
        check(existing.calendarId == destination.id && existing.marker == mapping.marker)
        if (existing.deleted) return
        val result = resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
            ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                .withSelection(OWNED, ownership(mapping)).withExpectedCount(1).build(),
            ContentProviderOperation.newDelete(ContentUris.withAppendedId(Events.CONTENT_URI, mapping.eventId))
                .withSelection(OWNED, ownership(mapping)).build(),
        ))
        val deleted = checkNotNull(result[1].count)
        val after = row(mapping.eventId)
        // Some providers retain a tombstone. Verify the actual outcome, not only the count.
        check(deleted in 0..1 && (after == null ||
            after.deleted && matchesOwnedRow(after, destination.id, mapping.marker)))
    }

    private fun ownership(mapping: Mapping) =
        arrayOf(mapping.eventId.toString(), mapping.calendarId.toString(), context.packageName, mapping.marker)

    private fun row(id: Long): ProviderCalendarEvent? =
        rows(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null).singleOrNull()

    private fun find(calendar: Long, prefix: String): List<ProviderCalendarEvent> = rows(Events.CONTENT_URI,
        "${Events.CALENDAR_ID}=? AND ${Events.CUSTOM_APP_URI} LIKE ? AND ${Events.DELETED}=0",
        arrayOf(calendar.toString(), "$prefix%"))

    private fun rows(uri: Uri, where: String?, args: Array<String>?): List<ProviderCalendarEvent> =
        checkNotNull(resolver.query(uri, arrayOf(Events._ID, Events.CALENDAR_ID, Events.CUSTOM_APP_PACKAGE,
            Events.CUSTOM_APP_URI, Events.DELETED), where, args, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val marker = cursor.getString(3).takeIf { cursor.getString(2) == context.packageName }
                    add(ProviderCalendarEvent(cursor.getLong(0), cursor.getLong(1), marker, cursor.getInt(4) != 0))
                }
            }
        }

    private fun isOwnedManagedRow(mapping: Mapping): Boolean {
        val row = row(mapping.eventId) ?: return false
        return !row.deleted && row.calendarId == mapping.calendarId && row.marker == mapping.marker &&
            mapping.marker.endsWith("/${state.calendarOwner}")
    }

    private data class Mapping(val identity: String, val eventId: Long, val key: WorkOccurrenceKey,
        val offset: Int, val mode: String, val marker: String) {
        val calendarId = identity.substringBefore(':').toLong()
        val encoded get() = listOf(identity, eventId, key.deviceKey(), offset, mode, marker).joinToString("|")
    }

    private fun mappings() = state.exports.map { value ->
        val fields = value.split('|')
        Mapping(fields[0], fields[1].toLong(), checkNotNull(parseWorkDeviceKey(fields[2])),
            fields[3].toInt(), fields[4], fields[5])
    }

    private fun forget(mapping: Mapping) { state.save("exports", state.exports - mapping.encoded) }

    private fun requireDestination(identity: String?): Destination {
        check(canWrite)
        return calendars().single { it.identity == identity && it.writable }
    }

    private fun enqueueLiveStore() {
        if (isolatedStore == null) enqueue(context)
    }

    private fun LocalDate.shiftBy(days: Int) = LocalDate.fromEpochDays(toEpochDays() + days)
    private fun LocalDate.nextDay() = shiftBy(1)

    companion object {
        private const val OWNED = "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.CUSTOM_APP_URI}=?"
        private val operations = Mutex()
        private val _revision = MutableStateFlow(0L)
        val revision = _revision.asStateFlow()
        private val _problems = MutableStateFlow<Set<WorkOccurrenceKey>>(emptySet())
        val problems = _problems.asStateFlow()
        private val _failed = MutableStateFlow(false)
        val failed = _failed.asStateFlow()

        fun invalidate() { _revision.update { it + 1 } }

        fun enqueue(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork("calendar-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CalendarSystemSyncWorker>().build())
            manager.enqueueUniquePeriodicWork("calendar-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CalendarSystemSyncWorker>(1, TimeUnit.HOURS).build())
        }

        fun reportFailure(failed: Boolean) { _failed.value = failed }
    }
}

class CalendarSystemSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (applicationContext.getSystemService(UserManager::class.java).isUserUnlocked)
            AndroidSystemCalendar(applicationContext).reconcile()
        AndroidSystemCalendar.reportFailure(false)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AndroidSystemCalendar.reportFailure(true)
        Result.retry()
    }
}
