package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class BusinessRoleJdbcTest {
    @Test fun productionRolesReadWriteAndReprice(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { d -> PriceCatalogSchema.create(d); BusinessRoleProof.run(d) }
    }
    @Test fun v14RollbackReopenAndFullSchema(): Unit = runBlocking { fileProof(false) }
    @Test fun windowsPhysicalCipherV14RollbackReopen(): Unit = runBlocking {
        if (System.getProperty("os.name").startsWith("Windows", true)) fileProof(true)
    }
    private suspend fun fileProof(cipher: Boolean) {
        val dir = Files.createTempDirectory("business-roles-").toFile()
        val file = dir.resolve("roles.db")
        val key = ByteArray(32) { 42 }
        try {
            BusinessRoleMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                val d = if (cipher) SqlCipherWindowsDriverFactory.open(file, key) else JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
                try {
                    if (schema === PriceCatalogSchema) openDesktopPriceCatalog(d, file.length() > 0)
                    else PrototypeDatabase(d).transaction {
                        val old = PriceNameUniquenessProof.scalar(d, "PRAGMA user_version")
                        if (old == 0L) schema.create(d) else if (old < schema.version) schema.migrate(d, old, schema.version)
                        d.execute(null, "PRAGMA user_version=${schema.version}", 0)
                    }
                    d
                } catch (t: Throwable) { d.close(); throw t }
            }
            val copy = dir.resolve("portable.db")
            fun openFile(file: java.io.File): SqlDriver = if (cipher) SqlCipherWindowsDriverFactory.open(file, key)
                else JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            val evidence = openFile(file).use { d -> BusinessRolePortableProof.snapshot(d, copy.absolutePath) }
            openFile(copy).use { d -> BusinessRolePortableProof.verify(d, evidence) }
        } finally { key.fill(0); dir.deleteRecursively() }
    }
    @Test fun malformedRoleSchemaFailsBeforeMigrationAndMissingIndexFailsAudit() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { d ->
            BusinessRoleMigrationProof.v14Schema.create(d)
            d.execute(null, "ALTER TABLE measurement_elements ADD COLUMN businessRole TEXT", 0)
            val before = PriceNameMigrationProof.schema(d)
            assertFails { PriceCatalogSchema.migrate(d, 14, 15) }
            assertEquals(before, PriceNameMigrationProof.schema(d))
        }
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { d ->
            PriceCatalogSchema.create(d)
            d.execute(null, "DROP INDEX index_measurement_elements_canvas", 0)
            assertFails { PriceCatalogSchema.verifyStructure(d) }
        }
    }

    @Test fun competingConnectionsCannotCreateTwoCanvases() {
        val dir = Files.createTempDirectory("role-race-").toFile()
        val file = dir.resolve("roles.db")
        val pool = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { d ->
                PriceCatalogSchema.create(d)
                val db = PrototypeDatabase(d)
                val f = LivePricingProof.seed(db)
                val repo = MeasurementElementRepository(db)
                val canvas = repo.find(f.elementUuid)!!
                repo.edit(canvas.version, canvas.note, canvas.approval, canvas.quantity, 500, businessRole = BusinessRole.ADDITIONAL_WORK)
                val start = CountDownLatch(1)
                val tasks = (1..2).map { index -> pool.submit<Boolean> {
                    JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { concurrent ->
                        concurrent.execute(null, "PRAGMA busy_timeout=10000", 0)
                        val other = MeasurementElementRepository(PrototypeDatabase(concurrent))
                        start.await()
                        try {
                            other.createCatalogSelection(f.measurementUuid, PriceRepository(db).find(f.priceId)!!.uuid,
                                canvas.quantity, 600, uuid = "racing-canvas-$index", businessRole = BusinessRole.CANVAS)
                            true
                        } catch (_: MeasurementElementFailure.DuplicateRole) { false }
                    }
                } }
                start.countDown()
                assertEquals(1, tasks.count { it.get(20, TimeUnit.SECONDS) })
                assertEquals(1, repo.forMeasurement(f.measurementUuid).count { it.businessRole == BusinessRole.CANVAS })
            }
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
    }
    @Test fun observerRefreshesOnRolesPricesAndGeometryAndCloseClearsSuccess(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { d ->
            PriceCatalogSchema.create(d)
            val db = PrototypeDatabase(d)
            val f = LivePricingProof.seed(db)
            val row = MeasurementRepository(db).findByUuid(f.measurementUuid)!!
            val store = MeasurementElementsStore(db, { 1000 }, Dispatchers.Default)
            val model = store.model(ElementRoute(f.projectId, row.id, row.uuid))
            suspend fun preview(test: (LiveEstimatePreview) -> Boolean) = withTimeout(10000) {
                model.pricing.first { it is MeasurementPricingState.Loaded && test(it.preview) }
            }
            try {
                preview { it.confirmedTotalCents == 12000L && it.canFinalizeFull }
                val prices = PriceRepository(db)
                val price = prices.find(f.priceId)!!
                prices.updateSalePrice(price, 20.0, 500)
                preview { it.confirmedTotalCents == 24000L }
                val repo = MeasurementElementRepository(db)
                val canvas = repo.find(f.elementUuid)!!
                repo.edit(canvas.version, "", canvas.approval, canvas.quantity, 1001, businessRole = BusinessRole.ADDITIONAL_WORK)
                preview { !it.canFinalizeFull && it.mandatoryProblems.any { p -> p.code == PricingProblemCode.MISSING_MANDATORY_CANVAS } }
            } finally { store.closeAndAwaitWrites() }
            assertIs<MeasurementPricingState.Unavailable>(model.pricing.value)
        }
    }
}
