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
            .putBoolean("restore_pending", true).remove("snoozes").remove("delivered").commit())
    }

    fun resumeAfterRestore() = synchronized(lock) {
        check(prefs.edit().putBoolean("restore_pending", false).commit())
    }

    fun requestReminders() { check(prefs.edit().putBoolean("reminders_requested", true).commit()) }

    fun values(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()

    fun save(key: String, values: Set<String>) { check(prefs.edit().putStringSet(key, values.toSet()).commit()) }

    fun changeDestination(identity: String, move: Boolean) {
        if (identity == destination) return
        val edit = prefs.edit().putString("destination", identity)
        if (move && destination != null) edit.putString("previous_destination", destination)
        else edit.remove("previous_destination").putStringSet("left_events", leaving + exports.filter {
            it.startsWith("${destination}|") || previousDestination?.let { prior -> it.startsWith("$prior|") } == true
        })
        check(edit.commit())
    }

    fun suspendSync(managed: Set<String>, remove: Boolean) {
        val edit = prefs.edit().putStringSet("sync_suspended", setOf("yes"))
            .remove("manual_after_disable")
        if (remove) edit.putStringSet("remove_queue", values("remove_queue") + managed)
        else edit.putStringSet("left_events", leaving + managed)
        check(edit.commit())
    }

    fun finishMove() { check(prefs.edit().remove("previous_destination").commit()) }

    companion object {
        private val lock = Any()
    }
}

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
