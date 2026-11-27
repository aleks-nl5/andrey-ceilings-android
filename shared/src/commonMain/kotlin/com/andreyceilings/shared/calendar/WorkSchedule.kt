package com.andreyceilings.shared.calendar

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class WorkStatus {
    PLANNED, CONFIRMED, COMPLETED, CANCELLED;

    val active: Boolean get() = this == PLANNED || this == CONFIRMED
}

enum class WorkTimeMode { TIMED, ALL_DAY, DATE_ONLY }

data class WorkDay(val offset: Int, val start: LocalTime? = null, val end: LocalTime? = null) {
    init {
        require(offset in 0..365)
        require((start == null && end == null) || (start != null && end != null && start < end))
    }
}

data class WorkSchedule(val mode: WorkTimeMode, val zone: TimeZone, val days: List<WorkDay>) {
    init {
        require(days.isNotEmpty() && days.first().offset == 0)
        require(days.zipWithNext().all { (left, right) -> left.offset < right.offset })
        require(days.all { (it.start != null) == (mode == WorkTimeMode.TIMED) })
    }

    fun on(date: LocalDate): List<ScheduledWorkDay> = days.map { day ->
        val localDate = date.plus(DatePeriod(days = day.offset))
        when (mode) {
            WorkTimeMode.DATE_ONLY -> ScheduledWorkDay(localDate, null)
            WorkTimeMode.ALL_DAY -> {
                val start = localDate.atStartOfDayIn(zone)
                val end = localDate.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
                // A zone can skip an entire calendar date.
                ScheduledWorkDay(localDate, if (start < end) WorkInterval(start, end) else null, start == end)
            }
            WorkTimeMode.TIMED -> {
                val localStart = LocalDateTime(localDate, checkNotNull(day.start))
                val localEnd = LocalDateTime(localDate, checkNotNull(day.end))
                val start = localStart.toInstant(zone)
                val resolvedEnd = localEnd.toInstant(zone)
                // Preserve a positive duration if a gap moves the start past
                // its requested end. A start inside a gap shifts by its size.
                val end = if (start < resolvedEnd) resolvedEnd
                    else start + (localEnd.time.toSecondOfDay() - localStart.time.toSecondOfDay()).seconds
                ScheduledWorkDay(localDate, WorkInterval(start, end),
                    changedByZone(localStart, start) || changedByZone(localEnd, resolvedEnd))
            }
        }
    }

    private fun changedByZone(local: LocalDateTime, instant: Instant): Boolean {
        if (instant.toLocalDateTime(zone) != local) return true
        val offset = zone.offsetAt(instant)
        val nextOffset = zone.offsetAt(instant + 36.hours)
        val repeated = offset.totalSeconds - nextOffset.totalSeconds
        return repeated > 0 && (instant + repeated.seconds).toLocalDateTime(zone) == local
    }
}

data class ScheduledWorkDay(val date: LocalDate, val interval: WorkInterval?, val shiftedByZone: Boolean = false)

data class WorkInterval(val start: Instant, val endExclusive: Instant) {
    init { require(start < endExclusive) }

    fun overlaps(other: WorkInterval): Boolean = start < other.endExclusive && other.start < endExclusive
}
