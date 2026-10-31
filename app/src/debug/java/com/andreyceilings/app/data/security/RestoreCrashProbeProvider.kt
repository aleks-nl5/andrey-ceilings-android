package com.andreyceilings.app.data.security

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.db.ProjectRepository
import java.io.File
import java.io.FileOutputStream

/**
 * DEBUG ONLY. Runs in a secondary app process so instrumentation can survive
 * a real SIGKILL while SQLCipher restore is between durable file/key steps.
 *
 * Must never be exported, never appear in the release manifest, and never
 * run against anything other than the app's emulator/instrumentation sandbox.
 */
internal class RestoreCrashProbeProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.andreyceilings.app.restorecrashprobe"
        const val INCOMING_IMAGE = "restore_crash_probe_incoming.db"
        const val CHECKPOINT_FILE = "restore_crash_probe_checkpoint.txt"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = requireNotNull(context).applicationContext
        return when (method) {
            "crash" -> {
                val checkpoint = SqlCipherRestoreManager.RestoreCheckpoint.valueOf(
                    requireNotNull(arg) { "Required restore checkpoint" }
                )
                require(checkpoint == SqlCipherRestoreManager.RestoreCheckpoint.AFTER_IMPORTED_FILE_INSTALLED ||
                    checkpoint == SqlCipherRestoreManager.RestoreCheckpoint.AFTER_KEY_REPLACED)
                val image = File(app.cacheDir, INCOMING_IMAGE).readBytes()
                val importedKey = requireNotNull(extras?.getByteArray("importedKey")).copyOf()
                try {
                    SqlCipherRestoreManager.restore(
                        app, PortableBackupPayload.Contents(image, importedKey)
                    ) { reached ->
                        if (reached == checkpoint) {
                            // Write and fsync the proof of which *durable*
                            // restore checkpoint was actually reached.
                            val marker = File(app.cacheDir, CHECKPOINT_FILE)
                            FileOutputStream(marker).use { output ->
                                output.write(reached.name.toByteArray(Charsets.UTF_8))
                                output.fd.sync()
                            }
                            // A real process kill, not an injected exception:
                            // catch/finally blocks inside restore cannot run.
                            Process.killProcess(Process.myPid())
                            Runtime.getRuntime().halt(91)
                        }
                    }
                } finally {
                    importedKey.fill(0)
                    image.fill(0)
                }
                // Returning means the process was NOT killed; the test fails.
                Bundle().apply { putBoolean("unexpectedReturn", true) }
            }
            "verify" -> {
                val database = CeilingsDatabase.get(app)
                try {
                    val originalKeyMarker = DatabaseKeyManager.pendingRestoreOriginalPassphrase(app)
                    originalKeyMarker?.fill(0)
                    Bundle().apply {
                        putBoolean("integrity", database.checkIntegrity())
                        putString("journalMode", database.queryText("PRAGMA journal_mode"))
                        putBoolean("pendingRestore", originalKeyMarker != null)
                        putStringArrayList("projects", ArrayList(
                            ProjectRepository(database.database).all().map { it.title }.sorted()
                        ))
                    }
                } finally {
                    CeilingsDatabase.closeAndReset()
                }
            }
            else -> throw IllegalArgumentException("Unknown debug restore-crash operation")
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Debug-only restore probe")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Debug-only restore probe")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Debug-only restore probe")
}
