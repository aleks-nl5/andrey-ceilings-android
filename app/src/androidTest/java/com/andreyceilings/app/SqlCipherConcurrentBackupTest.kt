package com.andreyceilings.app

import android.content.Context
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses a disposable SQLCipher DB and the exact snapshot helper used by backup.
 * One thread commits records while another generates/verifies encrypted images.
 * An image may reflect a partial prefix of commits, but must never contain
 * torn, duplicate or uncommitted records.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherConcurrentBackupTest {
    @Test fun concurrentCommitsAndEncryptedWalSnapshotsStayConsistent() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_concurrent_wal.db"
        val copyName = "sqldelight_test_concurrent_copy.db"
        val key = ByteArray(32) { 0x29.toByte() }
        val writeCount = 60
        val writerStarted = CountDownLatch(1)
        val writerError = AtomicReference<Throwable?>(null)
        context.deleteDatabase(name)
        context.deleteDatabase(copyName)
        try {
            CeilingsDatabase.openIsolatedForTesting(context, name, key).use { db ->
                assertEquals("wal", db.queryText("PRAGMA journal_mode=WAL")?.lowercase())
                val repo = ProjectRepository(db.database)
                val writer = Thread({
                    try {
                        writerStarted.countDown()
                        repeat(writeCount) { index ->
                            repo.createReturningId(
                                clientId = null,
                                title = "Concurrent WAL row $index",
                                createdAt = 10000L + index
                            )
                            // Make actual overlap with snapshot operations observable.
                            Thread.sleep(15)
                        }
                    } catch (failure: Throwable) {
                        writerError.set(failure)
                    }
                }, "sqlcipher-wal-test-writer")
                var overlappingSnapshots = 0
                val counts = mutableListOf<Int>()
                writer.start()
                try {
                    assertTrue("Writer did not start",
                        writerStarted.await(10, TimeUnit.SECONDS))
                    repeat(6) {
                        if (writer.isAlive) overlappingSnapshots++
                        val snapshot = File.createTempFile(
                            "wal_concurrent_", ".db", context.cacheDir
                        )
                        try {
                            assertTrue(snapshot.delete())
                            db.writeConsistentEncryptedSnapshot(snapshot)
                            PortableBackupManager.verifyEncryptedSnapshot(context, snapshot, key)
                            context.deleteDatabase(copyName)
                            snapshot.copyTo(context.getDatabasePath(copyName))
                            CeilingsDatabase.openIsolatedForTesting(
                                context, copyName, key
                            ).use { read ->
                                assertTrue(read.checkIntegrity())
                                val rows = ProjectRepository(read.database).all()
                                val n = rows.size
                                assertTrue("Snapshot has extra records: $n",
                                    n in 0..writeCount)
                                val actual = rows.map { it.title }.toSet()
                                val expected = (0 until n).map {
                                    "Concurrent WAL row $it"
                                }.toSet()
                                assertEquals("Snapshot is not a committed prefix", expected, actual)
                                counts += n
                            }
                        } finally {
                            snapshot.delete()
                            context.deleteDatabase(copyName)
                        }
                    }
                } finally {
                    writer.join(30000)
                }
                assertTrue("Writer did not finish in time", !writer.isAlive)
                assertNull("Concurrent SQLite commit failed", writerError.get())
                assertTrue("Backup did not overlap a writer", overlappingSnapshots > 0)
                assertEquals(writeCount, repo.all().size)
                assertTrue("Snapshot counts must be monotonic",
                    counts.zipWithNext().all { (a, b) -> a <= b })
                assertTrue("WAL mode was lost during concurrent snapshot",
                    db.queryText("PRAGMA journal_mode").equals("wal", ignoreCase = true))
                // Checkpoint after writes should not lose records or corrupt DB.
                assertTrue(db.checkpoint(CeilingsDatabase.WalMode.FULL).complete)
                assertTrue(db.checkIntegrity())
                assertEquals(writeCount, repo.all().size)
            }
        } finally {
            context.deleteDatabase(copyName)
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
