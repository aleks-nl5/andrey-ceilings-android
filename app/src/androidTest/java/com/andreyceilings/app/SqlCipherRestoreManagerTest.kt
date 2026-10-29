package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.SqlCipherRestoreManager
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only test in the instrumentation app sandbox.
 * Restores the original SQLCipher image/key in a finally block so other tests
 * see the same database state, even when an assertion fails.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherRestoreManagerTest {
    @Test fun restoresVerifiedImageRejectsCorruptionAndRecoversInterruptedSwap() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sourceName = "sqldelight_test_restore_payload.db"
        val importedKey = ByteArray(32) { 0x42.toByte() }
        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val originalSnapshot = PortableBackupManager.createVerifiedSnapshot(
            context, CeilingsDatabase.get(context)
        )
        val originalImage = originalSnapshot.readBytes()
        val source = context.getDatabasePath(sourceName)
        val live = context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        val rollback = File(live.parentFile, ".ceilings_restore_rollback.db")
        context.deleteDatabase(sourceName)
        try {
            createEncryptedPrototypeDriver(context, importedKey, sourceName).use { driver ->
                ProjectRepository(PrototypeDatabase(driver)).createReturningId(
                    clientId = null, title = "Restored project", createdAt = 12345L
                )
            }
            val importedImage = source.readBytes()
            val installed = SqlCipherRestoreManager.restore(
                context, PortableBackupPayload.Contents(importedImage, importedKey)
            )
            assertTrue(installed.checkIntegrity())
            assertEquals(1, ProjectRepository(installed.database).all().size)
            val activeKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals(importedKey, activeKey)
            } finally {
                activeKey.fill(0)
            }

            // Corrupt image must fail validation without closing/replacing live.
            val rejected = runCatching {
                SqlCipherRestoreManager.restore(
                    context,
                    PortableBackupPayload.Contents(ByteArray(64) { 0x7f.toByte() }, importedKey)
                )
            }
            assertNotNull("Corrupt imported SQLCipher database was accepted", rejected.exceptionOrNull())
            assertEquals(1, ProjectRepository(CeilingsDatabase.get(context).database).all().size)

            // Simulate process death midway through an import: recovery marker,
            // encrypted rollback snapshot, but an unusable live image.
            val currentSnapshot = PortableBackupManager.createVerifiedSnapshot(
                context, CeilingsDatabase.get(context)
            )
            try {
                assertTrue(!rollback.exists())
                currentSnapshot.copyTo(rollback, overwrite = false)
                DatabaseKeyManager.beginPendingRestore(context)
                CeilingsDatabase.closeAndReset()
                context.deleteDatabase(CeilingsDatabase.DATABASE_NAME)
                live.writeBytes(byteArrayOf(0x11, 0x22, 0x33))
                val recovered = CeilingsDatabase.get(context)
                assertTrue(recovered.checkIntegrity())
                assertEquals(1, ProjectRepository(recovered.database).all().size)
                val afterRecoveryKey = DatabaseKeyManager.getOrCreatePassphrase(context)
                try {
                    assertArrayEquals(importedKey, afterRecoveryKey)
                } finally {
                    afterRecoveryKey.fill(0)
                }
                assertTrue("Rollback journal was not cleaned", !rollback.exists())
            } finally {
                currentSnapshot.delete()
            }
        } finally {
            // A failed test must not leave the instrumentation app in a
            // partially restored state or with an uncommitted key marker.
            runCatching {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
            }
            try {
                SqlCipherRestoreManager.restore(
                    context, PortableBackupPayload.Contents(originalImage, originalKey)
                )
            } finally {
                originalSnapshot.delete()
                context.deleteDatabase(sourceName)
                importedKey.fill(0)
                originalKey.fill(0)
                originalImage.fill(0)
            }
        }
    }
}
