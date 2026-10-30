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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unlike fault-injection tests, this sends SIGKILL to an isolated DEBUG
 * provider *after* restore has modified the live encrypted file or key.
 * Instrumentation survives the provider process death and verifies real
 * cold-process recovery. Never used on a physical user's production DB.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherProcessDeathRecoveryTest {
    @Test fun realProcessDeathRestoresOriginalDatabaseAndKeystoreKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://${RestoreCrashProbeProvider.AUTHORITY}")
        val payload = File(context.cacheDir, RestoreCrashProbeProvider.INCOMING_IMAGE)
        val marker = File(context.cacheDir, RestoreCrashProbeProvider.CHECKPOINT_FILE)
        val importedName = "sqldelight_test_process_death_import.db"
        val importedKey = ByteArray(32) { 0x41.toByte() }
        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val original = CeilingsDatabase.get(context)
        val initial = PortableBackupManager.createVerifiedSnapshot(context, original)
        val initialBytes = initial.readBytes()
        val originalTitles = ProjectRepository(original.database).all()
            .map { it.title }.sorted()
        val addedTitle = "Must survive real killed restore"
        context.deleteDatabase(importedName)
        try {
            ProjectRepository(original.database).createReturningId(
                clientId = null, title = addedTitle, createdAt = 7654321L
            )
            val expected = (originalTitles + addedTitle).sorted()

            createEncryptedPrototypeDriver(
                context, importedKey, importedName
            ).use { driver ->
                ProjectRepository(PrototypeDatabase(driver)).createReturningId(
                    clientId = null,
                    title = "Must not survive real killed restore",
                    createdAt = 7654322L
                )
            }
            context.getDatabasePath(importedName).copyTo(payload, overwrite = true)

            // The test process must close the sole live driver before the
            // isolated provider opens/restores the same encrypted app DB.
            CeilingsDatabase.closeAndReset()
            val killPoints = listOf(
                SqlCipherRestoreManager.RestoreCheckpoint.AFTER_IMPORTED_FILE_INSTALLED,
                SqlCipherRestoreManager.RestoreCheckpoint.AFTER_KEY_REPLACED
            )
            killPoints.forEach { checkpoint ->
                marker.delete()
                val extras = Bundle().apply {
                    putByteArray("importedKey", importedKey.copyOf())
                }
                val attempt = runCatching {
                    context.contentResolver.call(uri, "crash", checkpoint.name, extras)
                }
                assertFalse("Crash provider returned normally at $checkpoint",
                    attempt.getOrNull()?.getBoolean("unexpectedReturn") == true)
                assertEquals("SIGKILL checkpoint was never reached",
                    checkpoint.name, marker.takeIf { it.isFile }?.readText())

                // This call starts a fresh remote process. The production
                // CeilingsDatabase.get() must recover BEFORE opening the DB.
                var result: Bundle? = null
                var lastError: Throwable? = null
                repeat(4) {
                    if (result == null) {
                        try {
                            result = context.contentResolver.call(uri, "verify", null, null)
                        } catch (error: Exception) {
                            lastError = error
                            Thread.sleep(250)
                        }
                    }
                }
                val verified = result ?: throw AssertionError(
                    "Fresh process never opened recovered SQLCipher after $checkpoint",
                    lastError
                )
                assertTrue("Recovered database is not integral", verified.getBoolean("integrity"))
                assertFalse("Durable recovery marker was not cleared",
                    verified.getBoolean("pendingRestore"))
                assertEquals("Data loss on cold restart after $checkpoint",
                    expected, verified.getStringArrayList("projects")?.sorted())

                val recovered = CeilingsDatabase.get(context)
                assertTrue(recovered.checkIntegrity())
                assertEquals(expected, ProjectRepository(recovered.database).all()
                    .map { it.title }.sorted())
                val activeKey = DatabaseKeyManager.getOrCreatePassphrase(context)
                try {
                    assertArrayEquals("Original encryption key was lost", originalKey, activeKey)
                } finally {
                    activeKey.fill(0)
                }
                CeilingsDatabase.closeAndReset()
            }
        } finally {
            // Always return the test app's original project rows.
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(context,
                    PortableBackupPayload.Contents(initialBytes, originalKey))
            } finally {
                payload.delete()
                marker.delete()
                initial.delete()
                context.deleteDatabase(importedName)
                importedKey.fill(0)
                originalKey.fill(0)
                initialBytes.fill(0)
            }
        }
    }
}
