package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.BusinessChangeTracker
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
                val observer = object : BusinessChangeTracker.Observer("clients") {
                    override fun onInvalidated(tables: Set<String>) {
                        updates.incrementAndGet()
                    }
                }
                database.invalidationTracker.addObserver(observer)
                try {
                    val id = database.clients().insert(ClientEntity(name = "Backup client"))
                    assertTrue(id > 0L)
                    assertEquals(1, database.clients().count())
                    assertTrue("SQLDelight insert must invalidate the clients query", updates.get() > 0)
                } finally {
                    database.invalidationTracker.removeObserver(observer)
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
