package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.shared.db.ProjectRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated WAL compatibility probe. Deliberately DOES NOT switch production
 * databases from DELETE to WAL. A failed encrypted snapshot must block rollout.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherWalSnapshotExperimentTest {
    @Test fun invalidSnapshotDestinationFailsWithoutMutatingWal() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_wal_snapshot_failure.db"
        val key = ByteArray(32) { 0x64.toByte() }
        val existing = File.createTempFile("wal_invalid_destination_", ".db", context.cacheDir)
        context.deleteDatabase(name)
        try {
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { db ->
                assertEquals("wal", db.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                ProjectRepository(db.database).createReturningId(
                    clientId = null, title = "Survives failed snapshot", createdAt = 100L
                )
                val failure = runCatching { db.writeConsistentEncryptedSnapshot(existing) }
                assertTrue("Existing destination must be rejected", failure.isFailure)
                assertTrue(db.checkIntegrity())
                assertEquals(1, ProjectRepository(db.database).all().size)
                val good = File.createTempFile("wal_usable_after_failure_", ".db", context.cacheDir)
                try {
                    assertTrue(good.delete())
                    db.writeConsistentEncryptedSnapshot(good)
                    PortableBackupManager.verifyEncryptedSnapshot(context, good, key)
                } finally {
                    good.delete()
                }
            }
        } finally {
            existing.delete()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    @Test fun isolatedWalAndVacuumSnapshotStayEncryptedAndSurviveReopen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_wal_snapshot.db"
        val snapshot = File(context.cacheDir, "sqldelight_wal_snapshot_test.db")
        val laterSnapshot = File(context.cacheDir, "sqldelight_wal_snapshot_later_test.db")
        val verificationName = "sqldelight_test_wal_copy.db"
        val key = ByteArray(32) { 0x35.toByte() }
        context.deleteDatabase(name)
        context.deleteDatabase(verificationName)
        snapshot.delete()
        laterSnapshot.delete()
        try {
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { db ->
                assertEquals("wal", db.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                ProjectRepository(db.database).createReturningId(
                    clientId = null, title = "WAL snapshot", createdAt = 1000L
                )
                assertFalse(snapshot.exists())
                db.writeConsistentEncryptedSnapshot(snapshot)
                assertTrue(snapshot.isFile && snapshot.length() > 16L)
                // The real backup verification rejects plaintext or corrupt images.
                PortableBackupManager.verifyEncryptedSnapshot(context, snapshot, key)
                // A second committed transaction is not allowed to mutate the
                // earlier snapshot. It must appear in a later WAL snapshot.
                ProjectRepository(db.database).createReturningId(
                    clientId = null, title = "Second WAL commit", createdAt = 2000L
                )
                db.writeConsistentEncryptedSnapshot(laterSnapshot)
                PortableBackupManager.verifyEncryptedSnapshot(context, laterSnapshot, key)

                fun verifyProjectCount(image: File, expected: Int) {
                    context.deleteDatabase(verificationName)
                    image.copyTo(context.getDatabasePath(verificationName))
                    CeilingsDatabase.openIsolatedForTesting(context, verificationName, key).use { read ->
                        assertTrue(read.checkIntegrity())
                        assertEquals(expected, ProjectRepository(read.database).all().size)
                    }
                }
                verifyProjectCount(snapshot, 1)
                verifyProjectCount(laterSnapshot, 2)
            }
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { db ->
                // The current Android driver may reset WAL to DELETE on reopen.
                // Production WAL support must explicitly enforce it at open.
                val modeOnReopen = db.queryText("PRAGMA journal_mode")?.lowercase()
                assertTrue("Unexpected SQLite journal mode: $modeOnReopen",
                    modeOnReopen == "delete" || modeOnReopen == "wal")
                assertEquals("wal", db.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                assertEquals(2, ProjectRepository(db.database).all().size)
            }
        } finally {
            snapshot.delete()
            laterSnapshot.delete()
            context.deleteDatabase(verificationName)
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    /**
     * Proof of the future production open path, on disposable encrypted
     * files only: existing DELETE-mode rows survive opening a WAL-aware
     * SQLCipher driver and every subsequent reopen.
     */
    @Test fun existingEncryptedDeleteDatabaseMigratesToWalWithoutLosingRows() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_delete_to_wal.db"
        val copyName = "sqldelight_test_delete_to_wal_snapshot_copy.db"
        val key = ByteArray(32) { 0x58.toByte() }
        val snapshot = File(context.cacheDir, "sqldelight_delete_to_wal_snapshot.db")
        context.deleteDatabase(name)
        context.deleteDatabase(copyName)
        snapshot.delete()
        try {
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { db ->
                assertEquals("delete", db.queryText("PRAGMA journal_mode")?.lowercase())
                ProjectRepository(db.database).createReturningId(
                    clientId = null, title = "Committed in DELETE", createdAt = 100L
                )
                assertTrue(db.checkIntegrity())
            }
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { db ->
                // No PRAGMA journal_mode=WAL setter: the helper must provide WAL.
                assertEquals("wal", db.queryText("PRAGMA journal_mode")?.lowercase())
                assertEquals(listOf("Committed in DELETE"),
                    ProjectRepository(db.database).all().map { it.title })
                ProjectRepository(db.database).createReturningId(
                    clientId = null, title = "Committed in WAL", createdAt = 200L
                )
                assertTrue(db.checkpoint(CeilingsDatabase.WalMode.FULL).complete)
                db.writeConsistentEncryptedSnapshot(snapshot)
                PortableBackupManager.verifyEncryptedSnapshot(context, snapshot, key)
                assertTrue(db.checkIntegrity())
            }
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { reopened ->
                assertEquals("wal", reopened.queryText("PRAGMA journal_mode")?.lowercase())
                assertEquals(
                    setOf("Committed in DELETE", "Committed in WAL"),
                    ProjectRepository(reopened.database).all().map { it.title }.toSet()
                )
                assertTrue(reopened.checkIntegrity())
            }
            snapshot.copyTo(context.getDatabasePath(copyName))
            CeilingsDatabase.openIsolatedForTesting(
                context, copyName, key, enableWalForTesting = true
            ).use { fromBackup ->
                assertEquals("wal", fromBackup.queryText("PRAGMA journal_mode")?.lowercase())
                assertEquals(2, ProjectRepository(fromBackup.database).all().size)
                assertTrue(fromBackup.checkIntegrity())
            }
        } finally {
            snapshot.delete()
            context.deleteDatabase(copyName)
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
