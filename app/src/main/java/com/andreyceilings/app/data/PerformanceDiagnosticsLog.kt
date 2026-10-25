package com.andreyceilings.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Debug-only performance diagnostics. Records only technical timing messages.
 * The log remains in app-private cache until the user explicitly exports it.
 */
object PerformanceDiagnosticsLog {
    private const val TAG = "CeilingsPerf"
    private const val FILE_NAME = "ceilings-perf.log"
    private val lock = Any()

    fun record(context: Context, message: String) {
        if (!com.andreyceilings.app.BuildConfig.DEBUG ||
            !PerformanceDiagnosticsSettings.isEnabled(context.applicationContext)
        ) return

        Log.d(TAG, message)
        synchronized(lock) {
            runCatching {
                File(context.applicationContext.cacheDir, FILE_NAME).appendText(
                    "${System.currentTimeMillis()} $message\n",
                    Charsets.UTF_8
                )
            }.onFailure { Log.w(TAG, "Could not append diagnostics file", it) }
        }
    }

    fun file(context: Context): File =
        File(context.applicationContext.cacheDir, FILE_NAME)

    fun clear(context: Context): Boolean = synchronized(lock) {
        val file = file(context)
        !file.exists() || file.delete()
    }

    fun readForExport(context: Context): ByteArray = synchronized(lock) {
        val file = file(context)
        if (file.isFile) file.readBytes() else ByteArray(0)
    }
}
