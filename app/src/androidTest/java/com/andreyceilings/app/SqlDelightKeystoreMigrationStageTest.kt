package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.SqlDelightDatabaseProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An app-owned SQLDelight session must use the existing Keystore-managed key,
 * close/reopen correctly and never share Room's live DB filename.
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightKeystoreMigrationStageTest {
    @Test
    fun stagingDatabaseUsesRealKeyManagerAndRemainsIndependentFromRoom() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val stageName = "andrey_ceilings_sqldelight_stage.db"
        val liveRoomName = "andrey_ceilings_secure.db"
        assertTrue(stageName != liveRoomName)
        context.deleteDatabase(stageName)
        try {
            SqlDelightDatabaseProvider.openStaging(context).use { stage ->
                val id = stage.clients.createReturningId("Keystore client", createdAt = 55)
                assertTrue(id > 0L)
                assertEquals(1L, stage.clients.count())
            }
            val file = context.getDatabasePath(stageName)
            assertTrue(file.isFile)
            val header = file.inputStream().use { it.readNBytes(16) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            // Deliberate close/reopen: persisted key must decrypt the same file.
            SqlDelightDatabaseProvider.openStaging(context).use { stage ->
                assertEquals("Keystore client", stage.clients.all().single().name)
                assertEquals(0L, stage.projects.count())
            }
        } finally {
            context.deleteDatabase(stageName)
        }
    }
}
