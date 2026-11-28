package com.andreyceilings.app.calendar

internal fun exactReminderWakeups(triggerMillis: Iterable<Long>, limit: Int): List<Long> =
    triggerMillis.toSortedSet().take(limit)

// A small delivery grace covers ordinary exact-alarm dispatch jitter, not recovery of old alarms.
internal const val EXACT_DELIVERY_GRACE_MS = 120_000L

internal fun reminderIsDue(now: Long, trigger: Long, scheduled: Boolean, relevant: Boolean, workStarted: Boolean): Boolean =
    trigger <= now && relevant && (!workStarted || scheduled && now - trigger <= EXACT_DELIVERY_GRACE_MS)

internal fun <T> reminderOverflow(due: List<T>, alreadyVisible: Set<T>, available: Int): Pair<List<T>, List<T>> {
    var slots = available.coerceAtLeast(0)
    return due.partition { key ->
        if (key in alreadyVisible) true else if (slots > 0) { slots--; true } else false
    }
}

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
