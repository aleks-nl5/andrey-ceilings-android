package com.andreyceilings.app.calendar

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.andreyceilings.shared.calendar.WorkOccurrenceKey
import kotlinx.datetime.LocalDate
import java.util.UUID

internal class CalendarDeviceState(context: Context, fileName: String = "calendar_integration_device") {
    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    val generation: String get() = synchronized(lock) {
        prefs.getString("generation", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("generation", it).commit())
        }
    }
    val paused: Boolean get() = prefs.getBoolean("restore_pending", false)
    val remindersRequested: Boolean get() = prefs.getBoolean("reminders_requested", false)
    val hasBatchedSchedule get() = prefs.contains("exact_wakeups")
    val alarmKeys: Set<String> get() = values("alarms")
    val delivered: Set<String> get() = values("delivered")
    val visible: Set<String> get() = values("visible")
    val snoozes: Set<String> get() = values("snoozes")
    val busyCalendars: Set<String> get() = values("busy_calendars")
    val destination: String? get() = prefs.getString("destination", null)
    val previousDestination: String? get() = prefs.getString("previous_destination", null)
    val leaving: Set<String> get() = values("left_events")
    val exports: Set<String> get() = values("exports")
    val calendarOwner: String get() = synchronized(lock) {
        prefs.getString("owner", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("owner", it).commit())
        }
    }

    fun pauseForRestore() = synchronized(lock) {
        check(prefs.edit().putString("generation", UUID.randomUUID().toString())
            .putBoolean("restore_pending", true).remove("snoozes").remove("delivered")
            .remove("sync_transition").commit())
    }

    fun resumeAfterRestore() = synchronized(lock) {
        check(prefs.edit().putBoolean("restore_pending", false).commit())
    }

    fun requestReminders() { check(prefs.edit().putBoolean("reminders_requested", true).commit()) }

    fun values(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    fun save(key: String, values: Set<String>) { check(prefs.edit().putStringSet(key, values.toSet()).commit()) }

    fun recordDelivery(visible: Set<String>, delivered: Set<String>) {
        check(prefs.edit().putStringSet("visible", visible).putStringSet("delivered", delivered).commit())
    }

    fun recordSchedule(alerts: Set<String>, wakeups: Set<String>) {
        check(prefs.edit().putStringSet("alarms", alerts).putStringSet("exact_wakeups", wakeups).commit())
    }

    fun changeDestination(identity: String, move: Boolean) {
        val current = destination
        if (identity == current) return
        val edit = prefs.edit().putString("destination", identity)
        if (move && current != null) {
            edit.putString("previous_destination", current)
        } else {
            val previous = listOfNotNull(current, previousDestination).toSet()
            edit.remove("previous_destination").putStringSet("left_events", leaving + exports.filter {
                it.substringBefore('|') in previous && it !in values("remove_queue")
            }).putStringSet("leave_pending", leftPending + pendingInserts.filter {
                it.substringBefore('|') in previous && it !in values("remove_pending")
            })
        }
        check(edit.commit())
    }

    val suspendedRules get() = values("suspended_rules")

    fun replaceSuspendedRules(rules: Set<String>) {
        check(prefs.edit().putStringSet("suspended_rules", rules)
            .remove("sync_suspended").remove("manual_after_disable").remove("activated_legacy_rules").commit())
    }

    fun activateRule(token: String) = synchronized(lock) {
        if (values("sync_suspended").isNotEmpty()) {
            save("activated_legacy_rules", values("activated_legacy_rules") + token)
        } else {
            val suspended = suspendedRules
            if (token in suspended) replaceSuspendedRules(suspended - token)
        }
    }

    val syncTransition get() = prefs.getString("sync_transition", null)?.let(SyncTransition::valueOf)

    fun beginSyncEnable() {
        check(prefs.edit().putString("sync_transition", SyncTransition.ENABLE.name).commit())
    }

    fun finishSyncTransition(transition: SyncTransition) {
        val edit = prefs.edit().remove("sync_transition")
        if (transition == SyncTransition.ENABLE) {
            edit.remove("suspended_rules").remove("sync_suspended")
                .remove("manual_after_disable").remove("activated_legacy_rules")
        }
        check(edit.commit())
    }

    fun suspendSync(managed: Set<String>, rules: Set<String>, remove: Boolean) {
        val pending = pendingInserts.filterTo(mutableSetOf()) { "/managed/" in it }
        val edit = prefs.edit().putStringSet("suspended_rules", rules)
            .putString("sync_transition", SyncTransition.DISABLE.name)
            .remove("sync_suspended").remove("manual_after_disable").remove("activated_legacy_rules")
        if (remove) edit.putStringSet("remove_queue", values("remove_queue") + managed)
            .putStringSet("remove_pending", values("remove_pending") + pending)
        else edit.putStringSet("left_events", leaving + managed)
            .putStringSet("leave_pending", values("leave_pending") + (pending - values("remove_pending")))
        check(edit.commit())
    }

    val leftPending get() = values("leave_pending")
    val pendingInserts get() = values("pending_inserts")

    fun finishInsert(token: String, mapping: String?, replacedMapping: String? = null) {
        val leave = values("leave_pending")
        val remove = values("remove_pending")
        val edit = prefs.edit().putStringSet("pending_inserts", pendingInserts - token)
            .putStringSet("leave_pending", leave - token).putStringSet("remove_pending", remove - token)
        if (mapping != null) {
            edit.putStringSet("exports", exports - listOfNotNull(replacedMapping).toSet() + mapping)
            if (token in remove) edit.putStringSet("remove_queue", values("remove_queue") + mapping)
            else if (token in leave) edit.putStringSet("left_events", leaving + mapping)
        }
        check(edit.commit())
    }

    fun finishMove() { check(prefs.edit().remove("previous_destination").commit()) }

    companion object {
        private val lock = Any()
    }
}

internal enum class SyncTransition(val enabled: Boolean) { ENABLE(true), DISABLE(false) }

internal fun canScheduleCalendarAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

internal const val CALENDAR_REMINDER_CHANNEL = "calendar_work_reminders"

internal fun canPostCalendarNotifications(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).let { notifications ->
        notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CALENDAR_REMINDER_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

internal fun WorkOccurrenceKey.deviceKey() = "$rootUuid/$originalDate"

internal fun parseWorkDeviceKey(value: String): WorkOccurrenceKey? {
    val parts = value.split('/')
    if (parts.size != 2) return null
    return runCatching {
        WorkOccurrenceKey(UUID.fromString(parts[0]).toString(), LocalDate.parse(parts[1]))
    }.getOrNull()
}
