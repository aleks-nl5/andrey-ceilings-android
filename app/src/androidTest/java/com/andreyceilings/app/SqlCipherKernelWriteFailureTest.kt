package com.andreyceilings.app

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupManager
import com.andreyceilings.app.data.security.PortableBackupPayload
import com.andreyceilings.app.data.security.SqlCipherRestoreManager
import com.andreyceilings.shared.db.ProjectRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real kernel write(2) failure through a pipe with no readers. The Android
 * sandbox denies /dev/full with EACCES, so it cannot prove a true ENOSPC on
 * standard CI emulators. EPIPE tests the very same staging stream's handling
 * of an unexpected OS write error without filling the emulator filesystem.
 *
 * This is NOT a genuine Android /data exhaustion test, and the separate
 * deterministic ENOSPC fault-injection regressions remain required.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherKernelWriteFailureTest {
    @Test fun kernelBrokenPipeDuringIncomingStageLeavesDatabaseAndKeyUntouched() {
        val context: Context = ApplicationProvider.getApplicationContext()

        // A real, app-accessible kernel write error. The read end is closed
        // before writing, so the OS returns EPIPE (not a synthetic exception).
        val directFailure = runCatching {
            brokenPipeOutput().use { stream ->
                stream.write(ByteArray(4096) { 0x3f })
            }
        }.exceptionOrNull()
        assertTrue("Expected kernel EPIPE from a readerless pipe, got: $directFailure",
            isBrokenPipe(directFailure))

        val database = CeilingsDatabase.get(context)
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        val snapshot = PortableBackupManager.createVerifiedSnapshot(context, database)
        val image = snapshot.readBytes()
        val before = ProjectRepository(database.database).all().map { it.title }.sorted()
        val live = context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        val rollback = File(live.parentFile, ".ceilings_restore_rollback.db")
        try {
            assertFalse("An earlier rollback is still pending", rollback.exists())
            val failure = runCatching {
                SqlCipherRestoreManager.restoreWithStageOutputForTesting(
                    context, PortableBackupPayload.Contents(image, key)
                ) { _ -> brokenPipeOutput() }
            }.exceptionOrNull()
            assertTrue("Restore did not propagate kernel EPIPE: $failure",
                isBrokenPipe(failure))

            // Failure must happen during incoming staging, before any
            // production driver closure, Keystore swap or recovery marker.
            assertTrue("Live SQLCipher database failed integrity_check",
                database.checkIntegrity())
            assertTrue("Live driver was replaced after a failed stage",
                CeilingsDatabase.get(context) === database)
            assertEquals("Committed projects changed after EPIPE",
                before, ProjectRepository(database.database).all().map { it.title }.sorted())
            val nowKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals("Keystore passphrase changed after EPIPE", key, nowKey)
            } finally {
                nowKey.fill(0)
            }
            val pending = DatabaseKeyManager.pendingRestoreOriginalPassphrase(context)
            try {
                assertTrue("Pending-restore marker was written on EPIPE", pending == null)
            } finally {
                pending?.fill(0)
            }
            assertFalse("Rollback was created before incoming validation", rollback.exists())
            val leaked = live.parentFile?.listFiles()
                ?.filter { it.name.startsWith("restore_incoming_") ||
                    it.name.startsWith("restore_prepared_") }
                .orEmpty()
            assertTrue("Partial restore files leaked on EPIPE: $leaked", leaked.isEmpty())
            val next = PortableBackupManager.createVerifiedSnapshot(context, database)
            try {
                PortableBackupManager.verifyEncryptedSnapshot(context, next, key)
            } finally {
                next.delete()
            }
        } finally {
            snapshot.delete()
            image.fill(0)
            key.fill(0)
        }
    }

    /**
     * Only OS descriptors created inside this test are used. No access to
     * privileged device nodes, external storage or users' files is required.
     */
    private fun brokenPipeOutput(): FileOutputStream {
        val ends = Os.pipe()
        try {
            Os.close(ends[0])
        } catch (failure: Throwable) {
            Os.close(ends[1])
            throw failure
        }
        return FileOutputStream(ends[1])
    }

    private fun isBrokenPipe(failure: Throwable?): Boolean {
        if (failure !is IOException) return false
        val descriptions = generateSequence(failure as Throwable?) { it.cause }
            .mapNotNull { it.message }.joinToString(" ")
        return descriptions.contains("EPIPE", ignoreCase = true) ||
            descriptions.contains("Broken pipe", ignoreCase = true)
    }
}
