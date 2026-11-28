package com.andreyceilings.shared.calendar

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

data class CalendarDestinationChoice(val identity: String, val label: String, val writable: Boolean)

data class CalendarExportConfirmation(val destination: String, val eventIds: Set<Long>)

sealed interface CalendarManualExportResult {
    data object Exported : CalendarManualExportResult
    data class ConfirmAdoption(val confirmation: CalendarExportConfirmation) : CalendarManualExportResult
}

enum class CalendarSyncFailure { ACCESS, DESTINATION, DELETE, EXPORT, OTHER }

@OptIn(ExperimentalMaterial3Api::class)
val LocalCalendarPickerFactory = compositionLocalOf<@Composable (Long?) -> DatePickerState> {
    { initial -> rememberDatePickerState(initialSelectedDateMillis = initial) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberCalendarPickerState(initialSelectedDateMillis: Long?): DatePickerState =
    LocalCalendarPickerFactory.current(initialSelectedDateMillis)

val LocalCalendarDialogChrome = compositionLocalOf<@Composable (Boolean) -> Unit> { {} }

interface CalendarIntegrationPlatform {
    val revision: Long
    val problems: Set<WorkOccurrenceKey>
    val failed: Boolean
    val failureReason: CalendarSyncFailure? get() = null
    val retrying: Boolean get() = false
    val exactAlarmsGranted: Boolean get() = remindersReady
    fun requestExactAlarms() = requestReminders()
    val restorePending: Boolean
    val remindersReady: Boolean
    val readCalendarGranted: Boolean
    val writeCalendarGranted: Boolean
    val busySelection: Set<String>
    val destination: String?
    fun requestReminders()
    fun requestCalendarAccess(write: Boolean)
    fun retry()
    suspend fun remindersChanged()
    suspend fun choices(): List<CalendarDestinationChoice>
    suspend fun selectBusy(identities: Set<String>)
    suspend fun selectDestination(identity: String, move: Boolean)
    suspend fun createDestination(): String
    suspend fun confirmRestore()
    fun reactivateSyncRule(rule: CalendarSyncRule)
    suspend fun enableSync()
    suspend fun disableSync(remove: Boolean)
    suspend fun manualExport(key: WorkOccurrenceKey, version: Long, managed: Boolean,
        confirmation: CalendarExportConfirmation? = null): CalendarManualExportResult
    suspend fun snooze(key: WorkOccurrenceKey, at: Instant): Boolean
    fun cachedAvailability(range: WorkDateRange, zone: TimeZone): CalendarAvailability?
    suspend fun refreshAvailability(range: WorkDateRange, zone: TimeZone): CalendarAvailability
    suspend fun availability(range: WorkDateRange, zone: TimeZone): CalendarAvailability
}

val LocalCalendarIntegrationPlatform = compositionLocalOf<CalendarIntegrationPlatform?> { null }

internal suspend fun externalConflictSnapshot(
    platform: CalendarIntegrationPlatform?, store: CalendarStore, command: CalendarSave,
): CalendarAvailability {
    if (platform == null || platform.busySelection.isEmpty() || !command.draft.status.active) return CalendarAvailability.Disabled
    val zone = command.draft.schedule.zone
    val intervals = store.externalProposal(command)
    if (intervals.isEmpty()) {
        val date = command.draft.date
        return when (platform.availability(WorkDateRange(date, date), zone)) {
            CalendarAvailability.Unknown -> CalendarAvailability.Unknown
            else -> CalendarAvailability.Available(emptySet())
        }
    }
    val ranges = intervals.map {
        WorkDateRange(it.start.toLocalDateTime(zone).date, (it.endExclusive - 1.nanoseconds).toLocalDateTime(zone).date)
    }.distinct().sortedBy { it.from }.fold(mutableListOf<WorkDateRange>()) { merged, range ->
        val last = merged.lastOrNull()
        if (last == null || range.from > last.through.shift(1)) merged += range
        else merged[merged.lastIndex] = WorkDateRange(last.from, maxOf(last.through, range.through))
        merged
    }
    val busy = mutableSetOf<ExternalBusyInterval>()
    for (range in ranges) when (val result = platform.availability(range, zone)) {
        CalendarAvailability.Disabled, CalendarAvailability.Unknown -> return CalendarAvailability.Unknown
        is CalendarAvailability.Available -> result.busy.filterTo(busy) { external ->
            intervals.any(external.interval::overlaps)
        }
    }
    return CalendarAvailability.Available(busy)
}

internal fun CalendarAvailability.requiresConfirmation() =
    this is CalendarAvailability.Unknown || this is CalendarAvailability.Available && busy.isNotEmpty()
