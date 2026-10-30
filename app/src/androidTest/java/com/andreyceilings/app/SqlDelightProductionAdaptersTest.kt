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

    /**
     * Actual Android DAO adapters and SQLDelight listeners running on the
     * WAL-aware SQLCipher driver, not just raw SQLite statements. Covers
     * price/estimate CRUD, portable encrypted backup and two cold opens.
     * Uses ONLY disposable sqldelight_test_ names; production remains DELETE.
     */
    @Test
    fun walAdaptersAndEncryptedPortableBackupSurviveReopenWithoutRepricing() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val liveName = "sqldelight_test_wal_android_adapters.db"
        val copyName = "sqldelight_test_wal_android_backup_copy.db"
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        val password = "isolated-wal-android-adapters-2026".toCharArray()
        var backup: ByteArray? = null
        context.deleteDatabase(liveName)
        context.deleteDatabase(copyName)
        try {
            CeilingsDatabase.openIsolatedForTesting(
                context, liveName, key, enableWalForTesting = true
            ).use { database ->
                assertEquals("wal", database.queryText("PRAGMA journal_mode")?.lowercase())
                val updates = AtomicInteger()
                val observer = object : SqlDelightChangeTracker.Observer(
                    "clients", "projects", "price_items", "estimates"
                ) {
                    override fun onInvalidated(tables: Set<String>) {
                        updates.incrementAndGet()
                    }
                }
                database.changeTracker.addObserver(observer)
                try {
                    val clientId = database.clients().insert(
                        ClientEntity(name = "WAL backup customer")
                    )
                    val projectId = com.andreyceilings.shared.db.ProjectRepository(
                        database.database
                    ).createReturningId(
                        clientId = clientId, title = "WAL quote", createdAt = 101L
                    )
                    val priceId = com.andreyceilings.shared.db.PriceRepository(
                        database.database
                    ).upsertReturningId(
                        code = "test.wal.canvas", title = "WAL canvas",
                        category = "canvas", unit = "m2",
                        price = 150.0, updatedAt = 102L
                    )
                    val estimateId = com.andreyceilings.shared.db.EstimateRepository(
                        database.database
                    ).createReturningId(
                        projectId, "WAL immutable amount", "PVC", "standard", "simple",
                        2.0, 3.0, 6.0, 10.0,
                        900.0, 60.0, 0.0, 0.0, 960.0,
                        createdAt = 103L
                    )
                    assertTrue(clientId > 0L && projectId > 0L)
                    assertTrue(priceId > 0L && estimateId > 0L)
                    assertTrue("SQLDelight change observers did not fire in WAL",
                        updates.get() >= 4)
                } finally {
                    database.changeTracker.removeObserver(observer)
                }

                backup = PortableBackupManager.create(context, database, password)
                assertTrue(database.checkIntegrity())
                database.clients().insert(ClientEntity(name = "Only after snapshot"))
                val prices = com.andreyceilings.shared.db.PriceRepository(database.database)
                val row = prices.all().single()
                prices.upsertReturningId(
                    id = row.id, code = row.code, title = "Repriced after backup",
                    category = row.category, unit = row.unit,
                    price = 250.0, updatedAt = 104L
                )
                assertTrue(database.checkpoint(CeilingsDatabase.WalMode.FULL).complete)
                assertEquals(2, database.clients().count())
                assertEquals(960.0,
                    com.andreyceilings.shared.db.EstimateRepository(database.database)
                        .all().single().totalCost, 0.0)
            }

            CeilingsDatabase.openIsolatedForTesting(
                context, liveName, key, enableWalForTesting = true
            ).use { reopened ->
                assertEquals("wal", reopened.queryText("PRAGMA journal_mode")?.lowercase())
                assertEquals(2, reopened.clients().count())
                assertEquals("Repriced after backup",
                    com.andreyceilings.shared.db.PriceRepository(reopened.database)
                        .all().single().title)
                assertEquals(960.0,
                    com.andreyceilings.shared.db.EstimateRepository(reopened.database)
                        .all().single().totalCost, 0.0)
                assertTrue(reopened.checkIntegrity())
            }

            val archive = requireNotNull(backup)
            val content = PortableBackupManager.validate(context, archive, password)
            try {
                context.getDatabasePath(copyName).writeBytes(content.database)
                CeilingsDatabase.openIsolatedForTesting(
                    context, copyName, content.passphrase, enableWalForTesting = true
                ).use { restored ->
                    assertEquals("wal",
                        restored.queryText("PRAGMA journal_mode")?.lowercase())
                    assertEquals(1, restored.clients().count())
                    assertEquals("WAL backup customer",
                        restored.clients().all().single().name)
                    val price = com.andreyceilings.shared.db.PriceRepository(restored.database)
                        .all().single()
                    assertEquals("WAL canvas", price.title)
                    assertEquals(150.0, price.price, 0.0)
                    assertEquals(960.0,
                        com.andreyceilings.shared.db.EstimateRepository(restored.database)
                            .all().single().totalCost, 0.0)
                    assertTrue(restored.checkIntegrity())
                }
            } finally {
                content.database.fill(0)
                content.passphrase.fill(0)
            }
            // The portable archive must remain independently valid even after
            // the original WAL DB has been closed and repriced.
            assertTrue(runCatching {
                PortableBackupManager.validate(
                    context, archive, "wrong-wal-password".toCharArray()
                )
            }.isFailure)
        } finally {
            backup?.fill(0)
            context.deleteDatabase(copyName)
            context.deleteDatabase(liveName)
            key.fill(0)
            password.fill('\u0000')
        }
    }
}
