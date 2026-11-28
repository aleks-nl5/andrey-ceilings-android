package com.andreyceilings.shared.calendar

import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.datetime.LocalDate

internal class CalendarIntegrationRepository(private val database: PrototypeDatabase) {
    private val queries = database.calendarIntegrationsQueries

    fun snapshot(): CalendarIntegrationSnapshot = database.transactionWithResult {
        val settings = queries.integrationSettings().executeAsOne()
        val typeRules = queries.typeReminderRules().executeAsList().groupBy { it.typeCode }
        val workRules = queries.workReminderRules().executeAsList().groupBy { Triple(it.rootUuid, it.originalDay, it.following) }
        val types = queries.typeIntegrations().executeAsList().associate {
            it.typeCode to CalendarTypeIntegration(
                typeRules[it.typeCode].orEmpty().mapTo(mutableSetOf()) { rule ->
                    ReminderRule(ReminderDay.valueOf(rule.dayKind), rule.dayLead.toInt(), rule.minute.toInt())
                }.takeIf { _ -> it.customReminders == 1L },
                it.syncEnabled == 1L,
            )
        }
        val occurrences = mutableMapOf<WorkOccurrenceKey, CalendarWorkIntegration>()
        val series = mutableMapOf<WorkOccurrenceKey, CalendarWorkIntegration>()
        for (row in queries.workIntegrations().executeAsList()) {
            val rules = workRules[Triple(row.rootUuid, row.originalDay, row.following)].orEmpty().mapTo(mutableSetOf()) {
                ReminderRule(ReminderDay.valueOf(it.dayKind), it.dayLead.toInt(), it.minute.toInt(), it.dayOffset.toInt())
            }
            val key = WorkOccurrenceKey(row.rootUuid, LocalDate.fromEpochDays(row.originalDay))
            val value = CalendarWorkIntegration(row.reminderMode?.let(ReminderMode::valueOf), rules,
                row.syncMode?.let(CalendarSyncMode::valueOf))
            (if (row.following == 1L) series else occurrences)[key] = value
        }
        CalendarIntegrationSnapshot(
            CalendarIntegrationSettings(settings.remindersEnabled == 1L, settings.privateLockscreen == 1L,
                settings.syncEnabled == 1L, CalendarExportField.entries.filterTo(mutableSetOf()) {
                    settings.exportFields and (1L shl it.ordinal) != 0L
                }),
            types, occurrences, series,
        )
    }

    fun save(settings: CalendarIntegrationSettings) = queries.saveIntegrationSettings(
        settings.remindersEnabled.flag(), settings.privateLockscreen.flag(), settings.syncEnabled.flag(),
        settings.exportFields.sumOf { 1L shl it.ordinal },
    )

    fun saveType(code: String, defaults: CalendarTypeIntegration) = database.transaction {
        require(defaults.reminders.orEmpty().none { it.day == ReminderDay.DAY })
        queries.saveTypeIntegration(code, (defaults.reminders != null).flag(), defaults.syncEnabled.flag())
        queries.clearTypeReminderRules(code)
        for (rule in defaults.reminders.orEmpty()) {
            queries.insertTypeReminderRule(code, rule.day.name, rule.leadDays.toLong(), rule.minute.toLong())
        }
    }

    fun saveWork(key: WorkOccurrenceKey, scope: CalendarEditScope, value: CalendarWorkIntegration) = database.transaction {
        val current = snapshot()
        val following = scope == CalendarEditScope.THIS_AND_FOLLOWING
        val previous = (if (following) current.series else current.occurrences)[key]
        val merged = CalendarWorkIntegration(
            value.reminderMode ?: previous?.reminderMode,
            if (value.reminderMode != null) value.reminders else previous?.reminders.orEmpty(),
            value.syncMode ?: previous?.syncMode,
        )
        writeWork(key, following, merged)
        if (following) current.occurrences[key]?.let { own ->
            writeWork(key, false, CalendarWorkIntegration(
                own.reminderMode.takeIf { value.reminderMode == null },
                own.reminders.takeIf { value.reminderMode == null }.orEmpty(),
                own.syncMode.takeIf { value.syncMode == null },
            ))
        }
    }

    private fun writeWork(key: WorkOccurrenceKey, following: Boolean, value: CalendarWorkIntegration) {
        val scope = following.flag()
        val day = key.originalDate.toEpochDays()
        queries.saveWorkIntegration(key.rootUuid, day, scope, value.reminderMode?.name, value.syncMode?.name)
        queries.clearWorkReminderRules(key.rootUuid, day, scope)
        for (rule in value.reminders) {
            queries.insertWorkReminderRule(key.rootUuid, day, scope, rule.day.name, rule.offset.toLong(),
                rule.leadDays.toLong(), rule.minute.toLong())
        }
    }

    private fun Boolean.flag() = if (this) 1L else 0L
}
