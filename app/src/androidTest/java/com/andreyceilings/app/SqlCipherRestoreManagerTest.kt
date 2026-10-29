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
import java.io.IOException
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
            // Force WAL on the live instrumentation DB and commit a change
            // before replacement. A restore must never leave stale WAL frames
            // attached to the newly imported encrypted image.
            val before = CeilingsDatabase.get(context)
            assertEquals("wal", before.queryText("PRAGMA journal_mode=WAL")?.lowercase())
            ProjectRepository(before.database).createReturningId(
                clientId = null, title = "Committed old WAL row", createdAt = 12340L
            )
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

            // Simulate process death midway through an import after a WAL
            // transaction; the rollback snapshot must include its latest row.
            assertEquals("wal", installed.queryText("PRAGMA journal_mode=WAL")?.lowercase())
            ProjectRepository(installed.database).createReturningId(
                clientId = null, title = "Committed WAL recovery row", createdAt = 12346L
            )
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
                val recoveredProjects = ProjectRepository(recovered.database).all()
                assertEquals(2, recoveredProjects.size)
                assertTrue(recoveredProjects.any { it.title == "Committed WAL recovery row" })
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

    @Test fun injectedFailuresRollbackDatabaseAndKeystoreKeyAtEveryPhase() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sourceName = "sqldelight_test_fault_restore_source.db"
        val importedKey = ByteArray(32) { 0x53.toByte() }
        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val originalSnapshot = PortableBackupManager.createVerifiedSnapshot(
            context, CeilingsDatabase.get(context)
        )
        val originalImage = originalSnapshot.readBytes()
        val originalTitles = ProjectRepository(CeilingsDatabase.get(context).database)
            .all().map { it.title }.sorted()
        val rollback = File(
            context.getDatabasePath(CeilingsDatabase.DATABASE_NAME).parentFile,
            ".ceilings_restore_rollback.db"
        )
        context.deleteDatabase(sourceName)
        try {
            createEncryptedPrototypeDriver(context, importedKey, sourceName).use { driver ->
                ProjectRepository(PrototypeDatabase(driver)).createReturningId(
                    clientId = null, title = "Must never survive a failed restore",
                    createdAt = 98765L
                )
            }
            val incomingImage = context.getDatabasePath(sourceName).readBytes()
            try {
                SqlCipherRestoreManager.RestoreCheckpoint.entries.forEach { checkpoint ->
                    val injected = IOException("Simulated failure at $checkpoint")
                    val failed = runCatching {
                        SqlCipherRestoreManager.restore(
                            context,
                            PortableBackupPayload.Contents(incomingImage, importedKey)
                        ) { reached ->
                            if (reached == checkpoint) throw injected
                        }
                    }
                    assertTrue("Checkpoint not reached: $checkpoint",
                        failed.exceptionOrNull() === injected)
                    val live = CeilingsDatabase.get(context)
                    assertTrue("Integrity failed after $checkpoint", live.checkIntegrity())
                    assertEquals("Rows lost after $checkpoint", originalTitles,
                        ProjectRepository(live.database).all().map { it.title }.sorted())
                    assertTrue("Rollback file leaked after $checkpoint", !rollback.exists())
                    val activeKey = DatabaseKeyManager.getOrCreatePassphrase(context)
                    try {
                        assertArrayEquals("SQLCipher key changed after $checkpoint",
                            originalKey, activeKey)
                    } finally {
                        activeKey.fill(0)
                    }
                    // A valid new snapshot after rollback proves the database
                    // remains usable for future backup and subsequent imports.
                    PortableBackupManager.createVerifiedSnapshot(context, live).delete()
                }
            } finally {
                incomingImage.fill(0)
            }
        } finally {
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
