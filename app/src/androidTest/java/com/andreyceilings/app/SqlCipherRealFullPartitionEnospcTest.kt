package com.andreyceilings.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.SqlCipherRestoreManager
import com.andreyceilings.app.data.security.StorageSpaceGuard
import com.andreyceilings.shared.db.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL kernel ENOSPC from the Android emulator's /data filesystem.
 *
 * This deliberately reserves almost all unallocated data blocks using
 * posix_fallocate(2). No fake IOException and no replacement of a live DB.
 * It MUST run as a solitary, opt-in instrumentation session on a throwaway
 * CI emulator, never in connectedDebugAndroidTest or on a physical phone.
 *
 * The existing isolation marker is reused so the regular instrumentation
 * exclusion continues to cover ALL destructive proofs in a single filter.
 * Run only from ci/verify-sqlcipher-enospc.sh, after ordinary and SIGKILL tests.
 */
@RunWith(AndroidJUnit4::class)
@IsolatedProcessDeathProof
class SqlCipherRealFullPartitionEnospcTest {
    companion object {
        // Shell emergency cleanup if Android kills the process at zero free space.
        private const val FILLER = "isolated_sqlcipher_full_partition_enospc.bin"
        private const val MIB = 1024L * 1024L
    }

    @Test fun actualEnospcDuringIncomingRestoreStagePreservesWalAndKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("This test may run ONLY from the disposable CI emulator script",
            "true", args.getString("allowDisposableFullPartitionEnospc"))
        assertTrue("Refusing to exhaust storage on a physical device: ${Build.HARDWARE}",
            Build.HARDWARE.equals("ranchu", true) ||
                Build.HARDWARE.equals("goldfish", true))
        assertTrue("Refusing to run outside a debuggable test app",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

        val diskPath = context.filesDir.absolutePath
        val initialAvailable = StatFs(diskPath).availableBytes
        assertTrue("Disposable emulator must have at least 128 MiB free before test",
            initialAvailable > 128L * MIB)
        assertTrue("Unexpected emulator image; refusing to allocate over 12 GiB",
            initialAvailable < 12L * 1024L * MIB)

        val filler = File(context.filesDir, FILLER)
        assertFalse("Stale full-partition filler must be cleaned by CI first",
            filler.exists())
        val db = CeilingsDatabase.get(context)
        assertEquals("wal", db.queryText("PRAGMA journal_mode")?.lowercase())
        val projects = ProjectRepository(db.database)
        projects.createReturningId(
            clientId = null, title = "Committed WAL row before real ENOSPC",
            createdAt = 1770000000000L
        )
        val expectedProjects = projects.all().map { it.title }.sorted()
        val live = context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        val rollback = File(live.parentFile, ".ceilings_restore_rollback.db")
        assertFalse("Unexpected pending rollback before test", rollback.exists())

        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        val originalSnapshot = PortableBackupManager.createVerifiedSnapshot(context, db)
        val importedImage = originalSnapshot.readBytes()
        var availableAtFailure = -1L
        try {
            try {
                val failure = runCatching {
                SqlCipherRestoreManager.restoreWithStageOutputForTesting(
                    context, PortableBackupPayload.Contents(importedImage, key)
                ) { stage ->
                    // The restore preflight and incoming temp-file creation have
                    // ALREADY completed. Now race the free-space check with a
                    // real kernel allocation that fills the *same* /data volume.
                    exhaustDataBlocksOnDisposableEmulator(context, filler)
                    availableAtFailure = StatFs(diskPath).availableBytes
                    assertTrue("Expected less than one 8KiB staging write available, got " +
                        availableAtFailure, availableAtFailure < 8192L)

                    // Backup preflight must also reject genuinely depleted /data.
                    assertTrue("Backup preflight permitted full storage",
                        runCatching {
                            StorageSpaceGuard.requireForBackup(context, live)
                        }.isFailure)
                    FileOutputStream(stage)
                }
            }.exceptionOrNull()
            assertNotNull("Restore unexpectedly succeeded on a full /data volume", failure)
            assertTrue(
                "Expected kernel ENOSPC, not a synthetic failure. available=" +
                    availableAtFailure + " error=" + failure,
                isRealEnospc(failure)
            )
            } finally {
                // Remove the large allocation BEFORE any integrity/key assertions
                // or snapshot work. Also mirrored by a CI shell trap on crash.
                filler.delete()
            }
            assertTrue("Storage was not released after the ENOSPC test",
                StatFs(diskPath).availableBytes >= 64L * MIB)
            assertTrue("Full /data damaged committed SQLCipher WAL", db.checkIntegrity())
            assertEquals("wal", db.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue("Restore closed the live driver before staging completed",
                CeilingsDatabase.get(context) === db)
            assertEquals(expectedProjects, projects.all().map { it.title }.sorted())
            val afterKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals("Keystore changed on ENOSPC", key, afterKey)
            } finally {
                afterKey.fill(0)
            }
            val marker = DatabaseKeyManager.pendingRestoreOriginalPassphrase(context)
            try {
                assertTrue("Restore created pending key marker on ENOSPC", marker == null)
            } finally {
                marker?.fill(0)
            }
            assertFalse("Restore published rollback before complete incoming validation",
                rollback.exists())
            val leaked = live.parentFile!!.listFiles()
                ?.filter {
                    it.name.startsWith("restore_incoming_") ||
                        it.name.startsWith("restore_prepared_")
                }.orEmpty()
            assertTrue("Incomplete encrypted stages not deleted: $leaked", leaked.isEmpty())

            // Recovery after space is freed must work with the same encrypted
            // image and key, and still read the WAL-committed row.
            val freshSnapshot = PortableBackupManager.createVerifiedSnapshot(context, db)
            try {
                PortableBackupManager.verifyEncryptedSnapshot(context, freshSnapshot, key)
            } finally {
                freshSnapshot.delete()
            }
            val restored = SqlCipherRestoreManager.restore(
                context, PortableBackupPayload.Contents(importedImage, key)
            )
            assertTrue("Normal restore did not resume after ENOSPC", restored.checkIntegrity())
            assertEquals(expectedProjects,
                ProjectRepository(restored.database).all().map { it.title }.sorted())
            assertEquals("wal", restored.queryText("PRAGMA journal_mode")?.lowercase())
            CeilingsDatabase.closeAndReset()
            val reopened = CeilingsDatabase.get(context)
            assertTrue("Cold reopened SQLCipher WAL is invalid", reopened.checkIntegrity())
            assertEquals(expectedProjects,
                ProjectRepository(reopened.database).all().map { it.title }.sorted())

            // A separate full-disk proof for SQLCipher's *own WAL write path*.
            // The restore-stage ENOSPC above does not, by itself, test
            // SQLite's transaction rollback after a failing WAL append.
            val oversizeTitle = "Forbidden WAL row on full disk: " +
                "X".repeat(4 * 1024 * 1024)
            val beforeFailedWrite = ProjectRepository(reopened.database)
                .all().map { it.title }.sorted()
            try {
                exhaustDataBlocksOnDisposableEmulator(context, filler)
                assertTrue("WAL write test did not actually fill /data",
                    StatFs(diskPath).availableBytes < 8192L)
                val writeFailure = runCatching {
                    ProjectRepository(reopened.database).createReturningId(
                        clientId = null, title = oversizeTitle,
                        createdAt = 1770000000001L
                    )
                }.exceptionOrNull()
                assertNotNull("SQLCipher committed a 4 MiB WAL write on full /data",
                    writeFailure)
                assertTrue(
                    "Expected genuine SQLite FULL while /data blocks are depleted: " +
                        writeFailure, isSqliteFull(writeFailure)
                )
            } finally {
                filler.delete()
            }

            // The failed WAL transaction must not publish the new row; prior
            // committed data must survive checkpoint and fresh driver reopen.
            assertTrue("WAL full-disk failure corrupted live SQLCipher",
                reopened.checkIntegrity())
            assertEquals("SQLCipher accepted a partially committed WAL transaction",
                beforeFailedWrite,
                ProjectRepository(reopened.database).all().map { it.title }.sorted())
            assertEquals("wal", reopened.queryText("PRAGMA journal_mode")?.lowercase())
            val checkpoint = reopened.checkpoint(CeilingsDatabase.WalMode.FULL)
            assertTrue("WAL checkpoint remains busy after disk space recovery",
                checkpoint.complete)
            CeilingsDatabase.closeAndReset()
            val afterWalFailure = CeilingsDatabase.get(context)
            assertEquals("wal", afterWalFailure.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue("SQLCipher WAL failed integrity after cold reopen",
                afterWalFailure.checkIntegrity())
            assertEquals(beforeFailedWrite,
                ProjectRepository(afterWalFailure.database).all().map { it.title }.sorted())
            val newRow = "Committed after ENOSPC was cleared"
            ProjectRepository(afterWalFailure.database).createReturningId(
                clientId = null, title = newRow, createdAt = 1770000000002L
            )
            assertTrue("WAL writes did not recover when storage was freed",
                ProjectRepository(afterWalFailure.database)
                    .all().any { it.title == newRow })
            val keyAfterWal = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals("Full-disk WAL failure changed the Keystore key",
                    key, keyAfterWal)
            } finally {
                keyAfterWal.fill(0)
            }
        } finally {
            originalSnapshot.delete()
            importedImage.fill(0)
            key.fill(0)
            filler.delete()
        }
    }

    /**
     * Reserve real blocks on /data. Unlike setLength (sparse), posix_fallocate
     * consumes the filesystem's remaining usable blocks. Decreasing allocations
     * let us approach one 4KiB block without assuming a specific emulator size.
     */
    private fun exhaustDataBlocksOnDisposableEmulator(context: Context, filler: File) {
        val path = context.filesDir.absolutePath
        val block = StatFs(path).blockSizeLong.coerceAtLeast(4096L)
        var allocated = 0L
        var attempts = 0
        RandomAccessFile(filler, "rw").use { file ->
            var chunk = (StatFs(path).availableBytes - 8L * block)
                .coerceAtLeast(block)
            while (StatFs(path).availableBytes >= 8192L) {
                check(++attempts < 400) {
                    "Filesystem allocation did not approach full capacity (no ENOSPC proof)"
                }
                val available = StatFs(path).availableBytes
                val increment = minOf(chunk, available)
                try {
                    Os.posix_fallocate(file.fd, allocated, increment)
                    allocated += increment
                    // Subsequent allocation approaches exactly the remaining
                    // blocks, while a failure halves only the next request.
                    chunk = (StatFs(path).availableBytes - block)
                        .coerceAtLeast(block)
                } catch (failure: ErrnoException) {
                    check(failure.errno == OsConstants.ENOSPC && increment > block) {
                        "Cannot reserve real /data blocks: $failure"
                    }
                    chunk = (increment / 2L / block * block).coerceAtLeast(block)
                }
            }
            assertTrue("Kernel reserve did not actually consume blocks", allocated > 64L * MIB)
        }
    }

    /** SQLite maps the same kernel out-of-blocks condition to SQLITE_FULL. */
    private fun isSqliteFull(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { failure ->
            val name = failure::class.simpleName.orEmpty()
            val message = failure.message.orEmpty()
            name.contains("SQLiteFull", ignoreCase = true) ||
                message.contains("SQLITE_FULL", ignoreCase = true) ||
                message.contains("database or disk is full", ignoreCase = true)
        }

    private fun isRealEnospc(error: Throwable?): Boolean {
        return generateSequence(error) { it.cause }.any { failure ->
            (failure is ErrnoException && failure.errno == OsConstants.ENOSPC) ||
                (failure is IOException &&
                    (failure.message?.contains("ENOSPC", ignoreCase = true) == true ||
                        failure.message?.contains(
                            "No space left on device", ignoreCase = true
                        ) == true))
        }
    }
}
