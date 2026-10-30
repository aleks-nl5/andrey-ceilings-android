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
 * Non-destructive, OS-generated ENOSPC proof on the Android emulator.
 *
 * /dev/full is a Linux character device which fails actual write(2) calls
 * with ENOSPC. Redirecting only the incoming staging *stream* to this device
 * tests the real restore failure path after a successful StatFs check without
 * exhausting emulator flash storage or ever replacing the live SQLCipher DB.
 * It is NOT a proof of a physically filled Android data partition.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherKernelEnospcTest {
    @Test fun kernelEnospcDuringIncomingStageLeavesLiveDatabaseAndKeyUntouched() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val full = File("/dev/full")
        assertTrue("Android emulator has no /dev/full ENOSPC device", full.exists())

        // Prove the kernel actually reports ENOSPC; permission errors and
        // arbitrary IOException must not be counted as a disk-full proof.
        val kernelError = runCatching {
            FileOutputStream(full).use { fd ->
                fd.write(ByteArray(4096) { 0x3f })
                fd.fd.sync()
            }
        }.exceptionOrNull()
        assertTrue("Expected OS ENOSPC, got: $kernelError",
            isKernelNoSpaceError(kernelError))

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
                ) { _ ->
                    FileOutputStream(full)
                }
            }.exceptionOrNull()
            assertTrue("Restore did not propagate kernel ENOSPC: $failure",
                isKernelNoSpaceError(failure))

            // No marker/key mutation or driver closure is allowed before a
            // fully persisted and verified incoming SQLCipher image exists.
            assertTrue("Live SQLCipher database failed integrity_check",
                database.checkIntegrity())
            assertTrue("Production driver was replaced despite failed staging",
                CeilingsDatabase.get(context) === database)
            assertEquals("Committed projects were changed by ENOSPC",
                before, ProjectRepository(database.database).all().map { it.title }.sorted())
            val nowKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals("Keystore passphrase changed despite ENOSPC", key, nowKey)
            } finally {
                nowKey.fill(0)
            }
            val pending = DatabaseKeyManager.pendingRestoreOriginalPassphrase(context)
            try {
                assertTrue("A false pending-restore marker was written", pending == null)
            } finally {
                pending?.fill(0)
            }
            assertFalse("An unnecessary rollback file was created", rollback.exists())
            val leaked = live.parentFile?.listFiles()
                ?.filter { it.name.startsWith("restore_incoming_") ||
                    it.name.startsWith("restore_prepared_") }
                .orEmpty()
            assertTrue("Partial restore staging files remain after ENOSPC: $leaked",
                leaked.isEmpty())

            // Backup must still be available after the failing write.
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

    private fun isKernelNoSpaceError(failure: Throwable?): Boolean {
        if (failure !is IOException) return false
        val reason = generateSequence(failure as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return reason.contains("ENOSPC", ignoreCase = true) ||
            reason.contains("No space left on device", ignoreCase = true)
    }
}
