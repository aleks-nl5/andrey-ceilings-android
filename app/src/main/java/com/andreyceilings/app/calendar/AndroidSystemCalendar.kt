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
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.ProductionDatabaseGate
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
            requireDestination(identity)
            check(!move || state.previousDestination == null)
            state.changeDestination(identity, move)
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
        withContext(Dispatchers.IO) {
            val selected = state.busyCalendars
            if (selected.isEmpty()) return@withContext CalendarAvailability.Disabled
            if (state.paused || !canRead) return@withContext CalendarAvailability.Unknown
            try {
                val ids = calendars().filter { it.identity in selected }.map { it.id }
                if (ids.size != selected.size) return@withContext CalendarAvailability.Unknown
                val from = range.from.atStartOfDayIn(zone).toEpochMilliseconds()
                val end = range.through.nextDay().atStartOfDayIn(zone).toEpochMilliseconds()
                val uri = Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, from - TimeUnit.DAYS.toMillis(1))
                    ContentUris.appendId(it, end + TimeUnit.DAYS.toMillis(1))
                }.build()
                val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_ID,
                    Instances.AVAILABILITY, Instances.STATUS, Instances.CALENDAR_ID)
                val rows = checkNotNull(resolver.query(uri, projection,
                    "${Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }})",
                    ids.map(Long::toString).toTypedArray(), null)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            if (cursor.isNull(4)) return@withContext CalendarAvailability.Unknown
                            if (cursor.getInt(4) == Events.AVAILABILITY_FREE || cursor.getInt(5) == Events.STATUS_CANCELED) continue
                            val eventId = cursor.getLong(3)
                            if (isOwnedManagedRow(eventId, cursor.getLong(6))) continue
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
        }

    suspend fun confirmRestore() = operations.withLock {
        withContext(Dispatchers.IO) {
            val known = state.busyCalendars + listOfNotNull(state.destination, state.previousDestination)
            if (known.isNotEmpty()) {
                check(canRead && calendars().map { it.identity }.containsAll(known))
                state.destination?.let { requireDestination(it) }
                state.previousDestination?.let { requireDestination(it) }
                for (mapping in mappings().filter { it.mode == "managed" && it.encoded !in state.leaving }) {
                    val row = row(mapping.eventId)
                    check(row == null || row.calendarId == mapping.calendarId && row.marker == mapping.marker)
                }
            }
            state.resumeAfterRestore()
            invalidate()
            CalendarReminderRuntime.enqueue(context)
            enqueue(context)
        }
    }

    suspend fun manualExport(key: WorkOccurrenceKey, version: Long, managed: Boolean) = operations.withLock {
        withContext(Dispatchers.IO) {
            val generation = state.generation
            check(!state.paused)
            val destination = requireDestination(state.destination)
            val store = store()
            val work = checkNotNull(store.exact(key))
            check(work.version == version && work.draft.status.active)
            val plan = store.integrationPlan(work.draft.date, work.draft.date, work.draft.schedule.zone, false)
            if (managed) {
                check(store.setManagedExport(key, version))
                state.save("manual_after_disable", state.values("manual_after_disable") + key.deviceKey())
            }
            ProductionDatabaseGate.read {
                check(generation == state.generation && !state.paused)
                export(work, plan, destination, if (managed) "managed" else "once")
            }
        }
    }

    suspend fun disableSync(remove: Boolean) = operations.withLock {
        withContext(Dispatchers.IO) {
            val store = store()
            val settings = store.integrationSnapshot().settings
            val managed = mappings().filter { it.mode == "managed" && it.encoded !in state.leaving }
            state.suspendSync(managed.mapTo(mutableSetOf()) { it.encoded }, remove)
            store.saveIntegrationSettings(settings.copy(syncEnabled = false))
            enqueueLiveStore()
        }
    }

    suspend fun enableSync() = operations.withLock {
        withContext(Dispatchers.IO) {
            check(!state.paused)
            requireDestination(state.destination)
            val store = store()
            store.saveIntegrationSettings(store.integrationSnapshot().settings.copy(syncEnabled = true))
            state.save("sync_suspended", emptySet())
            enqueue(context)
        }
    }

    suspend fun reconcile() = operations.withLock {
        withContext(Dispatchers.IO) {
            if (state.paused || state.destination == null) return@withContext
            val generation = state.generation
            val store = store()
            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()
            val today = now.toLocalDateTime(zone).date
            val plan = store.integrationPlan(today, today.shiftBy(89), zone, false)
            val allMappings = mappings()
            val leftKeys = allMappings.filter { it.mode == "managed" && it.encoded in state.leaving }.mapTo(mutableSetOf()) { it.key }
            val activeKeys = allMappings.filter { it.mode == "managed" && it.encoded !in state.leaving }.mapTo(mutableSetOf()) { it.key }
            val managed = plan.works.filter { (work, standalone) ->
                work.draft.status.active && (standalone || work.upcoming(now)) && plan.snapshot.managed(work) &&
                    (work.key !in leftKeys || work.key in activeKeys)
            }.associate { it.work.key to it.work }
            val known = allMappings.filter { it.mode == "managed" && it.encoded !in state.leaving }
            val exact = known.map { it.key }.distinct().associateWith { managed[it] ?: store.exact(it) }
            ProductionDatabaseGate.read {
                if (state.paused || generation != state.generation) return@read
                val destination: Destination
                val previous: Destination?
                try {
                    destination = requireDestination(state.destination)
                    previous = state.previousDestination?.let { requireDestination(it) }
                } catch (failure: Exception) {
                    _problems.value = managed.keys + known.map { it.key }
                    throw failure
                }
                val remove = state.values("remove_queue")
                for (mapping in known.filter { it.encoded in remove }) {
                    if (exact[mapping.key]?.draft?.status == WorkStatus.COMPLETED) {
                        state.save("left_events", state.leaving + mapping.encoded)
                    } else {
                        deleteOwned(mapping)
                        forget(mapping)
                    }
                    state.save("remove_queue", state.values("remove_queue") - mapping.encoded)
                }
                val manual = state.values("manual_after_disable")
                val suspended = state.values("sync_suspended").isNotEmpty()
                val eligible = if (suspended) managed.filterKeys { it.deviceKey() in manual } else managed
                val failures = mutableSetOf<WorkOccurrenceKey>()
                for (work in eligible.values) {
                    try {
                        export(work, plan, destination, "managed")
                    } catch (_: Exception) {
                        failures += work.key
                    }
                }
                for (mapping in known.filter { it.encoded !in remove && it.key !in failures &&
                    (!suspended || it.key.deviceKey() in manual) }) {
                    val work = exact[mapping.key]
                    when {
                        work?.draft?.status == WorkStatus.COMPLETED ||
                            work?.draft?.status?.active == true && !plan.snapshot.managed(work) ->
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

    private fun export(work: CalendarWork, plan: CalendarIntegrationPlan, destination: Destination, mode: String) {
        val type = plan.types.firstOrNull { it.code == work.draft.typeCode }?.label ?: context.getString(when (work.draft.typeCode) {
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
            val pendingInserts = state.values("pending_inserts")
            val owned = verifiedExportEvent(destination.id, marker, oldRow, find(destination.id, prefix),
                pending in pendingInserts)
            val values = eventValues(work, part, plan.snapshot.settings.exportFields, type)
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
            if (old != null && old.eventId != id) forget(old)
            state.save("exports", state.exports + mapping.encoded)
            if (pending in state.values("pending_inserts")) {
                state.save("pending_inserts", state.values("pending_inserts") - pending)
            }
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
        check(resolver.delete(Events.CONTENT_URI, OWNED, ownership(mapping)) == 1)
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

    private fun isOwnedManagedRow(id: Long, calendar: Long): Boolean {
        val mapping = mappings().firstOrNull { it.eventId == id && it.calendarId == calendar &&
            it.mode == "managed" && it.encoded !in state.leaving } ?: return false
        val row = row(id) ?: return false
        return !row.deleted && row.marker == mapping.marker && mapping.marker.endsWith("/${state.calendarOwner}")
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

    private fun store() = isolatedStore ?: CeilingsDatabase.get(context).objectsClientsStore.calendar

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
