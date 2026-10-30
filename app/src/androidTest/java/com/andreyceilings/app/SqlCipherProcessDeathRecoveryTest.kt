package com.andreyceilings.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.RestoreCrashProbeProvider
import com.andreyceilings.app.data.security.SqlCipherRestoreManager
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These tests deliberately crash an app process. They MUST NOT be run by the
 * regular connectedAndroidTest task: Android Test Runner considers any app
 * process crash a test-session failure, even when SIGKILL is intentional.
 *
 * CI executes each crash in an isolated 'adb shell am instrument' invocation,
 * verifies the fsynced checkpoint with run-as, then starts a NEW instrumentation
 * run for recovery checks. See ci/verify-sqlcipher-process-death.sh.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IsolatedProcessDeathProof

@RunWith(AndroidJUnit4::class)
@IsolatedProcessDeathProof
class SqlCipherProcessDeathRecoveryTest {
    companion object {
        private const val IMPORTED_NAME = "sqldelight_test_process_death_import.db"
        private const val BASELINE = "restore_crash_test_baseline.db"
        private const val EXPECTED = "restore_crash_test_expected.json"
        private const val FORBIDDEN_TITLE = "Must not survive real killed restore"
    }

    @Test fun crashAfterImportedFileInstalled() {
        prepareAndKill(
            SqlCipherRestoreManager.RestoreCheckpoint.AFTER_IMPORTED_FILE_INSTALLED
        )
    }

    @Test fun crashAfterKeystoreKeyReplaced() {
        prepareAndKill(
            SqlCipherRestoreManager.RestoreCheckpoint.AFTER_KEY_REPLACED
        )
    }

    private fun prepareAndKill(checkpoint: SqlCipherRestoreManager.RestoreCheckpoint) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseline = File(context.cacheDir, BASELINE)
        val metadata = File(context.cacheDir, EXPECTED)
        val incoming = File(context.cacheDir, RestoreCrashProbeProvider.INCOMING_IMAGE)
        val marker = File(context.cacheDir, RestoreCrashProbeProvider.CHECKPOINT_FILE)
        check(!baseline.exists() && !metadata.exists()) {
            "Previous process-death proof was not verified/cleaned"
        }
        marker.delete()
        incoming.delete()

        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val initial = PortableBackupManager.createVerifiedSnapshot(
            context, CeilingsDatabase.get(context)
        )
        try {
            initial.copyTo(baseline, overwrite = false)
        } finally {
            initial.delete()
        }
        val projectRepository = ProjectRepository(CeilingsDatabase.get(context).database)
        val title = "Committed before SIGKILL $checkpoint"
        projectRepository.createReturningId(
            clientId = null, title = title, createdAt = 7654321L
        )
        val expectedTitles = projectRepository.all().map { it.title }.sorted()
        val information = JSONObject()
            .put("checkpoint", checkpoint.name)
            .put("keyHash", sha256(originalKey))
            .put("expectedProjects", JSONArray(expectedTitles))
        // A durable manifest survives even if Android kills the instrumentation
        // main process when it observes the secondary process SIGKILL.
        FileOutputStream(metadata).use { output ->
            output.write(information.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }

        val importedKey = ByteArray(32) { 0x41.toByte() }
        context.deleteDatabase(IMPORTED_NAME)
        try {
            createEncryptedPrototypeDriver(context, importedKey, IMPORTED_NAME).use { driver ->
                ProjectRepository(PrototypeDatabase(driver)).createReturningId(
                    clientId = null, title = FORBIDDEN_TITLE, createdAt = 7654322L
                )
            }
            context.getDatabasePath(IMPORTED_NAME).copyTo(incoming, overwrite = false)
            CeilingsDatabase.closeAndReset()
            val extras = Bundle().apply { putByteArray("importedKey", importedKey) }
            context.contentResolver.call(
                Uri.parse("content://${RestoreCrashProbeProvider.AUTHORITY}"),
                "crash", checkpoint.name, extras
            )
            error("The remote SQLCipher crash probe returned without SIGKILL")
        } finally {
            importedKey.fill(0)
            originalKey.fill(0)
            context.deleteDatabase(IMPORTED_NAME)
        }
    }

    /**
     * Runs only after the CI shell script has proved that the prior
     * instrumentation process reached its exact fsynced kill checkpoint.
     * A new app/remote process must recover the original SQLCipher file/key.
     */
    @Test fun verifyRecoveryAndRestoreBaseline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metadata = File(context.cacheDir, EXPECTED)
        val baseline = File(context.cacheDir, BASELINE)
        val marker = File(context.cacheDir, RestoreCrashProbeProvider.CHECKPOINT_FILE)
        assertTrue("Missing durable test metadata", metadata.isFile)
        assertTrue("Missing encrypted original snapshot", baseline.isFile)
        val expected = JSONObject(metadata.readText())
        val checkpoint = expected.getString("checkpoint")
        assertEquals("Wrong durable checkpoint", checkpoint, marker.readText())
        val jsonTitles = expected.getJSONArray("expectedProjects")
        val titles = (0 until jsonTitles.length()).map { jsonTitles.getString(it) }.sorted()

        // The verify call is serviced by a freshly launched app process.
        val verified = requireNotNull(context.contentResolver.call(
            Uri.parse("content://${RestoreCrashProbeProvider.AUTHORITY}"),
            "verify", null, null
        )) { "No response from cold-process SQLCipher recovery" }
        assertTrue("Recovery failed integrity_check", verified.getBoolean("integrity"))
        assertEquals("Remote cold-open after crash must return to WAL", "wal",
            verified.getString("journalMode")?.lowercase())
        assertFalse("Recovery marker was not cleaned", verified.getBoolean("pendingRestore"))
        assertEquals("Committed rows were not recovered", titles,
            verified.getStringArrayList("projects")?.sorted())
        assertFalse("Imported rows survived rollback",
            verified.getStringArrayList("projects").orEmpty().contains(FORBIDDEN_TITLE))

        val reopened = CeilingsDatabase.get(context)
        assertEquals("Production WAL mode lost after crash recovery", "wal",
            reopened.queryText("PRAGMA journal_mode")?.lowercase())
        assertTrue("Second reopen lost integrity", reopened.checkIntegrity())
        assertEquals(titles,
            ProjectRepository(reopened.database).all().map { it.title }.sorted())
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        try {
            assertEquals("Recovered SQLCipher key differs from original",
                expected.getString("keyHash"), sha256(key))
            // Restore the exact encrypted baseline so the next crash proof
            // starts from the same state. It cannot silently keep test rows.
            CeilingsDatabase.closeAndReset()
            SqlCipherRestoreManager.restore(
                context, PortableBackupPayload.Contents(baseline.readBytes(), key)
            )
            assertTrue(CeilingsDatabase.get(context).checkIntegrity())
        } finally {
            key.fill(0)
        }
        baseline.delete()
        metadata.delete()
        marker.delete()
        File(context.cacheDir, RestoreCrashProbeProvider.INCOMING_IMAGE).delete()
        context.deleteDatabase(IMPORTED_NAME)
    }

    private fun sha256(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { b ->
            (b.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
