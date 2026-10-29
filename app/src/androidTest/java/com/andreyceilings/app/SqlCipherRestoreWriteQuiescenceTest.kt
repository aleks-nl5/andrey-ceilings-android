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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies real production SQLDelight operations quiesce before destructive restore. */
@RunWith(AndroidJUnit4::class)
class SqlCipherRestoreWriteQuiescenceTest {
    @Test fun restoreWaitsForInFlightTransactionAndRollbackKeepsCommittedWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val current = CeilingsDatabase.get(context)
        val initialKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val baseline = PortableBackupManager.createVerifiedSnapshot(context, current)
        val baselineBytes = baseline.readBytes()
        val before = ProjectRepository(current.database).all().map { it.title }.sorted()
        val uniqueTitle = "Committed before quiescent restore"
        val inTransaction = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val restoreStarted = CountDownLatch(1)
        val restoreFinished = CountDownLatch(1)
        val writerError = AtomicReference<Throwable?>(null)
        val restoreError = AtomicReference<Throwable?>(null)

        val writer = Thread({
            try {
                val projects = ProjectRepository(current.database)
                current.database.transaction {
                    projects.createReturningId(
                        clientId = null, title = uniqueTitle, createdAt = 900001L
                    )
                    inTransaction.countDown()
                    check(allowCommit.await(20, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                writerError.set(error)
            } finally {
                inTransaction.countDown()
                writerFinished.countDown()
            }
        }, "sqlcipher-live-writer")

        val injected = IOException("simulated failure after rollback marker")
        val restoring = Thread({
            restoreStarted.countDown()
            try {
                SqlCipherRestoreManager.restore(
                    context, PortableBackupPayload.Contents(baselineBytes, initialKey)
                ) { phase ->
                    if (phase == SqlCipherRestoreManager.RestoreCheckpoint.AFTER_MARKER) {
                        throw injected
                    }
                }
            } catch (error: Throwable) {
                restoreError.set(error)
            } finally {
                restoreFinished.countDown()
            }
        }, "sqlcipher-quiescent-restore")

        try {
            writer.start()
            assertTrue("Writer transaction never started",
                inTransaction.await(15, TimeUnit.SECONDS))
            restoring.start()
            assertTrue("Restore thread never started",
                restoreStarted.await(10, TimeUnit.SECONDS))
            // The restore may prepare only after the transaction has ended.
            assertTrue("Restore closed the active driver before commit",
                !restoreFinished.await(300, TimeUnit.MILLISECONDS))
            allowCommit.countDown()
            assertTrue("Writer transaction stalled",
                writerFinished.await(20, TimeUnit.SECONDS))
            writerError.get()?.let { throw AssertionError("Writer failed", it) }
            assertTrue("Restore stalled after writer completed",
                restoreFinished.await(20, TimeUnit.SECONDS))
            assertTrue("Unexpected restore outcome: ${restoreError.get()}",
                restoreError.get() === injected)
            val recovered = CeilingsDatabase.get(context)
            assertTrue(recovered.checkIntegrity())
            val committed = ProjectRepository(recovered.database).all()
                .map { it.title }.sorted()
            assertEquals((before + uniqueTitle).sorted(), committed)
            val afterKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals(initialKey, afterKey)
            } finally {
                afterKey.fill(0)
            }
        } finally {
            allowCommit.countDown()
            writer.join(20000)
            if (restoring.isAlive) restoring.join(20000)
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(
                    context, PortableBackupPayload.Contents(baselineBytes, initialKey)
                )
            } finally {
                baseline.delete()
                baselineBytes.fill(0)
                initialKey.fill(0)
            }
        }
    }
}
