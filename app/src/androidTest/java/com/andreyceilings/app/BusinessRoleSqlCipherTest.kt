package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.*
import com.andreyceilings.shared.livepricing.*
import kotlin.test.*

class BusinessRoleSqlCipherTest {
    @Test fun physicalV14ToV15RollbackReopenRolesAndPricing(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name = "business-roles-v15.db"
        val key = ByteArray(32) { 42 }
        context.deleteDatabase(name)
        try {
            BusinessRoleMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                val d = AndroidSqliteDriver(schema, context, name, factory = SupportOpenHelperFactory(key.copyOf(), null, true))
                try {
                    d.executeQuery(null, "SELECT count(*) FROM sqlite_master", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)) }, 0).value
                    d
                } catch (t: Throwable) { d.close(); throw t }
            }
        } finally { key.fill(0); context.deleteDatabase(name) }
    }

    @Test fun portableBackupRestoreRetainsTypedRolesAndCoherentTotals(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalKey = DatabaseKeyManager.getOrCreatePassphrase(context)
        val snapshot = PortableBackupManager.createVerifiedSnapshot(context, CeilingsDatabase.get(context))
        val original = snapshot.readBytes()
        val key = ByteArray(32) { 43 }
        val name = "business-roles-portable.db"
        val copy = "business-roles-portable-copy.db"
        val password = "role-backup-fixture".toCharArray()
        context.deleteDatabase(name); context.deleteDatabase(copy)
        try {
            val fixture = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true,
                initializePriceCatalog = true).use { d ->
                val fixture = LivePricingProof.seed(PrototypeDatabase(d))
                val destination = context.getDatabasePath(copy).absolutePath.replace("'", "''")
                d.execute(null, "VACUUM INTO '$destination'", 0)
                fixture
            }
            val source = context.getDatabasePath(copy).readBytes()
            val payload = PortableBackupPayload.encode(source, key)
            val envelope = try { PortableBackupEnvelope.seal(payload, password) } finally { payload.fill(0); source.fill(0) }
            val contents = try { PortableBackupManager.validate(context, envelope, password) } finally { envelope.fill(0) }
            try {
                val restored = SqlCipherRestoreManager.restore(context, contents)
                val reader = LivePricingReader(restored.database)
                val result = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
                assertEquals(BusinessRole.CANVAS, result.source.elements.first { it.uuid == fixture.elementUuid }.businessRole)
                assertEquals(BusinessRole.PRIMARY_PROFILE, result.source.elements.first { it.uuid == fixture.profileUuid }.businessRole)
                assertEquals(12000, result.buildPreview().confirmedTotalCents)
                assertTrue(result.buildPreview().canFinalizeFull)
                reader.closeReads()
                CeilingsDatabase.closeAndReset()
                val restarted = CeilingsDatabase.get(context)
                val afterRestart = LivePricingReader(restarted.database)
                assertEquals(result.buildPreview(), assertIs<PricingReadResult.Loaded>(afterRestart.read(fixture.projectId, fixture.measurementUuid)).buildPreview())
                afterRestart.closeReads()
                assertEquals(0, restarted.queryLong("SELECT count(*) FROM estimate_series"))
            } finally { contents.database.fill(0); contents.passphrase.fill(0) }
        } finally {
            try {
                CeilingsDatabase.closeAndReset()
                SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restore(context, PortableBackupPayload.Contents(original, originalKey))
            } finally {
                snapshot.delete(); context.deleteDatabase(name); context.deleteDatabase(copy)
                original.fill(0); originalKey.fill(0); key.fill(0); password.fill('\u0000')
            }
        }
    }
}
