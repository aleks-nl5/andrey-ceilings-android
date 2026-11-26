package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.measurement.MeasurementEditStore
import com.andreyceilings.shared.measurement.MeasurementOutlineDraft

class EstimateV22MigrationTest {
    @Test fun populatedUpgradeRollsBackAndClearsOnlyPreV22Estimates() = runBlocking {
        exercise { file -> JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}") }
    }

    @Test fun windowsEncryptedUpgradeAndReopen() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return@runBlocking
        val key = ByteArray(32) { 22 }
        try {
            exercise { file -> SqlCipherWindowsDriverFactory.open(file, key) }
        } finally {
            key.fill(0)
        }
    }

    @Test fun repeatedContourChangesAfterV22UpgradePreserveIssuedDocuments() {
        for (upgrade in listOf(false, true)) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                if (upgrade) {
                    EstimateV22MigrationProof.v21Schema.create(driver)
                    EstimateV22MigrationProof.seed(driver)
                    PriceCatalogSchema.migrate(driver, 21L, 22L)
                    // Seed post-v22 individual data with the current writer using empty P6B tables.
                    PrototypeDatabase.Schema.migrate(driver, 23L, 24L)
                } else PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val quoteFixture = MeasurementPricingFixture.seed(db)
                val history = ImmutableEstimateRepository(db)
                val quote = history.saveIndividual(FrozenIndividualEstimateProof.command(db, quoteFixture), 2026, 600)
                val frozen = assertNotNull(history.exactIndividual(quote.seriesUuid, quote.initialRevisionUuid))
                val reservations = db.estimateIssuanceQueries.selectAllReservations().executeAsList()
                val fixture = PointPlacementProof.fixture(db, "Repeated contour")
                val points = PointPlacementRepository(db)
                val elements = MeasurementElementRepository(db)
                val point = points.addPoint(fixture.element.version, fixture.contourVersion, 1000, 1000, 10)
                val store = MeasurementEditStore(db) { 20 }
                val original = store.load(fixture.project, fixture.id)
                val rectangle = original.draft.outline
                val lShape = MeasurementOutlineDraft.Template("l_shaped",
                    mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2"))
                val first = store.update(original, original.draft.copy(outline = lShape), now = 30)
                assertEquals(6, first.placements.walls.size)
                assertEquals(listOf(point.uuid), first.placements.points.map { it.uuid })
                fun retiredWalls() = MeasurementElementMigrationProof.rows(driver,
                    "SELECT * FROM ceiling_walls WHERE active=0 AND measurementUuid='${fixture.element.measurementUuid}' ORDER BY uuid", 9)
                val retired = retiredWalls()
                assertEquals(4, retired.size)
                if (upgrade) {
                    assertFails { store.update(first, first.draft.copy(outline = rectangle), now = 40) }
                    assertEquals(first, store.load(fixture.project, fixture.id))
                    assertEquals(retired, retiredWalls())
                    removeEmptyConsolidatedSchema(driver)
                    driver.execute(null, "PRAGMA user_version = 22", 0)
                    openDesktopPriceCatalog(driver, true)
                    assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                    assertEquals(first, store.load(fixture.project, fixture.id))
                    assertEquals(retired, retiredWalls())
                }
                val second = store.update(first, first.draft.copy(outline = rectangle), now = 50)
                assertEquals(4, second.placements.walls.size)
                assertEquals(listOf(point.uuid), second.placements.points.map { it.uuid })
                assertTrue(retiredWalls().containsAll(retired))
                val third = store.update(second, second.draft.copy(outline = lShape), now = 60)
                assertEquals(6, third.placements.walls.size)
                points.removePoint(elements.find(fixture.element.uuid)!!.version, third.placements.points.single(), 70)
                elements.delete(elements.find(fixture.element.uuid)!!.version, 80)
                val empty = store.load(fixture.project, fixture.id)
                val fourth = store.update(empty, empty.draft.copy(outline = rectangle), now = 90)
                assertEquals(4, fourth.placements.walls.size)
                assertTrue(fourth.placements.points.isEmpty())
                assertTrue(retiredWalls().containsAll(retired))
                assertEquals(frozen, history.exactIndividual(quote.seriesUuid, quote.initialRevisionUuid))
                assertEquals(reservations, db.estimateIssuanceQueries.selectAllReservations().executeAsList())
                PriceCatalogSchema.verifyStructure(driver)
                verifyFrozenEstimateContents(driver)
            }
        }
    }

    private suspend fun exercise(driverFactory: (java.io.File) -> SqlDriver) {
        val directory = Files.createTempDirectory("estimate-v22").toFile()
        val file = directory.resolve("business.db")
        try {
            EstimateV22MigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                driverFactory(file).also { driver ->
                    try {
                        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                        PrototypeDatabase(driver).transaction {
                            val old = PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version")
                            if (old == 0L) schema.create(driver)
                            else if (old < schema.version) schema.migrate(driver, old, schema.version)
                            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                        }
                    } catch (failure: Throwable) {
                        driver.close()
                        throw failure
                    }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
