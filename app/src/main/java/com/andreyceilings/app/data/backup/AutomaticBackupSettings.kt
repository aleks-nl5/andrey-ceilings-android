package com.andreyceilings.app.data.backup

import android.content.Context

enum class BackupSchedule(val label: String) {
    DAILY("Щодня"),
    WEEKLY("Щотижня"),
    MONTHLY("Щомісяця")
}

/** Persists which automatic backup triggers are enabled on this installation. */
object AutomaticBackupSettings {
    private const val PREFS = "automatic_backup_modes_v1"
    private const val KEY_SCHEDULE = "schedule"
    private const val KEY_PERIODIC = "periodic_enabled"
    private const val KEY_BACKGROUND = "background_enabled"
    private const val KEY_DATABASE_CHANGE = "database_change_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun schedule(context: Context): BackupSchedule =
        runCatching { BackupSchedule.valueOf(prefs(context).getString(KEY_SCHEDULE, BackupSchedule.DAILY.name)!!) }
            .getOrDefault(BackupSchedule.DAILY)

    fun setSchedule(context: Context, schedule: BackupSchedule) {
        prefs(context).edit().putString(KEY_SCHEDULE, schedule.name).apply()
    }

    fun periodicEnabled(context: Context) = prefs(context).getBoolean(KEY_PERIODIC, false)
    fun backgroundEnabled(context: Context) = prefs(context).getBoolean(KEY_BACKGROUND, false)
    fun databaseChangeEnabled(context: Context) = prefs(context).getBoolean(KEY_DATABASE_CHANGE, false)

    fun setPeriodicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PERIODIC, enabled).apply()
    }

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BACKGROUND, enabled).apply()
    }

    fun setDatabaseChangeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DATABASE_CHANGE, enabled).apply()
    }

    fun anyEnabled(context: Context) =
        periodicEnabled(context) || backgroundEnabled(context) || databaseChangeEnabled(context)
}
