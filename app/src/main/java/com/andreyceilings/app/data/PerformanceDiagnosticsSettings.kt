package com.andreyceilings.app.data

import android.content.Context

/** Controls optional performance timing logs in debug builds. */
object PerformanceDiagnosticsSettings {
    private const val PREFS_NAME = "performance_diagnostics"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
