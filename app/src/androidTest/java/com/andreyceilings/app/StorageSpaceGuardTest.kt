package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.security.StorageSpaceGuard
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
}
