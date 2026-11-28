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
import android.provider.Settings
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
private const val MAX_PENDING_WAKEUPS = 128
private const val MAX_ACTIVE_BEFORE_SUMMARY = 49
private const val WAKEUP = "wake"
private const val DISMISS = "com.andreyceilings.app.calendar.DISMISS"
private const val ALERT_EXTRA = "libellaro_alert"
private const val OVERFLOW_EXTRA = "libellaro_overflow"
private const val OVERFLOW = "overflow"
internal const val OPEN_REMINDER_CALENDAR = "com.andreyceilings.app.calendar.OPEN_REMINDER_CALENDAR"
private const val PAYLOAD_EXTRA = "libellaro_payload"
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

    suspend fun reconcile(context: Context, expectedGeneration: String? = null) = operations.withLock {
        val state = CalendarDeviceState(context)
        try {
            withCalendarEffects(context) {
                if (expectedGeneration != null && state.generation != expectedGeneration) return@withCalendarEffects
                if (!allowed(context, state)) {
                    clear(context, state)
                    return@withCalendarEffects
                }
                val now = Clock.System.now()
                val end = now + 7.days
                val zone = TimeZone.currentSystemDefault()
                val plan = plan(now.toLocalDateTime(zone).date.shiftBackOne(),
                    end.toLocalDateTime(zone).date, zone, includeLead = true)
                if (!plan.snapshot.settings.remindersEnabled) {
                    clear(context, state)
                    return@withCalendarEffects
                }
                val seen = plan.works.associate { it.work.key to it.work }
                val delivered = state.delivered
                val boot = bootCount(context)
                // An earlier boot still proves the alert was planned; it does not prove a timely dispatch.
                val scheduled = state.alarmKeys
                val sameBoot = boot >= 0 && state.scheduledBoot == boot
                val desired = mutableMapOf<String, Long>()
                val retained = mutableSetOf<String>()
                val retainedSnoozes = mutableSetOf<String>()
                val due = mutableMapOf<WorkOccurrenceKey, Pair<String, CalendarWork>>()
                val dueIds = mutableMapOf<WorkOccurrenceKey, MutableSet<String>>()
                fun consider(id: String, alarm: Alarm, work: CalendarWork, wasScheduled: Boolean) {
                    if (id in delivered) {
                        if (work.upcoming(now)) retained += id
                        return
                    }
                    if (alarm.whenAt > now) {
                        desired[id] = alarm.whenAt.toEpochMilliseconds()
                    } else if (reminderIsDue(now.toEpochMilliseconds(), alarm.whenAt.toEpochMilliseconds(),
                            wasScheduled, relevant(alarm, work, now), reminderDayStarted(alarm, work, now), sameBoot)) {
                        val prior = due[work.key]
                        if (prior == null || decode(prior.first)!!.whenAt <= alarm.whenAt) due[work.key] = id to work
                        dueIds.getOrPut(work.key) { mutableSetOf() } += id
                    }
                }
                for ((work, standalone) in plan.works) {
                    for (alert in calendarReminders(work, plan.snapshot.rules(work))) {
                        if (!standalone && alert.trigger > end) continue
                        val id = encode(alert, work)
                        consider(id, checkNotNull(decode(id)), work, id in scheduled)
                    }
                }
                for (id in state.snoozes) {
                    val alarm = decode(id) ?: continue
                    val work = seen[alarm.key] ?: exact(alarm.key) ?: continue
                    if (!work.draft.status.active || !isSnoozeFor(id, work)) continue
                    consider(id, alarm, work, wasScheduled = id in scheduled)
                    if (id in desired) retainedSnoozes += id
                }

                val manager = context.getSystemService(NotificationManager::class.java)
                val active = activeNotifications(manager)
                val visible = mutableSetOf<String>()
                for (id in state.visible) {
                    val alarm = decode(id) ?: continue
                    val tag = alarm.key.deviceKey()
                    val notification = active[tag]
                    val osAlert = notification?.extras?.getString(ALERT_EXTRA)
                    val work = seen[alarm.key] ?: exact(alarm.key)
                    val current = work != null && work.draft.status.active && isAlertCurrent(id, work, plan.snapshot)
                    val payload = if (current) contentSignature(state, checkNotNull(work), plan.snapshot.settings, plan.types) else null
                    when (visibleReminderChange(notification != null && (osAlert == id || osAlert == null),
                        current, osAlert == id && notification?.extras?.getString(PAYLOAD_EXTRA) == payload)) {
                        VisibleReminderChange.DISMISSED -> Unit
                        VisibleReminderChange.CANCEL -> manager.cancel(tag, 1)
                        VisibleReminderChange.KEEP -> visible += id
                        VisibleReminderChange.REPLACE -> {
                            // Recheck immediately before the replacement. A failed rebuild leaves no old public payload.
                            if (tag in activeNotifications(manager)) {
                                try {
                                    post(context, state, checkNotNull(work), id, plan.snapshot.settings, plan.types, quiet = true)
                                    visible += id
                                } catch (failure: Exception) {
                                    manager.cancel(tag, 1)
                                    throw failure
                                }
                            }
                        }
                    }
                }
                for ((tag, notification) in active) {
                    val osAlert = notification.extras.getString(ALERT_EXTRA)
                    if (osAlert != null && osAlert !in state.visible) manager.cancel(tag, 1)
                }
                val osNotifications = manager.activeNotifications
                val available = (MAX_ACTIVE_BEFORE_SUMMARY - osNotifications.count { it.tag != GROUP_SUMMARY }).coerceAtLeast(0)
                val activeTags = osNotifications.filter { it.id == 1 && it.notification.group == GROUP }
                    .mapTo(mutableSetOf()) { it.tag }
                val previousSummary = osNotifications.firstOrNull { it.tag == GROUP_SUMMARY && it.id == 1 }?.notification
                val overflow = mutableMapOf<WorkOccurrenceKey, Pair<String, CalendarWork>>()
                // A dismissed summary is not resurrected. Keep only live, still-relevant entries.
                if (state.overflow.isNotEmpty() &&
                    previousSummary?.extras?.getString(OVERFLOW_EXTRA) == overflowToken(state.overflow)) {
                    for (id in state.overflow) {
                        val alarm = decode(id) ?: continue
                        val work = seen[alarm.key] ?: exact(alarm.key) ?: continue
                        if (work.draft.status.active && relevant(alarm, work, now) &&
                            isAlertCurrent(id, work, plan.snapshot)) overflow[work.key] = id to work
                    }
                }
                val (individual, grouped) = reminderOverflow(due.keys.toList(),
                    due.keys.filterTo(mutableSetOf()) { it.deviceKey() in activeTags }, available)
                val sent = mutableSetOf<String>()
                var sounded = false
                for (key in individual) {
                    val (id, work) = due.getValue(key)
                    post(context, state, work, id, plan.snapshot.settings, plan.types, silent = sounded)
                    sounded = true
                    overflow.remove(key)
                    visible.removeAll { decode(it)?.key == key }
                    visible += id
                    sent += dueIds.getValue(key)
                    state.recordDelivery(visible, state.delivered + dueIds.getValue(key))
                }
                for (key in grouped) overflow[key] = due.getValue(key)
                val overflowIds = overflow.values.mapTo(mutableSetOf()) { it.first }
                // Present overflow immediately, not after another notification is dismissed.
                // The summary is the single reserved slot; it exposes no client details.
                if (overflow.isNotEmpty()) {
                    postOverflow(context, state, overflow.values.toList(), plan.types,
                        alert = grouped.isNotEmpty() && !sounded, hasChildren = visible.isNotEmpty())
                } else {
                    if (previousSummary?.extras?.getString(OVERFLOW_EXTRA) != null) manager.cancel(GROUP_SUMMARY, 1)
                    updateSummary(context, state, ignoreOverflow = true)
                }
                for (key in grouped) sent += dueIds.getValue(key)
                state.recordDelivery(visible, retained + delivered.intersect(visible + overflowIds) + sent, overflowIds)
                state.save("snoozes", retainedSnoozes)
                arm(context, state, desired, now)
            }
        } catch (failure: Exception) {
            // Missing/stale encrypted data must not leave older client details visible.
            ProductionDatabaseGate.calendarEffects { cancelVisible(context, state) }
            throw failure
        }
    }

    private fun allowed(context: Context, state: CalendarDeviceState) =
        !state.paused && state.remindersRequested && canPostCalendarNotifications(context) &&
            canScheduleCalendarAlarms(context)

    private fun relevant(alarm: Alarm, work: CalendarWork, now: Instant): Boolean {
        if (alarm.offset == -1) return work.upcoming(now)
        val index = work.draft.schedule.days.indexOfFirst { it.offset == alarm.offset }
        val day = work.days.getOrNull(index) ?: return false
        return day.interval?.let { now < it.endExclusive }
            ?: (day.date >= now.toLocalDateTime(work.draft.schedule.zone).date)
    }

    private fun reminderDayStarted(alarm: Alarm, work: CalendarWork, now: Instant): Boolean {
        val day = if (alarm.offset == -1) work.days.firstOrNull {
            it.interval?.let { interval -> now < interval.endExclusive }
                ?: (it.date >= now.toLocalDateTime(work.draft.schedule.zone).date)
        } else work.days.getOrNull(work.draft.schedule.days.indexOfFirst { it.offset == alarm.offset })
        if (day == null) return true
        // All-day/date-only work has no agreed clock start. The corresponding day remains
        // actionable until its end; timed multi-day work uses that specific day's start.
        return work.draft.schedule.mode == WorkTimeMode.TIMED &&
            day.interval?.let { now >= it.start } == true
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun arm(context: Context, state: CalendarDeviceState, desired: Map<String, Long>, now: Instant) {
        val generation = state.generation
        val old = physicalWakeups(state)
        val maintenance = old.firstOrNull { it.startsWith("$MAINTENANCE/") &&
            triggerMillis(it) > now.toEpochMilliseconds() } ?: encodeMaintenance(now + 1.days)
        val wakeups = exactReminderWakeups(desired.values.filter { it > now.toEpochMilliseconds() }, MAX_PENDING_WAKEUPS).mapTo(mutableSetOf()) { "$WAKEUP/$it" }
        wakeups += maintenance
        val boot = bootCount(context)
        state.recordSchedule(desired.keys, old + wakeups, boot)
        (old - wakeups).forEach { cancel(context, it, generation) }
        // Reinstall after boot even if the recorded wake-up did not change.
        wakeups.forEach { schedule(context, it, generation) }
        state.recordSchedule(desired.keys, wakeups, boot)
    }

    suspend fun snooze(context: Context, key: WorkOccurrenceKey, at: Instant, fromAlert: String? = null,
        generation: String = CalendarDeviceState(context).generation): Boolean = operations.withLock {
        withCalendarEffects(context) {
            val state = CalendarDeviceState(context)
            val now = Clock.System.now()
            if (at <= now || generation != state.generation || !allowed(context, state)) return@withCalendarEffects false
            val work = exact(key) ?: return@withCalendarEffects false
            val snapshot = snapshot()
            if (!work.draft.status.active || !snapshot.settings.remindersEnabled) return@withCalendarEffects false
            if (fromAlert != null && !canAct(context, state, fromAlert, work, snapshot, types())) {
                return@withCalendarEffects false
            }
            val id = "${key.deviceKey()}/-1/${at.toEpochMilliseconds()}/${snoozeFingerprint(work)}"
            val previous = state.snoozes.filter { decode(it)?.key == key }.toSet()
            state.save("snoozes", state.snoozes - previous + id)
            val pending = (state.alarmKeys - previous + id).mapNotNull { token ->
                decode(token)?.let { token to it.whenAt.toEpochMilliseconds() }
            }.toMap()
            arm(context, state, pending, now)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel(key.deviceKey(), 1)
            val visible = state.visible.filterNot { decode(it)?.key == key }.toSet()
            // Snooze from a work card also dismisses that work's overflow entry, without
            // discarding unrelated entries or changing any other planned reminder.
            val overflow = state.overflow
            val activeSummary = manager.activeNotifications.firstOrNull {
                it.tag == GROUP_SUMMARY && it.id == 1
            }?.notification
            val remaining = if (overflow.isNotEmpty() &&
                activeSummary?.extras?.getString(OVERFLOW_EXTRA) == overflowToken(overflow)) {
                overflow.mapNotNull { token ->
                    val alarm = decode(token) ?: return@mapNotNull null
                    if (alarm.key == key) return@mapNotNull null
                    val current = exact(alarm.key) ?: return@mapNotNull null
                    if (current.draft.status.active && relevant(alarm, current, now) &&
                        isAlertCurrent(token, current, snapshot)) token to current else null
                }
            } else emptyList()
            if (remaining.isNotEmpty()) {
                postOverflow(context, state, remaining, types(), alert = false, hasChildren = visible.isNotEmpty())
            } else if (activeSummary?.extras?.getString(OVERFLOW_EXTRA) != null) {
                manager.cancel(GROUP_SUMMARY, 1)
            }
            state.recordDelivery(visible, state.delivered, remaining.mapTo(mutableSetOf()) { it.first })
            updateSummary(context, state)
            true
        }
    }

    fun pauseForRestore(context: Context) {
        val state = CalendarDeviceState(context)
        val previous = state.generation
        state.pauseForRestore()
        clear(context, state, previous)
    }

    private fun physicalWakeups(state: CalendarDeviceState) =
        if (state.hasBatchedSchedule) state.values("exact_wakeups") else state.alarmKeys

    private fun activeNotifications(manager: NotificationManager) = manager.activeNotifications
        .filter { it.id == 1 && it.tag != GROUP_SUMMARY && it.notification.group == GROUP && it.tag != null }
        .associate { it.tag!! to it.notification }

    private fun cancelVisible(context: Context, state: CalendarDeviceState) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        (activeNotifications(notifications).keys + state.visible.mapNotNull { decode(it)?.key?.deviceKey() })
            .forEach { notifications.cancel(it, 1) }
        notifications.cancel(GROUP_SUMMARY, 1)
        state.recordDelivery(emptySet(), state.delivered, emptySet())
    }

    private fun clear(context: Context, state: CalendarDeviceState, generation: String = state.generation) {
        physicalWakeups(state).forEach { cancel(context, it, generation) }
        state.recordSchedule(emptySet(), emptySet())
        state.save("snoozes", emptySet())
        cancelVisible(context, state)
    }

    private fun triggerMillis(id: String) = id.substringAfter('/').toLong()

    private fun schedule(context: Context, id: String, generation: String) {
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            triggerMillis(id), checkNotNull(broadcast(context, ALARM, id, generation, PendingIntent.FLAG_UPDATE_CURRENT)))
    }

    private fun cancel(context: Context, id: String, generation: String) {
        broadcast(context, ALARM, id, generation, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    private fun post(context: Context, state: CalendarDeviceState, work: CalendarWork, id: String,
        settings: CalendarIntegrationSettings, types: List<CalendarWorkType>, quiet: Boolean = false,
        silent: Boolean = quiet) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CALENDAR_REMINDER_CHANNEL, context.getString(R.string.p7b_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT).apply { enableVibration(true) })
        val day = work.days.zip(work.draft.schedule.days).firstOrNull { it.second.offset == decode(id)?.offset }
            ?: work.days.zip(work.draft.schedule.days).first()
        val zone = work.draft.schedule.zone
        val times = day.first.interval?.takeIf { work.draft.schedule.mode == WorkTimeMode.TIMED }?.let {
            val start = it.start.toLocalDateTime(zone)
            val end = it.endExclusive.toLocalDateTime(zone)
            "${start.time.toString().take(5)}–${end.time.toString().take(5)}"
        }
        val dateTime = listOfNotNull(day.first.date.toString(), times).joinToString(" · ")
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
            .setOnlyAlertOnce(quiet)
            .setSilent(silent)
            .setExtras(android.os.Bundle().apply {
                putString(ALERT_EXTRA, id)
                putString(PAYLOAD_EXTRA, contentSignature(state, work, settings, types))
            })
            .setDeleteIntent(broadcast(context, DISMISS, id, state.generation, PendingIntent.FLAG_UPDATE_CURRENT))
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

    private fun overflowToken(ids: Set<String>) = "$OVERFLOW/${fingerprint(ids.sorted().joinToString("|"))}"

    private fun postOverflow(context: Context, state: CalendarDeviceState,
        entries: List<Pair<String, CalendarWork>>, types: List<CalendarWorkType>,
        alert: Boolean, hasChildren: Boolean) {
        val ids = entries.mapTo(mutableSetOf()) { it.first }
        val token = overflowToken(ids)
        val title = context.resources.getQuantityString(R.plurals.p7b_overflow_title, entries.size, entries.size)
        val lines = entries.sortedBy { decode(it.first)?.whenAt }.take(3).map { (id, work) ->
            val day = work.days.getOrNull(work.draft.schedule.days.indexOfFirst { it.offset == decode(id)?.offset })
                ?: work.days.first()
            val type = types.firstOrNull { it.code == work.draft.typeCode }?.label
                ?: context.getString(R.string.p7b_other)
            val time = day.interval?.takeIf { work.draft.schedule.mode == WorkTimeMode.TIMED }
                ?.start?.toLocalDateTime(work.draft.schedule.zone)?.time?.toString()?.take(5)
            listOfNotNull(type, day.date.toString(), time).joinToString(" · ")
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.forEach { style.addLine(it) }
        if (entries.size > lines.size) style.setSummaryText(context.resources.getQuantityString(
            R.plurals.p7b_overflow_more, entries.size - lines.size, entries.size - lines.size))
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setAction(OPEN_REMINDER_CALENDAR).setData(payload(token, state.generation, "calendar"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(NotificationManager::class.java).notify(GROUP_SUMMARY, 1,
            builder(context, title, context.getString(R.string.p7b_overflow_open))
                .setStyle(style).setGroupSummary(hasChildren).setSilent(!alert).setOnlyAlertOnce(!alert)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setExtras(android.os.Bundle().apply { putString(OVERFLOW_EXTRA, token) })
                .setContentIntent(intent)
                .setDeleteIntent(broadcast(context, DISMISS, token, state.generation, PendingIntent.FLAG_UPDATE_CURRENT))
                .build())
    }

    private fun updateSummary(context: Context, state: CalendarDeviceState, ignoreOverflow: Boolean = false) {
        if (!ignoreOverflow && state.overflow.isNotEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val count = manager.activeNotifications.count { it.tag != GROUP_SUMMARY && it.notification.group == GROUP }
        if (count < 2) manager.cancel(GROUP_SUMMARY, 1)
        else manager.notify(GROUP_SUMMARY, 1, builder(context, "Libellaro", context.getString(R.string.p7b_notification_channel))
            .setGroupSummary(true).setSilent(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
    }

    suspend fun openOverflow(context: Context, intent: Intent): Boolean = operations.withLock {
        if (intent.action != OPEN_REMINDER_CALENDAR) return@withLock false
        val (token, generation, action) = intent.data?.pathSegments?.takeIf { it.size == 3 } ?: return@withLock false
        withCalendarEffects(context) {
            val state = CalendarDeviceState(context)
            if (action != "calendar" || generation != state.generation || !allowed(context, state) ||
                state.overflow.isEmpty() || token != overflowToken(state.overflow)) return@withCalendarEffects false
            val active = context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.tag == GROUP_SUMMARY && it.id == 1 }?.notification
            val snapshot = snapshot()
            val now = Clock.System.now()
            val valid = active?.extras?.getString(OVERFLOW_EXTRA) == token && snapshot.settings.remindersEnabled &&
                state.overflow.any { id ->
                    val alarm = decode(id)
                    val work = alarm?.let { exact(it.key) }
                    alarm != null && work != null && work.draft.status.active &&
                        relevant(alarm, work, now) && isAlertCurrent(id, work, snapshot)
                }
            if (valid) {
                state.recordDelivery(state.visible, state.delivered + state.overflow, emptySet())
                context.getSystemService(NotificationManager::class.java).cancel(GROUP_SUMMARY, 1)
                updateSummary(context, state)
            }
            valid
        }
    }

    private fun builder(context: Context, title: String, text: String) = NotificationCompat.Builder(context, CALENDAR_REMINDER_CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text)
        .setGroup(GROUP).setAutoCancel(false).setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun action(context: Context, label: Int, pending: PendingIntent?): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(label), pending)
            .setAuthenticationRequired(true).build()

    private fun activity(context: Context, id: String, generation: String, action: String): PendingIntent {
        val target = if (action == "open") MainActivity::class.java else CalendarReminderActionActivity::class.java
        val flags = if (action == "open") Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        return PendingIntent.getActivity(context, 0, Intent(context, target)
            .setAction("com.andreyceilings.app.calendar.OPEN").setData(payload(id, generation, action))
            .addFlags(flags), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun contentSignature(state: CalendarDeviceState, work: CalendarWork,
        settings: CalendarIntegrationSettings, types: List<CalendarWorkType>): String =
        fingerprint(listOf(state.generation, settings.privateLockscreen, work.draft.title,
            work.projectLabel, work.contact.address, work.contact.name, work.contact.phone, work.draft.typeCode,
            types.firstOrNull { it.code == work.draft.typeCode }?.label).joinToString("") {
            val value = it?.toString()
            if (value == null) "-1:" else "${value.length}:$value"
        })

    private fun canAct(context: Context, state: CalendarDeviceState, id: String, work: CalendarWork,
        snapshot: CalendarIntegrationSnapshot, types: List<CalendarWorkType>): Boolean {
        val active = activeNotifications(context.getSystemService(NotificationManager::class.java))[work.key.deviceKey()]
        return activeReminderAction(allowed(context, state), id.takeIf { it in state.visible },
            active?.extras?.getString(ALERT_EXTRA), id) && snapshot.settings.remindersEnabled &&
            work.draft.status.active && isAlertCurrent(id, work, snapshot) &&
            active?.extras?.getString(PAYLOAD_EXTRA) == contentSignature(state, work, snapshot.settings, types)
    }

    suspend fun open(context: Context, intent: Intent): WorkOccurrenceKey? = operations.withLock {
        if (intent.action != "com.andreyceilings.app.calendar.OPEN") return@withLock null
        val (id, generation, action) = intent.data?.pathSegments?.takeIf { it.size == 3 } ?: return@withLock null
        withCalendarEffects(context) {
            val state = CalendarDeviceState(context)
            if (generation != state.generation || !allowed(context, state)) return@withCalendarEffects null
            val key = decode(id)?.key ?: return@withCalendarEffects null
            val work = exact(key) ?: return@withCalendarEffects null
            if (!canAct(context, state, id, work, snapshot(), types())) return@withCalendarEffects null
            if (action == "open") {
                consumeAlert(context, state, id, key)
                return@withCalendarEffects key
            }
            if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return@withCalendarEffects null
            val external = when (action) {
                "navigate" -> work.contact.address?.let { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(it)}")) }
                "dial" -> work.contact.phone?.let { Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null)) }
                "message" -> work.contact.phone?.let { Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", it, null)) }
                else -> null
            }
            if (external != null && runCatching {
                    context.startActivity(external)
                }.isSuccess) consumeAlert(context, state, id, key)
            null
        }
    }

    private fun consumeAlert(context: Context, state: CalendarDeviceState, id: String, key: WorkOccurrenceKey) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = key.deviceKey()
        // Record consumption before cancel so ordinary reconciliation cannot replay the alert.
        state.recordDelivery(state.visible - id, state.delivered + id)
        if (activeNotifications(manager)[tag]?.extras?.getString(ALERT_EXTRA) == id) manager.cancel(tag, 1)
        updateSummary(context, state)
    }

    private suspend fun dismiss(context: Context, id: String, generation: String) = operations.withLock {
        ProductionDatabaseGate.calendarEffects {
            val state = CalendarDeviceState(context)
            if (generation != state.generation) return@calendarEffects
            if (id.startsWith("$OVERFLOW/")) {
                if (state.overflow.isNotEmpty() && id == overflowToken(state.overflow)) {
                    state.recordDelivery(state.visible, state.delivered, emptySet())
                }
                return@calendarEffects
            }
            if (id !in state.visible) return@calendarEffects
            val manager = context.getSystemService(NotificationManager::class.java)
            val tag = decode(id)?.key?.deviceKey() ?: return@calendarEffects
            if (activeNotifications(manager)[tag]?.extras?.getString(ALERT_EXTRA) == id) manager.cancel(tag, 1)
            state.recordDelivery(state.visible - id, state.delivered + id)
            updateSummary(context, state)
        }
    }

    private fun isAlertCurrent(id: String, work: CalendarWork, policy: CalendarIntegrationSnapshot) =
        isSnoozeFor(id, work) || calendarReminders(work, policy.rules(work)).any { encode(it, work) == id }

    private fun isSnoozeFor(id: String, work: CalendarWork) =
        decode(id)?.let { it.offset == -1 && it.signature == snoozeFingerprint(work) } == true

    private data class Alarm(val key: WorkOccurrenceKey, val offset: Int, val whenAt: Instant, val signature: String?)

    private fun encode(alert: PlannedReminder, work: CalendarWork): String {
        val schedule = "${work.draft.date}|${work.draft.schedule}|${work.draft.typeCode}"
        return "${alert.key.deviceKey()}/${alert.dayOffset}/${alert.trigger.toEpochMilliseconds()}/${fingerprint(schedule)}"
    }

    private fun snoozeFingerprint(work: CalendarWork) = fingerprint(
        // A different occurrence may advance the shared root revision. Preserve a snooze
        // when the effective occurrence is still identical.
        "${work.key.deviceKey()}|${work.draft}",
    )

    private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }

    private fun encodeMaintenance(at: Instant) = "$MAINTENANCE/${at.toEpochMilliseconds()}"

    private fun decode(id: String): Alarm? = runCatching {
        val parts = id.split('/')
        val key = checkNotNull(parseWorkDeviceKey(parts.take(2).joinToString("/")))
        Alarm(key, parts[2].toInt(), Instant.fromEpochMilliseconds(parts[3].toLong()), parts.getOrNull(4))
    }.getOrNull()

    private fun payload(id: String, generation: String, action: String) =
        Uri.Builder().scheme("libellaro").authority("reminder").appendPath(id).appendPath(generation.ifEmpty { "initial" })
            .appendPath(action).build()

    private fun broadcast(context: Context, action: String, id: String, generation: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, Intent(context, CalendarReminderReceiver::class.java)
            .setAction(action).setData(payload(id, generation, "broadcast")), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun kotlinx.datetime.LocalDate.shiftBackOne() =
        kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() - 1)

    suspend fun onBroadcast(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments?.takeIf { it.size == 3 } ?: return
        val (id, generation) = parts
        when (intent.action) {
            ALARM -> {
                val state = CalendarDeviceState(context)
                if (state.generation != generation || id !in physicalWakeups(state)) return
                // The durable work also advances the queue if the broadcast process is interrupted.
                enqueue(context)
                reconcile(context, generation)
            }
            SNOOZE -> {
                val key = decode(id)?.key ?: return
                if (snooze(context, key, Clock.System.now() + 15.minutes, id, generation)) {
                    enqueue(context)
                    reconcile(context, generation)
                }
            }
            DISMISS -> {
                dismiss(context, id, generation)
                enqueue(context)
                reconcile(context, generation)
            }
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
                        ALARM, SNOOZE, DISMISS -> CalendarReminderRuntime.onBroadcast(context.applicationContext, intent)
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
