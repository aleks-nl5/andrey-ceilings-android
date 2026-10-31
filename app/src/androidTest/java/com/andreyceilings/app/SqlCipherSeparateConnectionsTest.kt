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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises two independent encrypted Android SQLDelight drivers on a
 * disposable WAL database. This catches a different class of issues than
 * sharing one driver across writer and snapshot threads. The first test
 * also exercises contention between two SQLDelight transactions (both
 * reserve the writer slot), rather than treating them as read-only cursors.
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
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { writer ->
                assertEquals("wal", writer.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                val repository = ProjectRepository(writer.database)
                repository.createReturningId(
                    clientId = null, title = "Committed before reader",
                    createdAt = 111L
                )

                CeilingsDatabase.openIsolatedForTesting(
                    context, name, key, enableWalForTesting = true
                ).use { reader ->
                    // Both open helpers explicitly enabled WAL before opening:
                    // the journal_mode PRAGMA by itself is not sufficient.
                    assertEquals("wal", reader.queryText("PRAGMA journal_mode")?.lowercase())
                    assertEquals("wal", writer.queryText("PRAGMA journal_mode")?.lowercase())

                    // SQLDelight Android transaction() uses
                    // beginTransactionNonExclusive() = BEGIN IMMEDIATE.
                    // Although the body reads only, it reserves the only
                    // write slot. SQLITE_BUSY here is EXPECTED contention,
                    // not evidence that concurrent WAL readers are broken.
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
                            // If a driver revision allows this overlap, the
                            // transaction must still keep its original view.
                            assertEquals(1L, reader.queryLong("SELECT count(*) FROM projects"))
                        } else {
                            val failure = overlap.exceptionOrNull()!!
                            assertTrue("Unexpected failure during concurrent write: $failure",
                                failure is SQLiteDatabaseLockedException)
                            Log.i("SqlCipherWAL", "Expected IMMEDIATE transaction " +
                                "writer contention; retry after the transaction", failure)
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
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { reopened ->
                assertEquals(2, ProjectRepository(reopened.database).all().size)
                assertTrue(reopened.checkIntegrity())
            }
        } finally {
            snapshot.delete()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    @Test fun independentWalReaderAndWriterOverlapWithoutHoldingWriteTransactions() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_wal_two_connections.db"
        val key = ByteArray(32) { 0x73.toByte() }
        val commits = 25
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val writeFailure = AtomicReference<Throwable?>(null)
        context.deleteDatabase(name)
        try {
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { writer ->
                CeilingsDatabase.openIsolatedForTesting(
                    context, name, key, enableWalForTesting = true
                ).use { reader ->
                    // Verify that SupportOpenHelperFactory enabled WAL on
                    // actual open, before any explicit PRAGMA mode setter.
                    assertEquals("wal", reader.queryText("PRAGMA journal_mode")?.lowercase())
                    assertEquals("wal", writer.queryText("PRAGMA journal_mode")?.lowercase())
                    val writerThread = Thread({
                        try {
                            val projects = ProjectRepository(writer.database)
                            repeat(commits) { i ->
                                projects.createReturningId(
                                    clientId = null, title = "Independent row $i",
                                    createdAt = 100L + i
                                )
                                if (i == 0) started.countDown()
                                Thread.sleep(12)
                            }
                        } catch (error: Throwable) {
                            writeFailure.set(error)
                        } finally {
                            started.countDown()
                            completed.countDown()
                        }
                    }, "sqlcipher-independent-wal-writer")
                    writerThread.start()
                    try {
                        assertTrue("Writer never committed its first row",
                            started.await(15, TimeUnit.SECONDS))
                        var prior = 0L
                        var readDuringWriter = 0
                        repeat(30) {
                            if (completed.count > 0L) readDuringWriter++
                            val count = reader.queryLong("SELECT count(*) FROM projects")
                                ?: error("Reader returned no count")
                            assertTrue("Reader saw uncommitted or duplicate rows: $count",
                                count in prior..commits.toLong())
                            prior = count
                            Thread.sleep(8)
                        }
                        assertTrue("No overlapping reads occurred", readDuringWriter > 0)
                        assertTrue("Independent writer stalled",
                            completed.await(20, TimeUnit.SECONDS))
                        writeFailure.get()?.let { throw AssertionError(
                            "Independent WAL writer failed", it
                        ) }
                        assertEquals(commits.toLong(),
                            reader.queryLong("SELECT count(*) FROM projects"))
                        assertEquals(commits, ProjectRepository(writer.database).all().size)
                        assertTrue(writer.checkpoint(CeilingsDatabase.WalMode.FULL).complete)
                        assertTrue(reader.checkIntegrity())
                    } finally {
                        writerThread.join(20000)
                        check(!writerThread.isAlive) {
                            "Concurrent writer remained active while closing SQLCipher drivers"
                        }
                    }
                }
            }
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { reopened ->
                assertEquals("wal", reopened.queryText("PRAGMA journal_mode")?.lowercase())
                assertEquals(commits, ProjectRepository(reopened.database).all().size)
                assertTrue(reopened.checkIntegrity())
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    /**
     * Previous tests exercised concurrent snapshots from a single driver,
     * and independent WAL readers/writers without snapshots. This combines
     * both: a writer commits on one SQLCipher connection while a second
     * WAL-aware connection produces encrypted VACUUM INTO snapshots.
     *
     * Every verified snapshot must represent a committed prefix, and the
     * original DB must preserve all rows after FULL checkpoint + cold reopen.
     */
    @Test fun independentWalWriterAndSnapshotReaderPreserveCommittedPrefixes() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_independent_wal_snapshots.db"
        val copyName = "sqldelight_test_independent_wal_snapshot_copy.db"
        val key = ByteArray(32) { 0x76.toByte() }
        val writeCount = 48
        val snapshotCount = 8
        val writerStarted = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val writerError = AtomicReference<Throwable?>(null)
        val recordedCounts = mutableListOf<Int>()
        var overlappingSnapshots = 0
        context.deleteDatabase(name)
        context.deleteDatabase(copyName)
        try {
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { writer ->
                CeilingsDatabase.openIsolatedForTesting(
                    context, name, key, enableWalForTesting = true
                ).use { snapshotReader ->
                    assertEquals("wal", writer.queryText("PRAGMA journal_mode")?.lowercase())
                    assertEquals("wal", snapshotReader.queryText("PRAGMA journal_mode")?.lowercase())
                    assertEquals(3000L, writer.queryLong("PRAGMA busy_timeout=3000"))
                    assertEquals(3000L, snapshotReader.queryLong("PRAGMA busy_timeout=3000"))

                    val thread = Thread({
                        try {
                            val projects = ProjectRepository(writer.database)
                            repeat(writeCount) { index ->
                                projects.createReturningId(
                                    clientId = null,
                                    title = "Parallel WAL snapshot row $index",
                                    createdAt = 10000L + index
                                )
                                if (index == 0) writerStarted.countDown()
                                Thread.sleep(18)
                            }
                        } catch (error: Throwable) {
                            writerError.set(error)
                        } finally {
                            writerStarted.countDown()
                            writerDone.countDown()
                        }
                    }, "sqlcipher-wal-independent-snapshot-writer")
                    thread.start()
                    try {
                        assertTrue("Independent writer did not start",
                            writerStarted.await(15, TimeUnit.SECONDS))
                        repeat(snapshotCount) { imageIndex ->
                            if (writerDone.count > 0L) overlappingSnapshots++
                            val snapshot = File.createTempFile(
                                "wal_independent_snapshot_$imageIndex", ".db", context.cacheDir
                            )
                            try {
                                assertTrue(snapshot.delete())
                                snapshotReader.writeConsistentEncryptedSnapshot(snapshot)
                                PortableBackupManager.verifyEncryptedSnapshot(
                                    context, snapshot, key
                                )
                                context.deleteDatabase(copyName)
                                snapshot.copyTo(context.getDatabasePath(copyName))
                                CeilingsDatabase.openIsolatedForTesting(
                                    context, copyName, key, enableWalForTesting = true
                                ).use { reopenedCopy ->
                                    assertEquals("wal",
                                        reopenedCopy.queryText("PRAGMA journal_mode")?.lowercase())
                                    assertTrue(reopenedCopy.checkIntegrity())
                                    val rows = ProjectRepository(reopenedCopy.database).all()
                                    val actual = rows.map { it.title }.toSet()
                                    val prefix = (0 until rows.size).map {
                                        "Parallel WAL snapshot row $it"
                                    }.toSet()
                                    assertEquals(
                                        "Snapshot #$imageIndex is not a committed prefix",
                                        prefix, actual
                                    )
                                    assertTrue(rows.size in 1..writeCount)
                                    recordedCounts += rows.size
                                }
                            } finally {
                                snapshot.delete()
                                context.deleteDatabase(copyName)
                            }
                        }
                    } finally {
                        thread.join(30000)
                    }
                    assertTrue("Independent WAL writer stalled", writerDone.count == 0L)
                    assertTrue("Independent WAL writer thread was not stopped", !thread.isAlive)
                    writerError.get()?.let { throw AssertionError("WAL writer failed", it) }
                    assertTrue("No snapshots overlapped live WAL commits",
                        overlappingSnapshots > 0)
                    assertTrue("Snapshots moved backwards in committed history",
                        recordedCounts.zipWithNext().all { (earlier, later) ->
                            earlier <= later
                        })
                    assertEquals(writeCount,
                        ProjectRepository(snapshotReader.database).all().size)
                    assertTrue("FULL checkpoint did not finish",
                        writer.checkpoint(CeilingsDatabase.WalMode.FULL).complete)
                    assertTrue(writer.checkIntegrity())
                    assertTrue(snapshotReader.checkIntegrity())
                }
            }
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { reopened ->
                assertEquals("wal", reopened.queryText("PRAGMA journal_mode")?.lowercase())
                assertEquals(writeCount, ProjectRepository(reopened.database).all().size)
                assertTrue(reopened.checkIntegrity())
            }
        } finally {
            context.deleteDatabase(copyName)
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
