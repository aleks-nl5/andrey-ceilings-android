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

    fun options(): Flow<CalendarRead<CalendarFormOptions>> = observe {
        database.transactionWithResult {
            CalendarFormOptions(repository.settings(), repository.types(),
                database.businessSchemaQueries.selectProjects().executeAsList().map { CalendarOption(it.uuid, it.title) },
                database.businessSchemaQueries.selectClients().executeAsList().map { CalendarOption(it.uuid, it.name) })
        }
    }

    fun upcoming(since: LocalDate, projectUuid: String? = null, limit: Int = 4): Flow<CalendarRead<List<CalendarWork>>> = observe {
        repository.upcoming(since, projectUuid, limit)
    }

    fun activeInstallation(projectUuid: String): Flow<CalendarRead<Boolean>> = observe {
        database.workCalendarQueries.hasActiveCalendarInstallation(projectUuid).executeAsOne()
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

    private fun <T> observe(previous: T? = null, read: () -> T): Flow<CalendarRead<T>> {
        var last = previous
        return changes.map {
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
        changes.update { it + 1 }
    }

    suspend fun closeAndAwait() {
        close()
        admitted.withLock { periods.value = emptyMap() }
    }
}
