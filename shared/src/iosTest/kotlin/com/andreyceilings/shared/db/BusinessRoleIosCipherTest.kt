package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.use
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.*

class BusinessRoleIosCipherTest {
    @Test fun physicalV22PurgeRollsBackAndReopens(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "estimate-v22-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 22 } },
            recovery = SqlCipherRecovery.Fail)
        try { EstimateV22MigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) } }
        finally { factory.deleteDatabase(name) }
    }

    @Test fun physicalV19ToV20RollbackAndWallRepair(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "walls-v19-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 46 } },
            recovery = SqlCipherRecovery.Fail)
        try { WallVersionMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) } }
        finally { factory.deleteDatabase(name) }
    }

    @Test fun exactFrozenDocumentOnSqlCipher(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "frozen-pdf-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 0x38 } },
            recovery = SqlCipherRecovery.Fail)
        try { factory.create(IosPriceCatalogSchema, config).use { proveExactEstimateDocuments(it) } }
        finally { factory.deleteDatabase(name) }
    }

    @Test fun physicalV18ToV19ReopenAndAreaQuantityPolicy(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "frozen-v19-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 44 } }, recovery = SqlCipherRecovery.Fail)
        try { RoundedAreaPolicyProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) } }
        finally { factory.deleteDatabase(name) }
    }
    @Test fun physicalV17ToV18RollbackReopenAndFrozenRevisions(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "frozen-v18-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 44 } }, recovery = SqlCipherRecovery.Fail)
        try { RevisionWorkflowMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) } }
        finally { factory.deleteDatabase(name) }
    }
    @Test fun physicalV16ToV17RollbackReopenAndFrozenRevisions(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "frozen-v17-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 44 } }, recovery = SqlCipherRecovery.Fail)
        try { FrozenRevisionMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) } }
        finally { factory.deleteDatabase(name) }
    }
    @Test fun physicalV15ToV16RollbackReopenAndFrozenV1(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "frozen-v16-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 44 } }, recovery = SqlCipherRecovery.Fail)
        try { FrozenEstimateMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) } }
        finally { factory.deleteDatabase(name) }
    }
    @Test fun currentEncryptedConsolidatedSnapshotKeepsTombstonesAndExactAmounts(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "consolidated-v24-${Random.nextLong()}.db"
        val copy = "$name.copy.db"
        val key = SqlCipherKey { ByteArray(32) { 24 } }
        val config = SqlCipherConfig(name = name, key = key, recovery = SqlCipherRecovery.Fail)
        try {
            val (revision, payable) = factory.create(IosPriceCatalogSchema, config).use { driver ->
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val project = assertNotNull(ProjectRepository(db).find(fixture.projectId))
                val individuals = ImmutableEstimateRepository(db)
                val issued = individuals.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
                val individual = assertNotNull(individuals.exactIndividual(issued.seriesUuid, issued.initialRevisionUuid))
                val ref = ConsolidatedDocumentRef(fixture.measurementUuid, individual.seriesUuid,
                    individual.revisionUuid, individual.frozen.consolidatedSourceProof())
                val command = ConsolidatedSave(newRecordUuid(), project.id, project.uuid, null, null,
                    ConsolidatedDraft("Consolidated", ConsolidatedDiscount(QuoteDiscountMode.FIXED, 101), listOf(ref)))
                val repository = ConsolidatedEstimateRepository(db)
                val stored = repository.save(command, LocalDate(2026, 11, 26), 601)
                assertTrue(individuals.deleteIndividualSeries(individual.seriesUuid, mapOf(individual.revisionUuid to 1L), 602))
                verifyFrozenEstimateContents(driver)
                val path = driver.executeQuery(null, "PRAGMA database_list", { cursor ->
                    cursor.next()
                    QueryResult.Value(checkNotNull(cursor.getString(2)))
                }, 0).value
                val destination = "$path.copy.db".replace("'", "''")
                driver.execute(null, "VACUUM INTO '$destination'", 0)
                stored.revisionUuid to individual.frozen.amounts.finalCents - 101L
            }
            factory.create(IosPriceCatalogSchema, SqlCipherConfig(name = copy, key = key, recovery = SqlCipherRecovery.Fail)).use { restored ->
                PriceCatalogSchema.verifyStructure(restored)
                verifyFrozenEstimateContents(restored)
                val saved = assertNotNull(ConsolidatedEstimateRepository(PrototypeDatabase(restored)).read(revision))
                assertEquals(payable, saved.document().totalCents)
                assertEquals(1, saved.document().sections.size)
            }
        } finally {
            factory.deleteDatabase(name)
            factory.deleteDatabase(copy)
        }
    }

    @Test fun currentEncryptedRolePortableSnapshot(): Unit = runBlocking { roleSnapshot(false) }

    @kotlin.test.Ignore
    @Test fun physicalV14ToV15RollbackReopenRolesAndPricing(): Unit = runBlocking { roleSnapshot(true) }

    private suspend fun roleSnapshot(historical: Boolean) {
        val factory = NativeSqlCipherDriverFactory()
        val name = "business-roles-v15-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 42 } }, recovery = SqlCipherRecovery.Fail)
        val copy = "$name.copy.db"
        try {
            if (historical) BusinessRoleMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) }
            val evidence = factory.create(IosPriceCatalogSchema, config).use { driver ->
                val path = driver.executeQuery(null, "PRAGMA database_list", { cursor ->
                    cursor.next(); app.cash.sqldelight.db.QueryResult.Value(checkNotNull(cursor.getString(2)))
                }, 0).value
                BusinessRolePortableProof.snapshot(driver, "$path.copy.db")
            }
            val restored = SqlCipherConfig(name = copy, key = SqlCipherKey { ByteArray(32) { 42 } }, recovery = SqlCipherRecovery.Fail)
            factory.create(IosPriceCatalogSchema, restored).use { driver -> BusinessRolePortableProof.verify(driver, evidence) }
        } finally { factory.deleteDatabase(name); factory.deleteDatabase(copy) }
    }
}
