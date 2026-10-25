package com.andreyceilings.app.data

import android.content.Context
import android.util.Log
import java.io.File

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

/** Records technical timings privately until the user explicitly exports the log. */
object PerformanceDiagnosticsLog {
    private const val TAG = "CeilingsPerf"
    private const val FILE_NAME = "ceilings-perf.log"
    private val lock = Any()

    fun record(context: Context, message: String) {
        val appContext = context.applicationContext
        if (!com.andreyceilings.app.BuildConfig.DEBUG ||
            !PerformanceDiagnosticsSettings.isEnabled(appContext)
        ) return

        Log.d(TAG, message)
        synchronized(lock) {
            runCatching {
                File(appContext.cacheDir, FILE_NAME).appendText(
                    "${System.currentTimeMillis()} $message\n",
                    Charsets.UTF_8
                )
            }.onFailure { Log.w(TAG, "Could not append diagnostics file", it) }
        }
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        val file = File(context.applicationContext.cacheDir, FILE_NAME)
        !file.exists() || file.delete()
    }

    fun readForExport(context: Context): ByteArray = synchronized(lock) {
        val file = File(context.applicationContext.cacheDir, FILE_NAME)
        if (file.isFile) file.readBytes() else ByteArray(0)
    }
}
