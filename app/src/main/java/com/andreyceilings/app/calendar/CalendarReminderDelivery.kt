package com.andreyceilings.app.calendar

internal fun exactReminderWakeups(triggerMillis: Iterable<Long>, limit: Int): List<Long> =
    triggerMillis.toSortedSet().take(limit)

internal fun reminderIsDue(now: Long, trigger: Long, scheduled: Boolean, relevant: Boolean, workStarted: Boolean): Boolean =
    trigger <= now && relevant && (scheduled || !workStarted)

internal enum class VisibleReminderChange { KEEP, CANCEL, REPLACE, DISMISSED }

internal fun visibleReminderChange(active: Boolean, current: Boolean, samePayload: Boolean): VisibleReminderChange =
    when {
        !active -> VisibleReminderChange.DISMISSED
        !current -> VisibleReminderChange.CANCEL
        !samePayload -> VisibleReminderChange.REPLACE
        else -> VisibleReminderChange.KEEP
    }

internal fun activeReminderAction(allowed: Boolean, recorded: String?, active: String?, requested: String): Boolean =
    allowed && recorded == requested && active == requested
