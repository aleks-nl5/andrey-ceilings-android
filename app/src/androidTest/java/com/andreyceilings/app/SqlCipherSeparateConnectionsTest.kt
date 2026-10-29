package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.shared.db.ProjectRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises two independent encrypted Android SQLDelight drivers on a
 * disposable WAL database. This catches a different class of issues than
 * sharing one driver across writer and snapshot threads.
 *
 * Production remains in DELETE mode until all WAL safety gates pass.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherSeparateConnectionsTest {
    @Test fun readerAndWriterConnectionsPreserveCommittedRowsAcrossCheckpointAndSnapshot() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_separate_wal.db"
        val key = ByteArray(32) { 0x71.toByte() }
        val snapshot = File(context.cacheDir, "separate_wal_snapshot_test.db")
        context.deleteDatabase(name)
        snapshot.delete()
        try {
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { writer ->
                assertEquals("wal", writer.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                val repository = ProjectRepository(writer.database)
                repository.createReturningId(
                    clientId = null, title = "Committed before reader",
                    createdAt = 111L
                )

                CeilingsDatabase.openIsolatedForTesting(context, name, key).use { reader ->
                    // SQLCipher Android may switch an opened connection back to
                    // DELETE; deliberately re-enforce WAL on every connection.
                    assertEquals("wal", reader.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                    assertEquals("wal", writer.queryText("PRAGMA journal_mode")?.lowercase())

                    reader.execute("BEGIN")
                    try {
                        assertEquals(1L, reader.queryLong("SELECT count(*) FROM projects"))
                        repository.createReturningId(
                            clientId = null, title = "Committed during reader",
                            createdAt = 222L
                        )
                        // WAL readers must retain a stable snapshot even after
                        // a separate writer has committed.
                        assertEquals(1L, reader.queryLong("SELECT count(*) FROM projects"))
                        // PASSIVE checkpoint must not corrupt or discard rows
                        // retained by an active read transaction.
                        writer.checkpoint(CeilingsDatabase.WalMode.PASSIVE)
                    } finally {
                        reader.execute("ROLLBACK")
                    }

                    assertEquals(2L, reader.queryLong("SELECT count(*) FROM projects"))
                    assertEquals(2, repository.all().size)
                    assertTrue(writer.checkpoint(CeilingsDatabase.WalMode.FULL).complete)
                    writer.writeConsistentEncryptedSnapshot(snapshot)
                    PortableBackupManager.verifyEncryptedSnapshot(context, snapshot, key)
                    assertTrue(reader.checkIntegrity())
                    assertTrue(writer.checkIntegrity())
                }
            }
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { reopened ->
                assertEquals(2, ProjectRepository(reopened.database).all().size)
                assertTrue(reopened.checkIntegrity())
            }
        } finally {
            snapshot.delete()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
