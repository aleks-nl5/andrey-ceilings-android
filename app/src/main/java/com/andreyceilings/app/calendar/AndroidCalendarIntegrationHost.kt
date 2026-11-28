package com.andreyceilings.app.calendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andreyceilings.shared.calendar.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

@Composable
internal fun AndroidCalendarIntegrationHost(store: CalendarStore?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val system = remember(app, store) { AndroidSystemCalendar(app) }
    val busyCache = remember(store) { linkedMapOf<Pair<WorkDateRange, TimeZone>, CalendarAvailability>() }
    val platformRevision by AndroidSystemCalendar.revision.collectAsState()
    val syncProblems by AndroidSystemCalendar.problems.collectAsState()
    val syncFailed by AndroidSystemCalendar.failed.collectAsState()
    fun refresh() {
        AndroidSystemCalendar.invalidate()
        CalendarReminderRuntime.enqueue(app)
        AndroidSystemCalendar.enqueue(app)
    }
    val calendarPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val exactAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && store != null) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val canRead = remember(platformRevision) { system.canRead }
    DisposableEffect(store, canRead) {
        val observer = if (store != null && canRead) system.observe() else null
        onDispose { observer?.close() }
    }
    val bridge = remember(store, platformRevision, syncProblems, syncFailed) {
        object : CalendarIntegrationPlatform {
            override val revision = platformRevision
            override val problems = syncProblems
            override val failed = syncFailed
            override val restorePending = system.paused
            override val remindersReady = CalendarDeviceState(app).remindersRequested &&
                canPostCalendarNotifications(app) && canScheduleCalendarAlarms(app)
            override val readCalendarGranted = system.canRead
            override val writeCalendarGranted = system.canWrite
            override val busySelection = system.busySelection
            override val destination = system.destinationSelection

            override fun requestReminders() {
                CalendarDeviceState(app).requestReminders()
                when {
                    Build.VERSION.SDK_INT >= 33 &&
                        app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Build.VERSION.SDK_INT >= 31 && !canScheduleCalendarAlarms(app) -> {
                        runCatching { exactAccess.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.fromParts("package", app.packageName, null))) }
                    }
                    !canPostCalendarNotifications(app) -> {
                        exactAccess.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName))
                    }
                    else -> refresh()
                }
            }

            override fun requestCalendarAccess(write: Boolean) {
                calendarPermissions.launch(if (write) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    else arrayOf(Manifest.permission.READ_CALENDAR))
            }

            override fun retry() = refresh()
            override suspend fun remindersChanged() {
                withContext(Dispatchers.IO) { CalendarReminderRuntime.reconcile(app) }
                refresh()
            }
            override suspend fun choices() = system.choices().map { CalendarDestinationChoice(it.identity, it.label, it.writable) }
            override suspend fun selectBusy(identities: Set<String>) = system.selectBusy(identities)
            override suspend fun selectDestination(identity: String, move: Boolean) = system.selectDestination(identity, move)
            override suspend fun createDestination() = system.createLocalCalendar()
            override suspend fun confirmRestore() {
                val requested = CalendarDeviceState(app).remindersRequested &&
                    store?.integrationSnapshot()?.settings?.remindersEnabled == true
                check(!requested || remindersReady)
                system.confirmRestore()
            }
            override fun reactivateSyncRule(rule: CalendarSyncRule) = CalendarDeviceState(app).activateRule(rule.deviceToken())
            override suspend fun enableSync() = system.enableSync()
            override suspend fun disableSync(remove: Boolean) = system.disableSync(remove)
            override suspend fun manualExport(key: WorkOccurrenceKey, version: Long, managed: Boolean,
                confirmation: CalendarExportConfirmation?): CalendarManualExportResult {
                val result = system.manualExport(key, version, managed, confirmation)
                if (result == CalendarManualExportResult.Exported) refresh()
                return result
            }
            override suspend fun snooze(key: WorkOccurrenceKey, at: Instant) = withContext(Dispatchers.IO) {
                CalendarReminderRuntime.snooze(app, key, at).also {
                    if (it) CalendarReminderRuntime.enqueue(app)
                }
            }
            override fun cachedAvailability(range: WorkDateRange, zone: TimeZone) =
                busyCache[range to zone].takeIf { readCalendarGranted && !restorePending && busySelection.isNotEmpty() }

            override suspend fun refreshAvailability(range: WorkDateRange, zone: TimeZone): CalendarAvailability {
                val fresh = system.availability(range, zone)
                busyCache[range to zone] = fresh
                if (busyCache.size > 12) busyCache.remove(busyCache.keys.first())
                return fresh
            }

            override suspend fun availability(range: WorkDateRange, zone: TimeZone) = system.availability(range, zone)
        }
    }
    CompositionLocalProvider(LocalCalendarIntegrationPlatform provides bridge, content = content)
}
