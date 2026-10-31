package com.andreyceilings.app

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.db.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Observe real SQLCipher/Android WAL defaults and growth, without modifying
 * busy_timeout, wal_autocheckpoint or synchronous and without a background
 * checkpoint worker. Avoid hardcoded page-size or WAL byte-size limits.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherWalOperationalPolicyTest {
    @Test fun effectiveWalSettingsGrowthCheckpointAndColdReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sqldelight_test_wal_operational_policy.db"
        val key = ByteArray(32) { 0x49.toByte() }
        context.deleteDatabase(name)
        val totalWrites = 160
        val sizeSamples = mutableListOf<Long>()
        try {
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { db ->
                val before = db.walDiagnostics()
                assertEquals("wal", before.journalMode.lowercase())
                assertTrue("SQLite did not provide busy_timeout", before.busyTimeoutMs >= 0L)
                assertTrue("SQLite did not provide wal_autocheckpoint",
                    before.autoCheckpointPages >= 0L)
                assertTrue("Unknown synchronous setting: ${before.synchronousMode}",
                    before.synchronousMode in 0L..3L)
                assertTrue("Unusable encrypted SQLite page size",
                    before.pageSizeBytes >= 512L)
                if (before.autoCheckpointPages > 0L) {
                    assertEquals(
                        before.autoCheckpointPages * before.pageSizeBytes,
                        before.autoCheckpointApproxBytes
                    )
                }
                // A read-only diagnostic must not change the connection policy.
                assertEquals(before, db.walDiagnostics())

                val projects = ProjectRepository(db.database)
                val payload = "X".repeat(32 * 1024)
                repeat(totalWrites) { i ->
                    projects.createReturningId(
                        clientId = null,
                        title = "WAL policy sample $i " + payload,
                        createdAt = 1770000000000L + i
                    )
                    if ((i + 1) % 40 == 0) {
                        val current = db.walDiagnostics()
                        sizeSamples += current.walBytes
                        assertEquals(before.busyTimeoutMs, current.busyTimeoutMs)
                        assertEquals(before.autoCheckpointPages,
                            current.autoCheckpointPages)
                        assertEquals(before.synchronousMode,
                            current.synchronousMode)
                        assertEquals("wal", current.journalMode.lowercase())
                    }
                }
                assertEquals(totalWrites, projects.all().size)
                assertTrue("WAL never contained physical pages during writes: $sizeSamples",
                    sizeSamples.any { it > 0L })
                val beforeCheckpoint = db.walDiagnostics()
                val checkpoint = db.checkpoint(CeilingsDatabase.WalMode.PASSIVE)
                assertTrue("Idle PASSIVE checkpoint could not complete: $checkpoint",
                    checkpoint.complete)
                val afterCheckpoint = db.walDiagnostics()
                assertEquals(before.autoCheckpointPages,
                    afterCheckpoint.autoCheckpointPages)
                assertEquals(before.busyTimeoutMs,
                    afterCheckpoint.busyTimeoutMs)
                assertEquals(before.synchronousMode,
                    afterCheckpoint.synchronousMode)
                assertEquals(totalWrites, projects.all().size)
                assertTrue("Checkpoint changed encrypted DB integrity",
                    db.checkIntegrity())
                Log.i(
                    "SqlCipherWalPolicy",
                    "effective busy_timeout=${before.busyTimeoutMs} ms, " +
                        "autoCheckpoint=${before.autoCheckpointPages} pages, " +
                        "synchronous=${before.synchronousMode}, " +
                        "pageSize=${before.pageSizeBytes} bytes; " +
                        "WAL growth samples=$sizeSamples; " +
                        "PASSIVE=$checkpoint; WAL after=${afterCheckpoint.walBytes} bytes"
                )
            }
            // Physical file size may stay allocated after PASSIVE; check data,
            // not a speculative WAL shrink threshold.
            CeilingsDatabase.openIsolatedForTesting(
                context, name, key, enableWalForTesting = true
            ).use { reopened ->
                assertEquals("wal", reopened.walDiagnostics().journalMode.lowercase())
                assertEquals(totalWrites,
                    ProjectRepository(reopened.database).all().size)
                assertTrue("Cold reopen after WAL growth lost integrity",
                    reopened.checkIntegrity())
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
