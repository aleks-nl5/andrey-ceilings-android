package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.SqlDelightChangeTracker
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.ClientEntity
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.app.data.security.PortableBackupManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the actual Android data adapters and portable backup path
 * without opening or modifying the user's live database.
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightProductionAdaptersTest {
    @Test
    fun editsNotifyBackupListenersAndEncryptedPortableBackupValidates() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val filename = "sqldelight_test_backups.db"
        context.deleteDatabase(filename)
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        val password = "strong-test-backup-password".toCharArray()
        try {
            CeilingsDatabase.openIsolatedForTesting(context, filename, key).use { database ->
                val updates = AtomicInteger()
                val observer = object : SqlDelightChangeTracker.Observer("clients") {
                    override fun onInvalidated(tables: Set<String>) {
                        updates.incrementAndGet()
                    }
                }
                database.changeTracker.addObserver(observer)
                try {
                    val id = database.clients().insert(ClientEntity(name = "Backup client"))
                    assertTrue(id > 0L)
                    assertEquals(1, database.clients().count())
                    assertTrue("SQLDelight insert must invalidate the clients query", updates.get() > 0)
                } finally {
                    database.changeTracker.removeObserver(observer)
                }
                val envelope = PortableBackupManager.create(context, database, password)
                try {
                    val verified = PortableBackupManager.validate(context, envelope, password)
                    try {
                        assertTrue(verified.database.size > 16)
                        assertTrue(!verified.database.copyOfRange(0, 16).contentEquals(
                            "SQLite format 3\u0000".toByteArray()
                        ))
                    } finally {
                        verified.database.fill(0)
                        verified.passphrase.fill(0)
                    }
                    assertTrue(runCatching {
                        PortableBackupManager.validate(context, envelope, "wrong-password".toCharArray())
                    }.isFailure)
                    // Truncation and corruption must fail without replacing or altering
                    // the active encrypted database.
                    val truncated = envelope.copyOf(envelope.size / 2)
                    try {
                        assertTrue(runCatching {
                            PortableBackupManager.validate(context, truncated, password)
                        }.isFailure)
                    } finally { truncated.fill(0) }
                    val corrupted = envelope.copyOf()
                    try {
                        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
                        assertTrue(runCatching {
                            PortableBackupManager.validate(context, corrupted, password)
                        }.isFailure)
                    } finally { corrupted.fill(0) }
                    assertEquals(1, database.clients().count())
                    assertTrue(database.checkIntegrity())

                    // A .stpb snapshot must remain at the old state after new edits.
                    database.clients().insert(ClientEntity(name = "Written after backup"))
                    assertEquals(2, database.clients().count())
                    val oldSnapshot = PortableBackupManager.validate(context, envelope, password)
                    val restoredName = "sqldelight_test_backups_restored.db"
                    context.deleteDatabase(restoredName)
                    try {
                        context.getDatabasePath(restoredName).writeBytes(oldSnapshot.database)
                        CeilingsDatabase.openIsolatedForTesting(context, restoredName, oldSnapshot.passphrase).use { restored ->
                            assertEquals(1, restored.clients().count())
                            assertTrue(restored.checkIntegrity())
                        }
                    } finally {
                        oldSnapshot.database.fill(0)
                        oldSnapshot.passphrase.fill(0)
                        context.deleteDatabase(restoredName)
                    }
                } finally {
                    envelope.fill(0)
                }
            }
        } finally {
            context.deleteDatabase(filename)
            key.fill(0)
            password.fill('\u0000')
        }
    }
}
