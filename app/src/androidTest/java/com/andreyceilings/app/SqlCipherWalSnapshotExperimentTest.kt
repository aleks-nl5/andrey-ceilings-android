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
    @Test fun isolatedWalAndVacuumSnapshotStayEncryptedAndSurviveReopen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_wal_snapshot.db"
        val snapshot = File(context.cacheDir, "sqldelight_wal_snapshot_test.db")
        val key = ByteArray(32) { 0x35.toByte() }
        context.deleteDatabase(name)
        snapshot.delete()
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
            }
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { db ->
                // The current Android driver may reset WAL to DELETE on reopen.
                // Production WAL support must explicitly enforce it at open.
                val modeOnReopen = db.queryText("PRAGMA journal_mode")?.lowercase()
                assertTrue("Unexpected SQLite journal mode: $modeOnReopen",
                    modeOnReopen == "delete" || modeOnReopen == "wal")
                assertEquals("wal", db.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                assertEquals(1, ProjectRepository(db.database).all().size)
            }
        } finally {
            snapshot.delete()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
