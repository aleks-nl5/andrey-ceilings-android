package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.security.StorageSpaceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageSpaceGuardTest {
    private val mib = 1024L * 1024L

    @Test fun backupAndRestoreAccountForSeveralTransientEncryptedCopies() {
        assertTrue(StorageSpaceGuard.bytesForBackup(10 * mib) >= 2 * 10 * mib)
        assertTrue(StorageSpaceGuard.bytesForRestore(20 * mib, 10 * mib) >
            StorageSpaceGuard.bytesForBackup(10 * mib))
        assertTrue(StorageSpaceGuard.bytesForImportValidation(20 * mib) >
            StorageSpaceGuard.bytesForBackup(10 * mib))
    }

    @Test fun checksIncludeExactBoundaryAndRejectInsufficientSpace() {
        val needed = StorageSpaceGuard.bytesForRestore(15 * mib, 20 * mib)
        assertTrue(StorageSpaceGuard.sufficient(needed, needed))
        assertFalse(StorageSpaceGuard.sufficient(needed - 1, needed))
        assertFalse(StorageSpaceGuard.sufficient(0, needed))
    }

    @Test fun overflowNeverProducesNegativeOrPermissiveRequirement() {
        assertTrue(StorageSpaceGuard.bytesForBackup(Long.MAX_VALUE) > 0L)
        assertTrue(StorageSpaceGuard.bytesForRestore(Long.MAX_VALUE, Long.MAX_VALUE) > 0L)
        assertFalse(StorageSpaceGuard.sufficient(512 * mib,
            StorageSpaceGuard.bytesForRestore(Long.MAX_VALUE, 0L)))
    }

    @Test fun startupWarningDoesNotRequireAnOpenDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A startup warning is advisory, and the storage probe must work
        // before CeilingsDatabase.get() has opened the driver.
        StorageSpaceGuard.startupWarning(context)
    }

    @Test fun walSidecarsCountTowardsBackupRestoreAndStartupHeadroom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val main = java.io.File.createTempFile(
            "wal_space_accounting_", ".db", context.cacheDir
        )
        val wal = java.io.File(main.absolutePath + "-wal")
        val shm = java.io.File(main.absolutePath + "-shm")
        try {
            java.io.RandomAccessFile(main, "rw").use { it.setLength(6 * mib) }
            java.io.RandomAccessFile(wal, "rw").use { it.setLength(13 * mib) }
            java.io.RandomAccessFile(shm, "rw").use { it.setLength(2 * mib) }
            assertEquals(21 * mib, StorageSpaceGuard.databaseFootprintWithSidecars(main))
            assertTrue(
                StorageSpaceGuard.bytesForBackup(
                    StorageSpaceGuard.databaseFootprintWithSidecars(main)
                ) > StorageSpaceGuard.bytesForBackup(main.length())
            )
            assertTrue(
                StorageSpaceGuard.bytesForRestore(
                    4 * mib, StorageSpaceGuard.databaseFootprintWithSidecars(main)
                ) > StorageSpaceGuard.bytesForRestore(4 * mib, main.length())
            )
            assertTrue(wal.delete())
            assertEquals(8 * mib, StorageSpaceGuard.databaseFootprintWithSidecars(main))
            assertTrue(shm.delete())
            assertEquals(6 * mib, StorageSpaceGuard.databaseFootprintWithSidecars(main))
        } finally {
            wal.delete()
            shm.delete()
            main.delete()
        }
    }

    @Test fun absentDatabaseAndSidecarsHaveZeroFootprint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val absent = java.io.File(
            context.cacheDir, "sqldelight_test_nonexistent_footprint.db"
        )
        assertFalse(absent.exists())
        assertEquals(0L, StorageSpaceGuard.databaseFootprintWithSidecars(absent))
    }
}
