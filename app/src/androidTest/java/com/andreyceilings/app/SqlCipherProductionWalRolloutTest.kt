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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the REAL production CeilingsDatabase.get() opening path, not the
 * disposable WAL helper. The baseline image/key is restored in finally, so
 * this test never permanently changes the instrumentation app's live data.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherProductionWalRolloutTest {
    @Test fun productionWalRetainsCommittedRowsAcrossBackupRestoreAndColdReopen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val live = CeilingsDatabase.get(context)
        assertEquals("Production SQLCipher must open in WAL", "wal",
            live.queryText("PRAGMA journal_mode")?.lowercase())
        assertEquals(1L, live.queryLong("PRAGMA foreign_keys"))
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        val baseline = PortableBackupManager.createVerifiedSnapshot(context, live)
        val originalImage = baseline.readBytes()
        val previous = ProjectRepository(live.database).all().map { it.title }.sorted()
        val newTitle = "Production WAL reopen integration ${System.nanoTime()}"
        try {
            ProjectRepository(live.database).createReturningId(
                clientId = null, title = newTitle, createdAt = 882000L
            )
            assertTrue(ProjectRepository(live.database).all().any { it.title == newTitle })
            assertTrue("FULL checkpoint must complete", 
                live.checkpoint(CeilingsDatabase.WalMode.FULL).complete)

            // Snapshot with a committed WAL row must be valid and encrypted.
            val withWrite = PortableBackupManager.createVerifiedSnapshot(context, live)
            try {
                PortableBackupManager.verifyEncryptedSnapshot(context, withWrite, key)
            } finally {
                withWrite.delete()
            }

            CeilingsDatabase.closeAndReset()
            val reopened = CeilingsDatabase.get(context)
            assertEquals("WAL must persist on an ordinary cold open", "wal",
                reopened.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue(reopened.checkIntegrity())
            assertTrue(ProjectRepository(reopened.database).all().any { it.title == newTitle })

            // Real production restore removes old sidecars ONLY with the
            // driver quiesced; WAL must be re-enabled on the imported image.
            val restored = SqlCipherRestoreManager.restore(
                context, PortableBackupPayload.Contents(originalImage, key)
            )
            assertEquals("WAL must return after encrypted restore", "wal",
                restored.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue(restored.checkIntegrity())
            assertEquals(previous,
                ProjectRepository(restored.database).all().map { it.title }.sorted())
            val activeKey = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                assertArrayEquals("Encrypted restore must not replace the key",
                    key, activeKey)
            } finally {
                activeKey.fill(0)
            }
            CeilingsDatabase.closeAndReset()
            val cold = CeilingsDatabase.get(context)
            assertEquals("Second cold open reverted to DELETE", "wal",
                cold.queryText("PRAGMA journal_mode")?.lowercase())
            assertTrue(cold.checkIntegrity())
            assertEquals(previous, ProjectRepository(cold.database).all()
                .map { it.title }.sorted())
        } finally {
            // Recover any interrupted restore before returning exactly to the
            // original encrypted image, even when an assertion failed.
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(
                    context, PortableBackupPayload.Contents(originalImage, key)
                )
            } finally {
                baseline.delete()
                originalImage.fill(0)
                key.fill(0)
            }
        }
    }
}
