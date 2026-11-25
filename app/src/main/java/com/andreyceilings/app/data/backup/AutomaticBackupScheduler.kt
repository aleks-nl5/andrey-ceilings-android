package com.andreyceilings.app.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the two independent triggers that share the same portable-backup worker. */
object AutomaticBackupScheduler {
    private const val PERIODIC_WORK_NAME = "stelapro_periodic_portable_backup"
    private const val BACKGROUND_WORK_NAME = "stelapro_background_portable_backup"
    private const val LEGACY_DATABASE_WORK_NAME = "stelapro_database_change_portable_backup"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun enable(context: Context, schedule: BackupSchedule) {
        val interval = when (schedule) {
            BackupSchedule.DAILY -> 1L to TimeUnit.DAYS
            BackupSchedule.WEEKLY -> 7L to TimeUnit.DAYS
            BackupSchedule.MONTHLY -> 30L to TimeUnit.DAYS
        }
        val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(interval.first, interval.second)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun disablePeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun triggerOnBackground(context: Context) = enqueueOnce(context, BACKGROUND_WORK_NAME)

    /** Best-effort cancellation of the retired one-time request on app startup. */
    fun cancelLegacyDatabaseChange(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(LEGACY_DATABASE_WORK_NAME)
    }

    private fun enqueueOnce(context: Context, name: String) {
        val request = OneTimeWorkRequestBuilder<AutomaticBackupWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    fun disableAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(BACKGROUND_WORK_NAME)
        cancelLegacyDatabaseChange(context)
    }
}
