package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.QueryResult
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
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
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val mode = driver.executeQuery(null, "PRAGMA journal_mode=WAL", { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                }, 0).value
                assertEquals("wal", mode?.lowercase())
                ProjectRepository(PrototypeDatabase(driver)).createReturningId(
                    clientId = null, title = "WAL snapshot", createdAt = 1000L
                )
                assertFalse(snapshot.exists())
                val safePath = snapshot.absolutePath.replace("'", "''")
                driver.execute(null, "VACUUM INTO '$safePath'", 0)
                assertTrue(snapshot.isFile && snapshot.length() > 16L)
                // A plaintext VACUUM output is not an acceptable SQLCipher backup.
                val header = snapshot.inputStream().use { stream ->
                    ByteArray(16).also { stream.read(it) }
                }
                assertFalse(header.toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
                PortableBackupManager.verifyEncryptedSnapshot(context, snapshot, key)
            }
            createEncryptedPrototypeDriver(context, key, name).use { driver ->
                val mode = driver.executeQuery(null, "PRAGMA journal_mode", { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
                }, 0).value
                assertEquals("wal", mode?.lowercase())
                assertEquals(1, ProjectRepository(PrototypeDatabase(driver)).all().size)
            }
        } finally {
            snapshot.delete()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
