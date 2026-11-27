package com.andreyceilings.shared.calendar

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class ReminderDay { FIRST, LATER, DATE, DAY }
enum class ReminderMode { INHERIT, ADD, REPLACE, OFF }
enum class CalendarSyncMode { INHERIT, ON, OFF }
enum class CalendarExportField { PROJECT, ADDRESS, CONTACT_NAME, CONTACT_PHONE, NOTES }

data class ReminderRule(
    val day: ReminderDay,
    val leadDays: Int,
    val minute: Int,
    val offset: Int = -1,
) {
    init {
        require(leadDays in 0..365 && minute in 0..1439)
        require(if (day == ReminderDay.DAY) offset in 0..365 else offset == -1)
    }
}

data class CalendarIntegrationSettings(
    val remindersEnabled: Boolean = true,
    val privateLockscreen: Boolean = true,
    val syncEnabled: Boolean = false,
    val exportFields: Set<CalendarExportField> = emptySet(),
)

data class CalendarTypeIntegration(val reminders: Set<ReminderRule>? = null, val syncEnabled: Boolean = false)

data class CalendarWorkIntegration(
    val reminderMode: ReminderMode? = null,
    val reminders: Set<ReminderRule> = emptySet(),
    val syncMode: CalendarSyncMode? = null,
) {
    init { require(reminders.isEmpty() || reminderMode == ReminderMode.ADD || reminderMode == ReminderMode.REPLACE) }
}

data class CalendarIntegrationSnapshot(
    val settings: CalendarIntegrationSettings,
    val types: Map<String, CalendarTypeIntegration>,
    val occurrences: Map<WorkOccurrenceKey, CalendarWorkIntegration>,
    val series: Map<WorkOccurrenceKey, CalendarWorkIntegration>,
) {
    fun overrides(key: WorkOccurrenceKey): CalendarWorkIntegration {
        val preceding = series.asSequence().filter {
            it.key.rootUuid == key.rootUuid && it.key.originalDate <= key.originalDate
        }.sortedByDescending { it.key.originalDate }.map { it.value }
        val ordered = listOfNotNull(occurrences[key]).asSequence() + preceding
        val reminder = ordered.firstOrNull { it.reminderMode != null }
        return CalendarWorkIntegration(reminder?.reminderMode, reminder?.reminders.orEmpty(),
            ordered.firstNotNullOfOrNull { it.syncMode })
    }

    fun rules(work: CalendarWork): Set<ReminderRule> {
        val defaults = types[work.draft.typeCode]?.reminders ?: standardReminderRules
        val override = overrides(work.key)
        return when (override.reminderMode) {
            ReminderMode.ADD -> defaults + override.reminders
            ReminderMode.REPLACE -> override.reminders
            ReminderMode.OFF -> emptySet()
            ReminderMode.INHERIT, null -> defaults
        }
    }

    fun managed(work: CalendarWork): Boolean = when (overrides(work.key).syncMode) {
        CalendarSyncMode.ON -> true
        CalendarSyncMode.OFF -> false
        CalendarSyncMode.INHERIT, null -> settings.syncEnabled && types[work.draft.typeCode]?.syncEnabled == true
    }

    val maximumLeadDays: Int
        get() = (standardReminderRules.asSequence() +
            types.values.asSequence().flatMap { it.reminders.orEmpty() } +
            occurrences.values.asSequence().flatMap { it.reminders } +
            series.values.asSequence().flatMap { it.reminders })
            .maxOf { it.leadDays } + 1
}

val standardReminderRules = setOf(
    ReminderRule(ReminderDay.FIRST, 1, 0),
    ReminderRule(ReminderDay.FIRST, 0, 60),
    ReminderRule(ReminderDay.LATER, 0, 60),
    ReminderRule(ReminderDay.DATE, 1, 18 * 60),
    ReminderRule(ReminderDay.DATE, 0, 8 * 60),
)

data class CalendarIntegrationWork(val work: CalendarWork, val standalone: Boolean)
data class CalendarIntegrationPlan(
    val snapshot: CalendarIntegrationSnapshot,
    val works: List<CalendarIntegrationWork>,
    val types: List<CalendarWorkType>,
)

data class PlannedReminder(val key: WorkOccurrenceKey, val dayOffset: Int, val trigger: Instant)

fun calendarReminders(work: CalendarWork, rules: Set<ReminderRule>): Set<PlannedReminder> {
    if (!work.draft.status.active) return emptySet()
    val schedule = work.draft.schedule
    return buildSet {
        work.days.zip(schedule.days).forEach { (day, spec) ->
            val timed = schedule.mode == WorkTimeMode.TIMED
            val kind = when {
                !timed -> ReminderDay.DATE
                spec.offset == 0 -> ReminderDay.FIRST
                else -> ReminderDay.LATER
            }
            val applicable = rules.filter { it.day == kind || it.day == ReminderDay.DAY && it.offset == spec.offset }
            for (rule in applicable) {
                val date = day.date.shift(-rule.leadDays)
                val trigger = if (timed) {
                    // Resolve DST through the same work schedule policy, then subtract the minute lead.
                    LocalDateTime(date, checkNotNull(spec.start)).toInstant(schedule.zone) - rule.minute.minutes
                } else {
                    val instant = LocalDateTime(date, LocalTime(rule.minute / 60, rule.minute % 60)).toInstant(schedule.zone)
                    if (date.atStartOfDayIn(schedule.zone) == date.shift(1).atStartOfDayIn(schedule.zone)) continue
                    instant
                }
                add(PlannedReminder(work.key, spec.offset, trigger))
            }
        }
    }
}

fun CalendarWork.hasStarted(now: Instant): Boolean {
    val first = days.first()
    return now >= (first.interval?.start ?: first.date.atStartOfDayIn(draft.schedule.zone))
}

data class CalendarExportPart(val dayOffset: Int, val interval: WorkInterval, val allDay: Boolean, val free: Boolean)

fun calendarExportParts(work: CalendarWork): List<CalendarExportPart> = when (work.draft.schedule.mode) {
    WorkTimeMode.TIMED -> work.days.zip(work.draft.schedule.days).mapNotNull { (day, spec) ->
        day.interval?.let { CalendarExportPart(spec.offset, it, allDay = false, free = false) }
    }
    WorkTimeMode.ALL_DAY, WorkTimeMode.DATE_ONLY -> listOf(CalendarExportPart(
        0, WorkInterval(work.days.first().date.atStartOfDayIn(TimeZone.UTC),
            work.days.last().date.shift(1).atStartOfDayIn(TimeZone.UTC)),
        allDay = true, free = work.draft.schedule.mode == WorkTimeMode.DATE_ONLY,
    ))
}

data class ExternalBusyInterval(val interval: WorkInterval, val allDay: Boolean)

sealed interface CalendarAvailability {
    data object Disabled : CalendarAvailability
    data object Unknown : CalendarAvailability
    data class Available(val busy: Set<ExternalBusyInterval>) : CalendarAvailability
}
