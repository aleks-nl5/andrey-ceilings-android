package com.andreyceilings.shared.calendar

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

enum class WorkFrequency { DAILY, WEEKLY, MONTHLY, CUSTOM }

data class WorkDateRange(val from: LocalDate, val through: LocalDate) {
    init { require(from <= through) }
    operator fun contains(date: LocalDate): Boolean = date >= from && date <= through
}

data class WorkRecurrence(
    val anchor: LocalDate,
    val frequency: WorkFrequency,
    val step: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
    val monthDay: Int = anchor.day,
) {
    init {
        require(step in 1..366)
        require((frequency == WorkFrequency.CUSTOM) == weekdays.isNotEmpty())
        require(frequency != WorkFrequency.CUSTOM || anchor.dayOfWeek in weekdays)
        require(until == null || until >= anchor)
        require(count == null || count > 0)
        require(until == null || count == null)
        require(monthDay in 1..31)
    }

    internal fun ordinal(date: LocalDate): Int {
        val elapsed = (date.toEpochDays() - anchor.toEpochDays()).toInt()
        return when (frequency) {
            WorkFrequency.DAILY -> elapsed / step
            WorkFrequency.WEEKLY -> elapsed / (7 * step)
            WorkFrequency.MONTHLY -> ((date.year - anchor.year) * 12 + date.monthNumber - anchor.monthNumber) / step
            WorkFrequency.CUSTOM -> {
                val anchorOffset = anchor.dayOfWeek.isoDayNumber - 1
                val cycleIndex = (elapsed + anchorOffset) / (7 * step)
                cycleIndex * weekdays.size + weekdays.count { it.isoDayNumber < date.dayOfWeek.isoDayNumber } -
                    weekdays.count { it.isoDayNumber < anchor.dayOfWeek.isoDayNumber }
            }
        }
    }

    /** Seeks into the requested window arithmetically, including for distant open-ended rules. */
    fun occurrences(range: WorkDateRange): Sequence<LocalDate> = sequence {
        val from = maxOf(anchor, range.from)
        val through = minOf(range.through, until ?: range.through)
        if (from > through) return@sequence
        when (frequency) {
            WorkFrequency.DAILY -> yieldAll(fixedDays(from, through, step))
            WorkFrequency.WEEKLY -> yieldAll(fixedDays(from, through, step * 7))
            WorkFrequency.MONTHLY -> {
                val anchorMonth = LocalDate(anchor.year, anchor.monthNumber, 1)
                val monthDelta = (from.year - anchor.year) * 12 + from.monthNumber - anchor.monthNumber
                var index = monthDelta / step
                var month = anchorMonth.plus(DatePeriod(months = index * step))
                while (month <= through && (count == null || index < count)) {
                    val nextMonth = month.plus(DatePeriod(months = 1))
                    val lastDay = nextMonth.minus(DatePeriod(days = 1)).day
                    val date = LocalDate(month.year, month.monthNumber, minOf(monthDay, lastDay))
                    if (date >= from && date <= through) yield(date)
                    index++
                    month = month.plus(DatePeriod(months = step))
                }
            }
            WorkFrequency.CUSTOM -> {
                val slots = weekdays.map { it.isoDayNumber - 1 }.sorted()
                val anchorOffset = anchor.dayOfWeek.isoDayNumber - 1
                val firstWeek = anchor.minus(DatePeriod(days = anchorOffset))
                val cycle = step * 7
                val skippedSlots = slots.count { it < anchorOffset }
                var index = ((from.toEpochDays() - firstWeek.toEpochDays()) / cycle).toInt()
                var week = firstWeek.plus(DatePeriod(days = index * cycle))
                while (week <= through) {
                    for ((slot, offset) in slots.withIndex()) {
                        val ordinal = index.toLong() * slots.size + slot - skippedSlots
                        if (count != null && ordinal >= count) return@sequence
                        val date = week.plus(DatePeriod(days = offset))
                        if (date >= from && date <= through) yield(date)
                    }
                    index++
                    week = week.plus(DatePeriod(days = cycle))
                }
            }
        }
    }

    private fun fixedDays(from: LocalDate, through: LocalDate, stride: Int): Sequence<LocalDate> = sequence {
        val elapsed = from.toEpochDays() - anchor.toEpochDays()
        var index = ((elapsed + stride - 1) / stride).toInt()
        var date = anchor.plus(DatePeriod(days = index * stride))
        while (date <= through && (count == null || index < count)) {
            yield(date)
            index++
            date = date.plus(DatePeriod(days = stride))
        }
    }
}

data class WorkOccurrenceKey(val rootUuid: String, val originalDate: LocalDate)
