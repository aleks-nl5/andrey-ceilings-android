package com.andreyceilings.shared.calendar

import app.cash.sqldelight.coroutines.asFlow
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

sealed interface CalendarRead<out T> {
    data object Loading : CalendarRead<Nothing>
    data class Ready<T>(val value: T) : CalendarRead<T>
    data class Unavailable<T>(val previous: T? = null) : CalendarRead<T>
}

data class CalendarFormOptions(
    val settings: CalendarSettings, val types: List<CalendarWorkType>,
    val projects: List<CalendarOption>, val clients: List<CalendarOption>,
)
data class CalendarOption(val uuid: String, val name: String)

class CalendarStore(
    private val database: PrototypeDatabase,
    private val clock: Clock = Clock.System,
    private val physicalLease: ((() -> Unit) -> Unit)? = null,
) {
    private val repository = CalendarRepository(database, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admitted = Mutex()
    private val closed = MutableStateFlow(false)
    private val changes = MutableStateFlow(0L)
    private val periods = MutableStateFlow<Map<PeriodKey, CachedPeriod>>(emptyMap())
    private val upcomingRefresh = MutableStateFlow(0L)
    private val upcomingCache = MutableStateFlow<Map<UpcomingKey, CachedUpcoming>>(emptyMap())
    private val optionsCache = MutableStateFlow<CalendarFormOptions?>(null)
    private data class UpcomingKey(val since: LocalDate, val projectUuid: String?, val limit: Int, val typeCode: String?)
    private data class CachedUpcoming(val revision: Long, val refresh: Long, val works: List<CalendarWork>,
        val validUntil: kotlin.time.Instant?)
    private data class PeriodKey(val range: WorkDateRange, val zone: TimeZone)
    private data class CachedPeriod(val revision: Long, val works: List<CalendarWork>)

    init {
        scope.launch {
            database.workCalendarQueries.observeCalendar().asFlow().catch { changes.update { it + 1 } }.collect {
                changes.update { it + 1 }
            }
        }
    }

    fun cached(range: WorkDateRange, zone: TimeZone): List<CalendarWork>? = periods.value[PeriodKey(range, zone)]?.works

    fun period(range: WorkDateRange, zone: TimeZone): Flow<CalendarRead<List<CalendarWork>>> = observe(cached(range, zone)) {
        fill(PeriodKey(range, zone), changes.value)
    }

    fun prefetch(range: WorkDateRange, zone: TimeZone) {
        scope.launch {
            try { access { fill(PeriodKey(range, zone), changes.value) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Foreground read exposes failures independently. */ }
        }
    }

    private fun fill(key: PeriodKey, revision: Long): List<CalendarWork> {
        periods.value[key]?.takeIf { it.revision == revision }?.let { return it.works }
        val result = repository.period(key.range, key.zone)
        periods.update { current ->
            (current.toList().filter { it.first != key }.takeLast(7) + (key to CachedPeriod(revision, result))).toMap()
        }
        return result
    }

    fun cachedOptions(): CalendarFormOptions? = optionsCache.value.takeUnless { closed.value }

    fun options(): Flow<CalendarRead<CalendarFormOptions>> = observe(cachedOptions()) {
        database.transactionWithResult {
            CalendarFormOptions(repository.settings(), repository.types(),
                database.businessSchemaQueries.selectProjects().executeAsList().map { CalendarOption(it.uuid, it.title) },
                database.businessSchemaQueries.selectClients().executeAsList().map { CalendarOption(it.uuid, it.name) })
        }.also { optionsCache.value = it }
    }

    fun cachedUpcoming(since: LocalDate, projectUuid: String? = null, limit: Int = 4,
        typeCode: String? = null): List<CalendarWork>? =
        upcomingCache.value[UpcomingKey(since, projectUuid, limit, typeCode)]?.works.takeUnless { closed.value }

    fun upcoming(since: LocalDate, projectUuid: String? = null, limit: Int = 4,
        typeCode: String? = null): Flow<CalendarRead<List<CalendarWork>>> {
        val key = UpcomingKey(since, projectUuid, limit, typeCode)
        return observe(cachedUpcoming(since, projectUuid, limit, typeCode),
            combine(changes, upcomingRefresh) { revision, refresh -> revision to refresh }) {
            val revision = changes.value
            val refresh = upcomingRefresh.value
            val now = clock.now()
            upcomingCache.value[key]?.takeIf {
                it.revision == revision && it.refresh == refresh &&
                    it.works.all { work -> work.upcoming(now) } &&
                    it.validUntil?.let { end -> end > now } != false
            }?.works ?: repository.upcoming(since, projectUuid, limit, typeCode).also { works ->
                val validUntil = works.mapNotNull { it.nextBoundary(now) }.minOrNull()
                upcomingCache.update { current ->
                    (current.toList().filter { it.first != key }.takeLast(15) +
                        (key to CachedUpcoming(revision, refresh, works, validUntil))).toMap()
                }
            }
        }
    }

    // Only the expiration of an actual displayed interval, not a periodic UI tick,
    // triggers a fresh upcoming projection.
    fun refreshUpcoming() { if (!closed.value) upcomingRefresh.update { it + 1 } }

    fun activeInstallation(projectUuid: String): Flow<CalendarRead<Boolean>> =
        observe(trigger = combine(changes, upcomingRefresh) { revision, refresh -> revision to refresh }) {
            repository.hasActiveInstallation(projectUuid)
        }

    fun search(text: String, horizonDays: Int): Flow<CalendarRead<List<CalendarWork>>> = observe {
        repository.search(text, horizonDays)
    }

    suspend fun linkedContact(projectUuid: String?, clientUuid: String?): WorkContact = access {
        database.transactionWithResult {
            val project = projectUuid?.let { database.workCalendarQueries.selectCalendarDirectProject(it).executeAsOneOrNull() }
            val client = if (clientUuid != null) {
                database.workCalendarQueries.selectCalendarDirectClient(clientUuid).executeAsOneOrNull()
            } else {
                project?.clientId?.let { database.businessSchemaQueries.selectClientById(it).executeAsOneOrNull() }
            }
            WorkContact(project?.address, client?.name, client?.phone, client?.email)
        }
    }

    suspend fun ceilings(projectUuid: String): List<WorkCeiling> = access {
        val project = database.workCalendarQueries.selectCalendarDirectProject(projectUuid).executeAsOneOrNull()
        if (project == null) emptyList() else database.businessSchemaQueries.selectMeasurementsForProject(project.id)
            .executeAsList().map { WorkCeiling(it.uuid, it.name) }
    }

    suspend fun previewConflicts(command: CalendarSave) = access { repository.previewConflicts(command) }
    suspend fun exact(key: WorkOccurrenceKey) = access { repository.exact(key) }
    suspend fun save(command: CalendarSave, confirmation: CalendarConflictConfirmation? = null) =
        access { repository.save(command, confirmation) }
    suspend fun delete(key: WorkOccurrenceKey, version: Long, scope: CalendarEditScope) =
        access { repository.delete(key, version, scope) }
    suspend fun cancelFollowing(key: WorkOccurrenceKey, version: Long) = access { repository.cancelFollowing(key, version) }
    suspend fun saveSettings(settings: CalendarSettings) = access { repository.saveSettings(settings) }
    suspend fun saveType(type: CalendarWorkType) = access { repository.saveType(type) }

    private fun <T> observe(previous: T? = null, trigger: Flow<*> = changes, read: () -> T): Flow<CalendarRead<T>> {
        var last = previous
        return trigger.map {
            try {
                val value = access(read)
                last = value
                CalendarRead.Ready(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalendarRead.Unavailable(last.takeUnless { closed.value })
            }
        }
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.Default) {
        admitted.withLock {
            check(!closed.value)
            val lease = physicalLease
            if (lease == null) block() else {
                var result: Result<T>? = null
                lease {
                    check(!closed.value)
                    result = runCatching(block)
                }
                checkNotNull(result).getOrThrow()
            }
        }
    }

    /** Android calls this with the exclusive physical restore gate held. */
    fun close() {
        closed.value = true
        scope.cancel()
        periods.value = emptyMap()
        upcomingCache.value = emptyMap()
        optionsCache.value = null
        changes.update { it + 1 }
    }

    suspend fun closeAndAwait() {
        close()
        admitted.withLock {
            periods.value = emptyMap()
            upcomingCache.value = emptyMap()
            optionsCache.value = null
        }
    }
}
