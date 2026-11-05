package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.app.data.db.*
import com.andreyceilings.app.data.security.*
import com.andreyceilings.shared.db.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.*

/** CI invokes each phase separately. Never runs in the normal JUnit suite. */
@RunWith(AndroidJUnit4::class)
@IsolatedProcessDeathProof
class ProductionDatabaseProcessRestartTest {
    @Test fun prepareRealV9WalAndKillProcess() {
        val context:Context=ApplicationProvider.getApplicationContext()
        CeilingsDatabase.closeAndReset()
        context.deleteDatabase(CeilingsDatabase.DATABASE_NAME)
        val key=DatabaseKeyManager.getOrCreatePassphrase(context)
        val driver=AndroidSqliteDriver(WallAnchorMigrationProof.v9Schema,context,CeilingsDatabase.DATABASE_NAME,
            factory=SupportOpenHelperFactory(key.copyOf(),null,true))
        try {
            LegacyWallSeedProof.populate(driver)
            driver.executeQuery(null,"PRAGMA wal_autocheckpoint=0",{c->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))},0).value
            driver.executeQuery(null,"PRAGMA wal_checkpoint(TRUNCATE)",{c->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))},0).value
            ProjectRepository(PrototypeDatabase(driver)).createReturningId(null,"Process WAL sentinel",createdAt=900)
            val live=context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
            check(File(live.path+"-wal").length()>32)
            check(PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version")==9L)
            val marker=File(context.cacheDir,"production_v9_wal_ready")
            java.io.FileOutputStream(marker).use {it.write("READY_V9_WAL".toByteArray());it.fd.sync()}
            // Deliberately no driver.close(): committed frames must survive a real SIGKILL.
            android.os.Process.killProcess(android.os.Process.myPid())
            error("SIGKILL returned")
        } finally { key.fill(0) }
    }

    @Test fun newProcessOpensProductionV9WalAndRoundTripsBackup() = kotlinx.coroutines.runBlocking {
        val context:Context=ApplicationProvider.getApplicationContext()
        val db=CeilingsDatabase.get(context)
        assertEquals("wal",db.queryText("PRAGMA journal_mode"))
        assertEquals(PriceCatalogSchema.version,db.queryLong("PRAGMA user_version"))
        assertTrue(ProjectRepository(db.database).all().any{it.title=="Process WAL sentinel"})
        assertTrue(db.queryLong("SELECT count(*) FROM estimate_revisions")!!>0)
        val point=PointPlacementRepository(db.database).find("v9-point")!!
        assertEquals(1500L,point.xMm);assertEquals(1200L,point.yMm);assertEquals(20L,point.updatedAt)
        assertEquals(com.andreyceilings.shared.measurement.PointPrecision.EXACT,point.precision)
        assertNull(point.mounting.anchor)
        assertNotNull(db.queryText("SELECT uuid FROM point_position_operations WHERE uuid='v9-move'"))
        assertEquals(1L,db.queryLong("SELECT count(*) FROM point_placements WHERE uuid='v9-deleted' AND deletedAt=22"))
        LegacyWallSeedProof.unsupportedDrawings.forEachIndexed { i,source ->
            assertEquals(source,MeasurementRepository(db.database).findByUuid("legacy-source-$i")!!.drawingJson)
        }
        val id=db.clients().insert(ClientEntity(name="After real process restart"));assertTrue(id>0)
        ProjectRepository(db.database).createReturningId(id,"After real process restart",createdAt=901)
        val envelope=PortableBackupManager.create(context,db,"process-round-trip".toCharArray())
        try {
            val contents=PortableBackupManager.validate(context,envelope,"process-round-trip".toCharArray())
            try { assertTrue(SqlCipherRestoreManager.restore(context,contents).checkIntegrity()) }
            finally{contents.database.fill(0);contents.passphrase.fill(0)}
        } finally {envelope.fill(0)}
        File(context.cacheDir,"production_v9_wal_ready").delete()
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("production_upgrade","PRODUCTION_V9_WAL_UPGRADE_OK")})
    }

    @Test fun rawRecoveryAfterKeyReplacementAndKillProcess() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val db=CeilingsDatabase.get(context)
        ProjectRepository(db.database).createReturningId(null,"Original before raw recovery SIGKILL",createdAt=950)
        val key=DatabaseKeyManager.getOrCreatePassphrase(context)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key).joinToString(""){"%02x".format(it)}
        key.fill(0)
        java.io.FileOutputStream(File(context.cacheDir,"raw_original_key_hash")).use {it.write(hash.toByteArray());it.fd.sync()}
        val name="raw-crash-import.db";context.deleteDatabase(name)
        val incomingKey=ByteArray(32){90}
        createEncryptedPrototypeDriver(context,incomingKey,name,initializePriceCatalog=true).use { driver ->
            ProjectRepository(PrototypeDatabase(driver)).createReturningId(null,"Raw imported row must not survive",createdAt=951)
        }
        val contents=PortableBackupPayload.Contents(context.getDatabasePath(name).readBytes(),incomingKey)
        CeilingsDatabase.closeAndReset()
        SqlCipherRestoreManager.restoreUnavailable(context,contents,confirmed=true) { checkpoint ->
            if(checkpoint==SqlCipherRestoreManager.RestoreCheckpoint.AFTER_KEY_REPLACED) {
                check(DatabaseKeyManager.pendingRawBundle(context)!=null)
                java.io.FileOutputStream(File(context.cacheDir,"production_raw_restore_ready")).use {it.write("READY_RAW_AFTER_KEY".toByteArray());it.fd.sync()}
                android.os.Process.killProcess(android.os.Process.myPid())
                Runtime.getRuntime().halt(92)
            }
        }
        error("Raw restore did not reach SIGKILL")
    }

    @Test fun newProcessRecoversRawOriginalFilesAndKeystoreKey() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val expectedHash=File(context.cacheDir,"raw_original_key_hash").readText()
        check(DatabaseKeyManager.pendingRawBundle(context)!=null)
        val db=CeilingsDatabase.get(context)
        assertTrue(db.checkIntegrity());assertEquals("wal",db.queryText("PRAGMA journal_mode"))
        val titles=ProjectRepository(db.database).all().map{it.title}
        assertTrue("Original before raw recovery SIGKILL" in titles)
        assertFalse("Raw imported row must not survive" in titles)
        val key=DatabaseKeyManager.getOrCreatePassphrase(context)
        try {assertEquals(expectedHash,java.security.MessageDigest.getInstance("SHA-256").digest(key).joinToString(""){"%02x".format(it)})}
        finally {key.fill(0)}
        assertNull(DatabaseKeyManager.pendingRawBundle(context))
        context.deleteDatabase("raw-crash-import.db")
        File(context.cacheDir,"production_raw_restore_ready").delete();File(context.cacheDir,"raw_original_key_hash").delete()
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("raw_recovery","RAW_RECOVERY_PROCESS_RESTART_OK")})
    }
}
