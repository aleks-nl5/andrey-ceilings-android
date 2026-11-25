package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlin.test.*

class FrozenIndividualEstimateTest {
    @Test fun windowsPhysicalV18ToV19ReopenAndAreaQuantityPolicy() = kotlinx.coroutines.runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val directory = java.nio.file.Files.createTempDirectory("cipher-v19").toFile()
        val file = directory.resolve("quote.db")
        val key = ByteArray(32) { 44 }
        try {
            RoundedAreaPolicyProof.lifecycle(PriceCatalogSchema) { schema ->
                SqlCipherWindowsDriverFactory.open(file, key).also { d ->
                    try {
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                        else PrototypeDatabase(d).transaction {
                            val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                            if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                            d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
    @Test fun windowsPhysicalV17ToV18RollbackAndReopen() = kotlinx.coroutines.runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val directory = java.nio.file.Files.createTempDirectory("cipher-v18").toFile()
        val file = directory.resolve("quote.db")
        val key = ByteArray(32) { 44 }
        try {
            RevisionWorkflowMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                SqlCipherWindowsDriverFactory.open(file, key).also { d ->
                    try {
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                        else PrototypeDatabase(d).transaction {
                            val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                            if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                            d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
    @Test fun windowsPhysicalV16ToV17RollbackAndReopen() = kotlinx.coroutines.runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val directory = java.nio.file.Files.createTempDirectory("cipher-v17").toFile()
        val file = directory.resolve("quote.db")
        val key = ByteArray(32) { 44 }
        try {
            FrozenRevisionMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                SqlCipherWindowsDriverFactory.open(file, key).also { d ->
                    try {
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                        else PrototypeDatabase(d).transaction {
                            val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                            if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                            d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
    @Test fun windowsPhysicalV15ToV16RollbackAndReopen() = kotlinx.coroutines.runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val directory = java.nio.file.Files.createTempDirectory("cipher-v16").toFile()
        val file = directory.resolve("quote.db")
        val key = ByteArray(32) { 44 }
        try {
            FrozenEstimateMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                SqlCipherWindowsDriverFactory.open(file, key).also { d ->
                    try {
                        if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                        else PrototypeDatabase(d).transaction {
                            val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                            if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                            d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                        }
                    } catch (failure: Throwable) { d.close(); throw failure }
                }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
    @Test fun migrationV15RollbackReopenAndSuffix() = kotlinx.coroutines.runBlocking {
        val file = java.nio.file.Files.createTempDirectory("frozen-v16").resolve("quote.db").toFile()
        try {
            FrozenEstimateMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").also { driver ->
                    val version = driver.executeQuery(null, "PRAGMA user_version", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!) }, 0).value
                    try {
                        if (version == 0L) schema.create(driver)
                        else if (version < schema.version) schema.migrate(driver, version, schema.version)
                        driver.execute(null, "PRAGMA user_version=${schema.version}", 0)
                    } catch (failure: Throwable) { driver.close(); throw failure }
                }
            }
        } finally { file.parentFile.deleteRecursively() }
    }
    @Test fun atomicExactFrozenV1StatusRetryStalenessAndRollback() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {
            PriceCatalogSchema.create(it)
            it.execute(null, "PRAGMA foreign_keys=ON", 0)
            FrozenIndividualEstimateProof.run(it)
        }
    }

    @Test fun approvedPartialDriftRechecksRelevantOmissions() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val history = ImmutableEstimateRepository(db)
            val fixture = MeasurementPricingFixture.seed(db)
            val prices = PriceRepository(db)
            val group = PriceGroupRepository(db).create("Drift " + newRecordUuid(), 510, "шт.", QuantityMode.MANUAL)
            val id = prices.createInGroup("drift." + newRecordUuid(), "Drift work " + newRecordUuid(),
                group, 20.0, 511, unit = "шт.", quantityMode = QuantityMode.MANUAL, placementKind = PlacementKind.NONE)
            val original = checkNotNull(prices.find(id))
            val work = MeasurementElementRepository(db).createCatalogSelection(fixture.measurementUuid, original.uuid,
                ElementQuantity.Exact(1000), 512, approval = ElementApproval.APPROVED, businessRole = BusinessRole.ADDITIONAL_WORK)
            fun read() = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            fun configure(unit: String, now: Long) {
                prices.updateConfigured(checkNotNull(prices.find(id)), group, original.title, unit,
                    QuantityMode.MANUAL, PlacementKind.NONE, 20.0, now)
            }
            configure("м.п.", 513)
            val before = read()
            val saved = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture, partial = true), 2026, 514)
            val frozen = checkNotNull(history.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid)).frozen
            val omitted = frozen.omissions.single { it.sourceKey == elementKey(work.uuid) }
            assertEquals(ExclusionReason.UNRESOLVED, omitted.reason)
            assertTrue(PricingProblemCode.METADATA_DRIFT in omitted.problems)
            assertFalse(frozen.hasPricingDrift(before))
            assertFalse(frozen.hasPricingDrift(before.copy(catalog = before.catalog.copy(versionToken = "unrelated change"))))
    
            configure("шт.", 515)
            val after = read()
            assertFalse(frozen.hasMeasurementDrift(after))
            assertTrue(frozen.hasPricingDrift(after))
            // The user intentionally excluding this row is not the same as a pricing failure.
            val intentional = frozen.copy(omissions = listOf(omitted.copy(reason = ExclusionReason.USER_EXCLUDED, problems = emptyList())))
            assertFalse(intentional.hasPricingDrift(after))
            assertEquals(frozen, history.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid)?.frozen)
        }
    }
}
