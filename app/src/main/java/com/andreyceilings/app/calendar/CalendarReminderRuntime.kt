package com.andreyceilings.app.calendar

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andreyceilings.app.MainActivity
import com.andreyceilings.app.R
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.ProductionDatabaseGate
import com.andreyceilings.shared.calendar.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val GROUP = "libellaro_work"
private const val GROUP_SUMMARY = "libellaro_work_summary"
private const val MAINTENANCE = "replenish"
private const val MAX_PENDING_WORK_ALARMS = 128
private const val ALARM = "com.andreyceilings.app.calendar.ALARM"
private const val SNOOZE = "com.andreyceilings.app.calendar.SNOOZE"

internal object CalendarReminderRuntime {
    private val operations = Mutex()

    fun enqueue(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("calendar-reminders-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CalendarReminderWorker>().build())
        manager.enqueueUniquePeriodicWork("calendar-reminders-maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarReminderWorker>(12, TimeUnit.HOURS).build())
    }

    suspend fun reconcile(context: Context) = operations.withLock {
        val state = CalendarDeviceState(context)
        val generation = state.generation
        if (state.paused || !state.remindersRequested || !canScheduleCalendarAlarms(context) ||
            !canPostCalendarNotifications(context)) {
            ProductionDatabaseGate.read { if (state.generation == generation) clear(context, state) }
            return@withLock
        }
        val now = Clock.System.now()
        val end = now + 7.days
        val zone = TimeZone.currentSystemDefault()
        val store = store(context)
        val plan = store.integrationPlan(now.toLocalDateTime(zone).date.shiftBackOne(),
            end.toLocalDateTime(zone).date, zone, includeLead = true)
        if (!plan.snapshot.settings.remindersEnabled) {
            ProductionDatabaseGate.read { if (state.generation == generation) clear(context, state) }
            return@withLock
        }
        val delivered = state.delivered
        val seenWorks = plan.works.associate { it.work.key to it.work }
        val desired = mutableSetOf<String>()
        val retainedDelivered = mutableSetOf<String>()
        val visibleOrSnoozed = (state.visible + state.snoozes).filter { id ->
            val key = decode(id)?.key ?: return@filter false
            val work = seenWorks[key] ?: store.exact(key)
            work?.draft?.status?.active == true && (id in state.snoozes && isSnoozeFor(id, work) ||
                id in state.visible && isAlertCurrent(id, work, plan.snapshot))
        }.toSet()
        val retainedSnoozes = state.snoozes.intersect(visibleOrSnoozed)
        desired += retainedSnoozes.filter { decode(it)!!.whenAt > now }
        val missed = mutableMapOf<WorkOccurrenceKey, Pair<String, CalendarWork>>()
        val missedIds = mutableSetOf<String>()
        for ((work, standalone) in plan.works) {
            for (alert in calendarReminders(work, plan.snapshot.rules(work))) {
                if (!standalone && alert.trigger > end) continue
                val id = encode(alert, work)
                if (id in delivered) {
                    if (work.upcoming(now)) retainedDelivered += id
                    continue
                }
                if (alert.trigger > now) desired += id
                else if (!work.hasStarted(now)) {
                    val previous = missed[work.key]
                    if (previous == null || decode(previous.first)!!.whenAt < alert.trigger) missed[work.key] = id to work
                    missedIds += id
                }
            }
        }
        for (id in retainedSnoozes) {
            val alarm = decode(id)!!
            if (alarm.whenAt <= now) {
                val work = seenWorks[alarm.key] ?: store.exact(alarm.key)
                if (work != null && !work.hasStarted(now)) {
                    missed[work.key] = id to work
                    missedIds += id
                }
            }
        }
        val armed = desired.sortedBy { decode(it)!!.whenAt }.take(MAX_PENDING_WORK_ALARMS).toSet() +
            encodeMaintenance(now + 1.days)
        ProductionDatabaseGate.read {
            if (!state.paused && state.generation == generation) {
                val notifications = context.getSystemService(NotificationManager::class.java)
                for (id in state.visible - visibleOrSnoozed) decode(id)?.let { notifications.cancel(it.key.deviceKey(), 1) }
                // Record the union before scheduling, so a failed or interrupted operation can be retried.
                state.save("alarms", state.alarmKeys + armed)
                (state.alarmKeys - armed).forEach { cancel(context, it, generation) }
                armed.forEach { schedule(context, it, generation) }
                state.save("alarms", armed)
                state.save("snoozes", retainedSnoozes.intersect(desired))
                val visible = visibleOrSnoozed.intersect(state.visible).toMutableSet()
                for ((id, work) in missed.values) {
                    post(context, state, work, id, plan.snapshot.settings, plan.types)
                    visible.removeAll { decode(it)?.key == work.key }
                    visible += id
                }
                updateSummary(context)
                state.save("visible", visible)
                state.save("delivered", retainedDelivered + state.visible.intersect(delivered) + missedIds)
            }
        }
    }

    suspend fun fired(context: Context, id: String, generation: String) = operations.withLock {
        val state = CalendarDeviceState(context)
        if (state.paused || state.generation != generation || id !in state.alarmKeys ||
            id in state.delivered || !canPostCalendarNotifications(context) || !canScheduleCalendarAlarms(context)) return@withLock
        val alarm = decode(id) ?: return@withLock
        val now = Clock.System.now()
        if (alarm.whenAt > now) return@withLock
        val store = store(context)
        val work = store.exact(alarm.key) ?: return@withLock
        val snapshot = store.integrationSnapshot()
        if (!snapshot.settings.remindersEnabled || !work.draft.status.active) return@withLock
        if (!isAlertCurrent(id, work, snapshot)) return@withLock
        if (work.days.none { it.interval?.endExclusive?.let { end -> now < end }
                ?: (it.date >= now.toLocalDateTime(work.draft.schedule.zone).date) }) return@withLock
        val types = store.integrationPlan(work.draft.date, work.draft.date, work.draft.schedule.zone, false).types
        ProductionDatabaseGate.read {
            if (!state.paused && state.generation == generation) {
                post(context, state, work, id, snapshot.settings, types)
                state.save("visible", state.visible.filterNot { decode(it)?.key == work.key }.toSet() + id)
                state.save("delivered", state.delivered + id)
                state.save("alarms", state.alarmKeys - id)
                state.save("snoozes", state.snoozes - id)
                updateSummary(context)
            }
        }
    }

    suspend fun snooze(context: Context, key: WorkOccurrenceKey, at: Instant, fromAlert: String? = null,
        generation: String = CalendarDeviceState(context).generation): Boolean = operations.withLock {
        val state = CalendarDeviceState(context)
        val now = Clock.System.now()
        if (at <= now || state.paused || !state.remindersRequested || state.generation != generation ||
            !canPostCalendarNotifications(context) || !canScheduleCalendarAlarms(context)) return@withLock false
        val store = store(context)
        val work = store.exact(key) ?: return@withLock false
        val snapshot = store.integrationSnapshot()
        if (!work.draft.status.active || !snapshot.settings.remindersEnabled ||
            fromAlert != null && (fromAlert !in state.visible || !isAlertCurrent(fromAlert, work, snapshot))) return@withLock false
        val id = "${key.deviceKey()}/-1/${at.toEpochMilliseconds()}/${work.version}"
        ProductionDatabaseGate.read {
            if (state.paused || state.generation != generation) return@read false
            val previous = state.snoozes.filter { decode(it)?.key == key }.toSet()
            state.save("snoozes", state.snoozes + id)
            state.save("alarms", state.alarmKeys + id)
            schedule(context, id, generation)
            previous.forEach { cancel(context, it, generation) }
            state.save("snoozes", state.snoozes - previous)
            state.save("alarms", state.alarmKeys - previous)
            context.getSystemService(NotificationManager::class.java).cancel(key.deviceKey(), 1)
            updateSummary(context)
            state.save("visible", state.visible.filterNot { decode(it)?.key == key }.toSet())
            true
        }
    }

    fun pauseForRestore(context: Context) {
        val state = CalendarDeviceState(context)
        val previous = state.generation
        state.pauseForRestore()
        clear(context, state, previous)
    }

    private fun clear(context: Context, state: CalendarDeviceState, generation: String = state.generation) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        state.alarmKeys.forEach { cancel(context, it, generation) }
        state.visible.forEach { id -> decode(id)?.let { notifications.cancel(it.key.deviceKey(), 1) } }
        notifications.cancel(GROUP_SUMMARY, 1)
        state.save("alarms", emptySet())
        state.save("visible", emptySet())
        state.save("snoozes", emptySet())
    }

    private fun schedule(context: Context, id: String, generation: String) {
        val trigger = if (id.startsWith("$MAINTENANCE/")) id.substringAfter('/').toLong()
            else checkNotNull(decode(id)).whenAt.toEpochMilliseconds()
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger,
            checkNotNull(broadcast(context, ALARM, id, generation, PendingIntent.FLAG_UPDATE_CURRENT)))
    }

    private fun cancel(context: Context, id: String, generation: String) {
        broadcast(context, ALARM, id, generation, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    private fun post(context: Context, state: CalendarDeviceState, work: CalendarWork, id: String,
        settings: CalendarIntegrationSettings, types: List<CalendarWorkType>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CALENDAR_REMINDER_CHANNEL, context.getString(R.string.p7b_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
        val day = work.days.zip(work.draft.schedule.days).firstOrNull { it.second.offset == decode(id)?.offset }
            ?: work.days.zip(work.draft.schedule.days).first()
        val dateTime = listOfNotNull(day.first.date.toString(), day.second.start?.toString()?.take(5),
            day.second.end?.toString()?.take(5)).joinToString(" ")
        val type = types.firstOrNull { it.code == work.draft.typeCode }?.label ?: context.getString(when (work.draft.typeCode) {
            "MEASURE" -> R.string.p7b_measure
            "INSTALL" -> R.string.p7b_install
            "DELIVER" -> R.string.p7b_deliver
            else -> R.string.p7b_other
        })
        val key = work.key.deviceKey()
        val public = builder(context, type, dateTime).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        val notification = builder(context, work.draft.title.ifBlank { type }, dateTime)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(
                dateTime, work.projectLabel, work.contact.address, work.contact.name, work.contact.phone,
            ).joinToString("\n")))
            .setVisibility(if (settings.privateLockscreen) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .setContentIntent(activity(context, id, state.generation, "open"))
            .addAction(action(context, R.string.p7b_snooze, broadcast(context, SNOOZE, id, state.generation, PendingIntent.FLAG_UPDATE_CURRENT)))
        if (!work.contact.address.isNullOrBlank()) notification.addAction(
            action(context, R.string.p7b_navigate, activity(context, id, state.generation, "navigate")))
        if (!work.contact.phone.isNullOrBlank()) {
            notification.addAction(action(context, R.string.p7b_dial, activity(context, id, state.generation, "dial")))
            if (work.contact.address.isNullOrBlank())
                notification.addAction(action(context, R.string.p7b_message, activity(context, id, state.generation, "message")))
        }
        manager.notify(key, 1, notification.build())
    }

    private fun updateSummary(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val count = manager.activeNotifications.count { it.tag != GROUP_SUMMARY && it.notification.group == GROUP }
        if (count < 2) manager.cancel(GROUP_SUMMARY, 1)
        else manager.notify(GROUP_SUMMARY, 1, builder(context, "Libellaro", context.getString(R.string.p7b_notification_channel))
            .setGroupSummary(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
    }

    private fun builder(context: Context, title: String, text: String) = NotificationCompat.Builder(context, CALENDAR_REMINDER_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text)
        .setGroup(GROUP).setAutoCancel(false).setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun action(context: Context, label: Int, pending: PendingIntent?): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(label), pending)
            .setAuthenticationRequired(true).build()

    private fun activity(context: Context, id: String, generation: String, action: String): PendingIntent {
        return PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setAction("com.andreyceilings.app.calendar.OPEN").setData(payload(id, generation, action))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    suspend fun open(context: Context, intent: Intent): WorkOccurrenceKey? {
        if (intent.action != "com.andreyceilings.app.calendar.OPEN") return null
        val (id, generation, action) = intent.data?.pathSegments?.takeIf { it.size == 3 } ?: return null
        val state = CalendarDeviceState(context)
        if (state.paused || generation != state.generation || id !in state.visible) return null
        val key = decode(id)?.key ?: return null
        val store = store(context)
        val work = store.exact(key)?.takeIf { it.draft.status.active } ?: return null
        val snapshot = store.integrationSnapshot()
        if (!snapshot.settings.remindersEnabled || !isAlertCurrent(id, work, snapshot)) return null
        if (action == "open") return key
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return null
        val external = when (action) {
            "navigate" -> work.contact.address?.let { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(it)}")) }
            "dial" -> work.contact.phone?.let { Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null)) }
            "message" -> work.contact.phone?.let { Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", it, null)) }
            else -> null
        }
        if (external != null) runCatching { context.startActivity(external.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return null
    }

    private fun isAlertCurrent(id: String, work: CalendarWork, policy: CalendarIntegrationSnapshot) =
        isSnoozeFor(id, work) || calendarReminders(work, policy.rules(work)).any { encode(it, work) == id }

    private fun isSnoozeFor(id: String, work: CalendarWork) =
        decode(id)?.let { it.offset == -1 && it.version == work.version } == true

    private data class Alarm(val key: WorkOccurrenceKey, val offset: Int, val whenAt: Instant, val version: Long?)

    private fun encode(alert: PlannedReminder, work: CalendarWork): String {
        val schedule = "${work.draft.date}|${work.draft.schedule}|${work.draft.typeCode}"
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(schedule.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        return "${alert.key.deviceKey()}/${alert.dayOffset}/${alert.trigger.toEpochMilliseconds()}/$fingerprint"
    }

    private fun encodeMaintenance(at: Instant) = "$MAINTENANCE/${at.toEpochMilliseconds()}"

    private fun decode(id: String): Alarm? = runCatching {
        val parts = id.split('/')
        val key = checkNotNull(parseWorkDeviceKey(parts.take(2).joinToString("/")))
        Alarm(key, parts[2].toInt(), Instant.fromEpochMilliseconds(parts[3].toLong()), parts.getOrNull(4)?.toLongOrNull())
    }.getOrNull()

    private fun payload(id: String, generation: String, action: String) =
        Uri.Builder().scheme("libellaro").authority("reminder").appendPath(id).appendPath(generation.ifEmpty { "initial" })
            .appendPath(action).build()

    private fun broadcast(context: Context, action: String, id: String, generation: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, Intent(context, CalendarReminderReceiver::class.java)
            .setAction(action).setData(payload(id, generation, "broadcast")), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun store(context: Context): CalendarStore = CeilingsDatabase.get(context).objectsClientsStore.calendar
    private fun kotlinx.datetime.LocalDate.shiftBackOne() =
        kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() - 1)

    suspend fun onBroadcast(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments?.takeIf { it.size == 3 } ?: return
        val (id, generation) = parts
        if (intent.action == ALARM && id.startsWith("$MAINTENANCE/")) enqueue(context)
        else if (intent.action == ALARM) {
            fired(context, id, generation)
            enqueue(context)
        }
        else if (intent.action == SNOOZE) {
            val key = decode(id)?.key ?: return
            snooze(context, key, Clock.System.now() + 15.minutes, id, generation)
        }
    }
}

class CalendarReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (applicationContext.getSystemService(UserManager::class.java).isUserUnlocked)
            CalendarReminderRuntime.reconcile(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.getSystemService(UserManager::class.java).isUserUnlocked) {
                    when (intent.action) {
                        ALARM, SNOOZE -> CalendarReminderRuntime.onBroadcast(context.applicationContext, intent)
                        else -> {
                            CalendarReminderRuntime.enqueue(context)
                            AndroidSystemCalendar.enqueue(context)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalendarReminderRuntime.enqueue(context)
            } finally {
                pending.finish()
            }
        }
    }
}
