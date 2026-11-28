package com.andreyceilings.shared.calendar

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant
import kotlin.time.Duration.Companion.nanoseconds

data class WorkContact(val address: String? = null, val name: String? = null, val phone: String? = null, val email: String? = null) {
    internal fun over(inherited: WorkContact) = WorkContact(
        address ?: inherited.address, name ?: inherited.name,
        phone ?: inherited.phone, email ?: inherited.email
    )
}

data class WorkCeiling(val uuid: String, val label: String, val removed: Boolean = false)

data class CalendarWorkDraft(
    val date: LocalDate,
    val schedule: WorkSchedule,
    val title: String,
    val typeCode: String,
    val status: WorkStatus = WorkStatus.PLANNED,
    val notes: String = "",
    val projectUuid: String? = null,
    val clientUuid: String? = null,
    val contactOverrides: WorkContact = WorkContact(),
    val ceilings: List<WorkCeiling> = emptyList(),
    val explicitCeilings: Boolean = ceilings.isNotEmpty(),
    val recurrence: WorkRecurrence? = null,
)

data class CalendarWork(
    val key: WorkOccurrenceKey,
    val version: Long,
    val draft: CalendarWorkDraft,
    val projectLabel: String?,
    val contact: WorkContact,
) {
    val days: List<ScheduledWorkDay> by lazy { draft.schedule.on(draft.date) }

    fun upcoming(now: Instant): Boolean = draft.status.active && days.any { day ->
        day.interval?.let { it.endExclusive > now } ?: (day.date >= now.toLocalDateTime(draft.schedule.zone).date)
    }

    private fun nextDay(now: Instant): ScheduledWorkDay? = days.firstOrNull { day ->
        day.interval?.let { it.endExclusive > now } ?: (day.date >= now.toLocalDateTime(draft.schedule.zone).date)
    }

    internal fun nextStart(now: Instant): Instant? =
        nextDay(now)?.let { it.interval?.start ?: it.date.atStartOfDayIn(draft.schedule.zone) }

    internal fun nextBoundary(now: Instant): Instant? =
        nextDay(now)?.let { it.interval?.endExclusive ?: it.date.shift(1).atStartOfDayIn(draft.schedule.zone) }

    fun displayDates(zone: TimeZone): Set<LocalDate> = buildSet {
        days.forEach { day ->
            val interval = day.interval
            if (interval == null || draft.schedule.mode != WorkTimeMode.TIMED) add(day.date)
            else {
                add(interval.start.toLocalDateTime(zone).date)
                add((interval.endExclusive - 1.nanoseconds).toLocalDateTime(zone).date)
            }
        }
    }
}

data class CalendarSettings(val zone: TimeZone, val firstWeekday: DayOfWeek?, val workingDays: Set<DayOfWeek>)

data class CalendarWorkType(
    val code: String, val label: String?, val color: Int, val icon: String,
    val durationMinutes: Int, val archived: Boolean,
)

enum class CalendarEditScope { OCCURRENCE, THIS_AND_FOLLOWING }

data class CalendarSave(
    val draft: CalendarWorkDraft,
    val target: WorkOccurrenceKey? = null,
    val expectedVersion: Long? = null,
    val scope: CalendarEditScope = CalendarEditScope.OCCURRENCE,
    val replaceRecurrence: Boolean = false,
    val integration: CalendarWorkIntegration? = null,
)

data class CalendarConflictPreview(val count: Int, val checkedThrough: LocalDate?)

data class CalendarConflict(
    val proposed: WorkOccurrenceKey,
    val other: WorkOccurrenceKey,
    val proposedInterval: WorkInterval,
    val otherInterval: WorkInterval,
)

class CalendarConflictConfirmation internal constructor(
    val command: CalendarSave,
    val conflicts: Set<CalendarConflict>,
) {
    internal fun matches(other: CalendarConflictConfirmation): Boolean =
        command == other.command && conflicts == other.conflicts
}

sealed interface CalendarSaveResult {
    data class Saved(val key: WorkOccurrenceKey, val detached: Int = 0, val installationCompleted: Boolean = false) : CalendarSaveResult
    data class Conflicts(val confirmation: CalendarConflictConfirmation, val checkedThrough: LocalDate?) : CalendarSaveResult
    data object Stale : CalendarSaveResult
}

internal fun Set<DayOfWeek>.mask(): Long = sumOf { 1L shl (it.isoDayNumber - 1) }
internal fun Long.weekdays(): Set<DayOfWeek> = DayOfWeek.entries.filterTo(mutableSetOf()) {
    this and (1L shl (it.isoDayNumber - 1)) != 0L
}


@ConsistentCopyVisibility
data class CalendarDeletionImpact internal constructor(
    internal val kind: String, internal val entityUuid: String, internal val workVersions: Map<String, Long>,
) {
    val affectedRecords: Int get() = workVersions.size
}

class CalendarDeletionConfirmationRequired(val impact: CalendarDeletionImpact) : IllegalStateException()
