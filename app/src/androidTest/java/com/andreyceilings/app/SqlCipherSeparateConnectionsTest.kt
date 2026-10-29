package com.andreyceilings.app

import android.content.Context
import android.database.sqlite.SQLiteDatabaseLockedException
import android.util.Log
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

                    // The current pair of separate SupportOpenHelperFactory
                    // connections may report WAL yet still reject a writer
                    // while a second connection has an active read transaction.
                    // Emulator run 40952735193 confirmed SQLITE_BUSY after the
                    // default lock-retry window. Fail closed and retry only
                    // after releasing the reader; this is a rollout blocker.
                    // busy_timeout is a query PRAGMA: AndroidSqliteDriver
                    // execute() routes to executeUpdateDelete and rejects
                    // statements returning rows.
                    assertEquals(250L, writer.queryLong("PRAGMA busy_timeout=250"))
                    var committedDuringReader = false
                    reader.database.transaction {
                        assertEquals(1L, reader.queryLong("SELECT count(*) FROM projects"))
                        val overlap = runCatching {
                            repository.createReturningId(
                                clientId = null, title = "Committed during reader",
                                createdAt = 222L
                            )
                        }
                        if (overlap.isSuccess) {
                            committedDuringReader = true
                            // A working WAL implementation keeps the original
                            // read snapshot even after a concurrent commit.
                            assertEquals(1L, reader.queryLong("SELECT count(*) FROM projects"))
                        } else {
                            val failure = overlap.exceptionOrNull()!!
                            assertTrue("Unexpected failure during concurrent write: $failure",
                                failure is SQLiteDatabaseLockedException)
                            Log.w("SqlCipherWAL", "Independent drivers returned SQLITE_BUSY; " +
                                "production dual-driver WAL cannot be enabled", failure)
                        }
                        // PASSIVE may report incomplete work while a reader
                        // holds a transaction; it must not silently lose rows.
                        val passive = runCatching {
                            writer.checkpoint(CeilingsDatabase.WalMode.PASSIVE)
                        }
                        passive.exceptionOrNull()?.let { failure ->
                            assertTrue("Unexpected checkpoint error: $failure",
                                failure is SQLiteDatabaseLockedException)
                        }
                    }

                    // SQLDelight owns the reader transaction lifecycle and
                    // releases it before any retry; no raw BEGIN/ROLLBACK calls.
                    // A blocked write must succeed after releasing the reader;
                    // never lose or duplicate the record on retry.
                    if (!committedDuringReader) {
                        repository.createReturningId(
                            clientId = null, title = "Committed during reader",
                            createdAt = 222L
                        )
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
