package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.app.data.db.*
import com.andreyceilings.app.data.security.*
import com.andreyceilings.shared.db.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*
import java.io.File

/** Exact singleton/filename/Keystore/WAL path, shared by debug and signed release instrumentation. */
@RunWith(AndroidJUnit4::class)
class ProductionDatabaseStartupTest {
    @Test fun freshProductionColdOpenCreatesListsExportsVerifiesAndRestores() = preserveProduction { context,key ->
        replaceClosedProduction(context,null)
        val db=CeilingsDatabase.get(context)
        assertEquals("wal",db.queryText("PRAGMA journal_mode"))
        assertEquals(PriceCatalogSchema.version,db.queryLong("PRAGMA user_version"))
        createAndRoundTrip(context,db)
        CeilingsDatabase.closeAndReset()
        assertTrue(CeilingsDatabase.get(context).clients().count()>0)
        val reopenedKey=DatabaseKeyManager.getOrCreatePassphrase(context)
        try { assertTrue(key.contentEquals(reopenedKey),"Keystore key changed") } finally { reopenedKey.fill(0) }
    }

    @Test fun populatedProductionV9AndDeployedV10ColdUpgradeKeepMixedHistoryAndCanCreateAndRestore() = preserveProduction { context,key ->
        for(version in listOf(9L,10L)) {
            val name="production-upgrade-$version.db";context.deleteDatabase(name)
            try {
                val before=historical(context,name,key,if(version==9L)WallAnchorMigrationProof.v9Schema else WallAnchorMigrationProof.v10Schema).use { driver ->
                    LegacyWallSeedProof.populate(driver,version==9L)
                    driver.executeQuery(null,"PRAGMA wal_autocheckpoint=0",{c->c.next();QueryResult.Value(c.getLong(0))},0).value
                    ProjectRepository(PrototypeDatabase(driver)).createReturningId(null,"Committed v$version WAL row",createdAt=600)
                    assertEquals("wal",driver.executeQuery(null,"PRAGMA journal_mode",{ c->c.next();QueryResult.Value(c.getString(0)) },0).value)
                    LegacyWallSeedProof.snapshot(driver)
                }
                replaceClosedProduction(context,context.getDatabasePath(name))
                val upgraded=CeilingsDatabase.get(context)
                assertEquals(context.getDatabasePath(CeilingsDatabase.DATABASE_NAME).absolutePath,upgraded.path)
                assertEquals(PriceCatalogSchema.version,upgraded.queryLong("PRAGMA user_version"))
                assertEquals(before,LegacyWallSeedProof.snapshot(upgraded.driver))
                assertEquals(0L,upgraded.queryLong("SELECT count(*) FROM ceiling_walls WHERE measurementUuid LIKE 'legacy-source-%'"))
                assertEquals(0L,upgraded.queryLong("SELECT count(*) FROM point_placements WHERE mountingJson!='{}'"))
                val reopenedKey=DatabaseKeyManager.getOrCreatePassphrase(context)
                try { assertTrue(key.contentEquals(reopenedKey),"Keystore key changed") } finally { reopenedKey.fill(0) }
                createAndRoundTrip(context,upgraded)
                CeilingsDatabase.closeAndReset()
                assertTrue(CeilingsDatabase.get(context).checkIntegrity())
            } finally {context.deleteDatabase(name)}
        }
    }

    @Test fun nullSessionVerifyAndExplicitRecoveryPreserveOldFilesAndKeyAtEveryFailurePoint() = preserveProduction { context,key ->
        val sourceName="null-session-source.db";context.deleteDatabase(sourceName)
        val incomingKey=ByteArray(32){88}
        createEncryptedPrototypeDriver(context,incomingKey,sourceName,initializePriceCatalog=true).use { driver ->
            ProjectRepository(PrototypeDatabase(driver)).createReturningId(null,"Incoming recovery project",createdAt=800)
        }
        val contents=PortableBackupPayload.Contents(context.getDatabasePath(sourceName).readBytes(),incomingKey)
        val payload=PortableBackupPayload.encode(contents.database,contents.passphrase)
        val envelope=try { PortableBackupEnvelope.seal(payload,"recovery-test".toCharArray()) }finally{payload.fill(0)}
        val unavailableSource="unavailable-encrypted-source.db";context.deleteDatabase(unavailableSource)
        val unavailableKey=ByteArray(32){89}
        createEncryptedPrototypeDriver(context,unavailableKey,unavailableSource,initializePriceCatalog=true).use { driver ->
            ProjectRepository(PrototypeDatabase(driver)).createReturningId(null,"Opaque original project",createdAt=801)
        }
        val originalEncryptedImage=context.getDatabasePath(unavailableSource).readBytes()
        val live=context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        try {
            for(checkpoint in SqlCipherRestoreManager.RestoreCheckpoint.entries) {
                replaceClosedProduction(context,null)
                // A REAL encrypted image with a different key is unavailable to the production
                // session. Opaque sidecars additionally prove exact closed-file preservation.
                val originals=listOf("","-wal","-shm","-journal").associateWith { suffix ->
                    (if(suffix.isEmpty())originalEncryptedImage else ByteArray(4096+suffix.length) { i -> (i*31+suffix.length).toByte() })
                        .also {File(live.path+suffix).writeBytes(it)}
                }
                assertFails { CeilingsDatabase.get(context) }
                assertContentEquals(originalEncryptedImage,live.readBytes(),"Wrong-key open must not reset/delete the original")
                // A failed SQLCipher open may legitimately inspect/recover files. Preserve the
                // exact CLOSED state at the explicit recovery boundary, not a stale earlier copy.
                val closed=originals.keys.associateWith { suffix -> File(live.path+suffix).takeIf {it.exists()}?.readBytes() }
                val verified=PortableBackupManager.validate(context,envelope,"recovery-test".toCharArray())
                verified.database.fill(0);verified.passphrase.fill(0)
                assertFalse(CeilingsDatabase.hasOpenSession())
                assertFails { SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=false) }
                assertFails { SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=true) {
                    if(it==checkpoint) throw java.io.IOException("Injected recovery interruption")
                } }
                assertFalse(CeilingsDatabase.hasOpenSession())
                closed.forEach { (suffix,bytes) ->
                    val file=File(live.path+suffix)
                    if(bytes==null)assertFalse(file.exists()) else assertContentEquals(bytes,file.readBytes(),"Original $suffix changed at $checkpoint")
                }
                val current=DatabaseKeyManager.getOrCreatePassphrase(context)
                try {assertTrue(key.contentEquals(current),"Original key was not restored")}finally{current.fill(0)}
                assertNull(DatabaseKeyManager.pendingRawBundle(context))
            }
            val originalFiles=listOf("","-wal","-shm","-journal").associateWith { suffix ->File(live.path+suffix).takeIf {it.exists()}?.readBytes()}
            CeilingsDatabase.withExclusiveRestoreLock {
                val root=RawDatabaseRecovery.root(context)
                val parked=File(context.noBackupFilesDir,"recovery-test-parked-${java.util.UUID.randomUUID()}")
                check(root.renameTo(parked))
                try {
                    root.writeText("Not a recovery directory")
                    assertFails {SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=true)}
                    assertNull(DatabaseKeyManager.pendingRawBundle(context))
                    originalFiles.forEach { (suffix,bytes) ->
                        if(bytes==null)assertFalse(File(live.path+suffix).exists()) else assertTrue(bytes.contentEquals(File(live.path+suffix).readBytes()),"A failed archive write changed original files")
                    }
                } finally {check(root.delete());check(parked.renameTo(root))}
            }
            var manifest:File?=null;var originalManifest:ByteArray?=null
            assertFails { SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=true) {
                if(it==SqlCipherRestoreManager.RestoreCheckpoint.AFTER_OLD_FILES_REMOVED) {
                    manifest=File(File(RawDatabaseRecovery.root(context),DatabaseKeyManager.pendingRawBundle(context)!!),"manifest")
                    originalManifest=manifest!!.readBytes();manifest!!.writeText("format=1\ndatabase.present=false\n")
                    throw java.io.IOException("Injected damaged recovery metadata")
                }
            } }
            assertNotNull(DatabaseKeyManager.pendingRawBundle(context))
            assertFails {CeilingsDatabase.get(context)}
            assertFalse(live.exists(),"Failed recovery must not silently create an empty database")
            java.io.FileOutputStream(manifest!!).use {it.write(originalManifest!!);it.fd.sync()}
            CeilingsDatabase.withExclusiveRestoreLock { SqlCipherRestoreManager.recoverIfNeeded(context) }
            originalFiles.forEach { (suffix,bytes) ->
                if(bytes==null)assertFalse(File(live.path+suffix).exists()) else assertContentEquals(bytes,File(live.path+suffix).readBytes())
            }
            val restored=SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=true)
            assertTrue(restored.checkIntegrity());assertEquals("wal",restored.queryText("PRAGMA journal_mode"))
            val installedKey=DatabaseKeyManager.getOrCreatePassphrase(context)
            try {assertTrue(incomingKey.contentEquals(installedKey),"Incoming key was not installed")}finally{installedKey.fill(0)}
            assertTrue(RawDatabaseRecovery.root(context).listFiles().orEmpty().any {it.name.startsWith("unavailable-")})
        } finally {context.deleteDatabase(sourceName);context.deleteDatabase(unavailableSource);unavailableKey.fill(0)
            originalEncryptedImage.fill(0);envelope.fill(0);contents.database.fill(0);contents.passphrase.fill(0)}
    }

    @Test fun missingWrappedKeyRefusesReplacementAndNeverGeneratesANewKeyOverExistingFiles() = preserveProduction { context,key ->
        val prefs=context.getSharedPreferences("secure_db_key_v1",Context.MODE_PRIVATE)
        val wrapped=DatabaseKeyManager.currentWrappedForRecovery(context)
        val snapshot=PortableBackupManager.createVerifiedSnapshot(context,CeilingsDatabase.get(context))
        val contents=PortableBackupPayload.Contents(snapshot.readBytes(),key.copyOf())
        CeilingsDatabase.closeAndReset()
        val live=context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        val original=live.readBytes()
        try {
            assertTrue(prefs.edit().remove("wrapped_passphrase").commit())
            val failure=assertFails {CeilingsDatabase.get(context)}
            assertEquals("DB-KEY",DatabaseStartupDiagnostics.report(failure).code)
            assertFails {SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=true)}
            assertTrue(prefs.getString("wrapped_passphrase",null)==null,"A missing key must not be silently replaced")
            assertTrue(original.contentEquals(live.readBytes()),"Existing encrypted bytes changed")
            assertNull(DatabaseKeyManager.pendingRawBundle(context))
        } finally {
            check(prefs.edit().putString("wrapped_passphrase",wrapped).commit())
            snapshot.delete();original.fill(0);contents.database.fill(0);contents.passphrase.fill(0)
        }
    }

    @Test fun physicalCipherCorruptionFailsWithoutDeletingOrRecreatingTheDatabase() = preserveProduction { context,_ ->
        CeilingsDatabase.closeAndReset()
        val live=context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        val corrupted=live.readBytes()
        check(corrupted.size>128)
        corrupted[64]=(corrupted[64].toInt() xor 0x55).toByte()
        java.io.FileOutputStream(live).use{it.write(corrupted);it.fd.sync()}
        val failure=assertFails {CeilingsDatabase.get(context)}
        assertTrue(DatabaseStartupDiagnostics.report(failure).code.startsWith("DB-"))
        assertFalse(CeilingsDatabase.hasOpenSession())
        assertTrue(live.isFile && corrupted.contentEquals(live.readBytes()),"A corruption handler deleted or recreated the encrypted original")
        corrupted.fill(0)
    }

    private suspend fun createAndRoundTrip(context:Context,db:CeilingsDatabase) {
        val id=db.clients().insert(ClientEntity(name="Production client"));assertTrue(id>0)
        assertTrue(ProjectRepository(db.database).createReturningId(id,"Production project",createdAt=700)>0)
        val expected=db.clients().count()
        val envelope=PortableBackupManager.create(context,db,"production-round-trip".toCharArray())
        try {
            val contents=PortableBackupManager.validate(context,envelope,"production-round-trip".toCharArray())
            try {assertEquals(expected,SqlCipherRestoreManager.restore(context,contents).clients().count())}
            finally{contents.database.fill(0);contents.passphrase.fill(0)}
        } finally {envelope.fill(0)}
    }

    private fun historical(context:Context,name:String,key:ByteArray,schema:SqlSchema<QueryResult.Value<Unit>>) =
        AndroidSqliteDriver(schema,context,name,factory=SupportOpenHelperFactory(key.copyOf(),null,true))

    private fun replaceClosedProduction(context:Context,source:File?) = CeilingsDatabase.withExclusiveRestoreLock {
        CeilingsDatabase.closeAndReset()
        // Test-owned data only. The preserved production fixture is restored in finally.
        context.deleteDatabase(CeilingsDatabase.DATABASE_NAME)
        source?.copyTo(context.getDatabasePath(CeilingsDatabase.DATABASE_NAME))
    }

    private fun preserveProduction(test:suspend (Context,ByteArray)->Unit) = kotlinx.coroutines.runBlocking {
        val context:Context=ApplicationProvider.getApplicationContext()
        val key=DatabaseKeyManager.getOrCreatePassphrase(context)
        val saved=PortableBackupManager.createVerifiedSnapshot(context,CeilingsDatabase.get(context))
        val bytes=saved.readBytes()
        val oldBundles=RawDatabaseRecovery.root(context).listFiles().orEmpty().map{it.name}.toSet()
        var primary:Throwable?=null
        var baselineRestored=false
        try {test(context,key)}catch(failure:Throwable){primary=failure;throw failure}
        finally {
            try {
                CeilingsDatabase.closeAndReset();SqlCipherRestoreManager.recoverIfNeeded(context)
                SqlCipherRestoreManager.restoreUnavailable(context,PortableBackupPayload.Contents(bytes,key),confirmed=true)
                baselineRestored=true
            } catch(cleanup:Throwable){if(primary!=null)primary.addSuppressed(cleanup)else throw cleanup}
            finally {
                val pending=DatabaseKeyManager.pendingRawBundle(context)
                RawDatabaseRecovery.root(context).listFiles().orEmpty().filter{it.name !in oldBundles && it.name!=pending}.forEach{it.deleteRecursively()}
                if(baselineRestored)saved.delete()
                bytes.fill(0);key.fill(0)
            }
        }
    }
}
