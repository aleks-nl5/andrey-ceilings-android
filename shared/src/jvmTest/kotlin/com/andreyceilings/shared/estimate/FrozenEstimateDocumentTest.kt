package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.FrozenIndividualEstimateProof
import com.andreyceilings.shared.db.FrozenRevisionProof
import com.andreyceilings.shared.db.MeasurementPricingFixture
import com.andreyceilings.shared.db.PriceCatalogSchema
import com.andreyceilings.shared.db.PriceGroupRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.SqlCipherWindowsDriverFactory
import com.andreyceilings.shared.db.openDesktopPriceCatalog
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.measurement.ElementApproval
import com.andreyceilings.shared.measurement.ElementQuantity
import com.andreyceilings.shared.db.MeasurementElementRepository
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class FrozenEstimateDocumentTest {
    @Test fun threeExactVersionsCanBeRegeneratedAfterLiveSourceChanges() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            proveExactEstimateDocuments(driver)
        }
    }

    @Test fun frozenDocumentOnWindowsSqlCipher() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val directory = Files.createTempDirectory("frozen-pdf-cipher").toFile()
        val key = ByteArray(32) { 0x38 }
        try {
            SqlCipherWindowsDriverFactory.open(directory.resolve("quote.db"), key).use { driver ->
                openDesktopPriceCatalog(driver, false)
                proveExactEstimateDocuments(driver)
            }
        } finally {
            key.fill(0)
            directory.deleteRecursively()
        }
    }

    @Test fun omissionsUseFrozenLabelsAndFileNameOmitsPathSeparators() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            MeasurementElementRepository(db).createWish(fixture.measurementUuid, "Необов’язкове освітлення", 500,
                approval = ElementApproval.APPROVED)
            val history = ImmutableEstimateRepository(db)
            history.configureNumberPolicy(EstimateNumberPolicy("{year}/{sequence}/{suffix}", "КШ", 5, "ІП"))
            val saved = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture, partial = true), 2026, 501)
            val store = MeasurementElementsStore(db, { 510 })
            try {
                val document = assertNotNull(store.estimateDocument(saved.seriesUuid, saved.initialRevisionUuid))
                assertTrue(document.publicNumber.contains('/'))
                assertTrue(document.fileName.endsWith("-v1.pdf"))
                assertFalse(document.fileName.contains('/'))
                assertFalse(document.fileName.contains(saved.initialRevisionUuid))
                assertTrue(document.omissions.single().startsWith("Необов’язкове освітлення · "))
            } finally {
                store.closeAndAwaitWrites()
            }
        }
    }

    @Test fun individualZeroIsValidAndOnlyTheProjectSumOverflows() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val first = MeasurementPricingFixture.seed(db)
            val second = MeasurementPricingFixture.seed(db, first.projectId)
            val history = ImmutableEstimateRepository(db)
            val group = PriceGroupRepository(db).create("Large quotes", 110, "шт.")
            val prices = PriceRepository(db)
            val price = assertNotNull(prices.find(prices.createInGroup("large.one", "Large item", group, 999_999_999.0, 111)))
            val elements = MeasurementElementRepository(db)
            val quotes = listOf(first, second).mapIndexed { index, room ->
                elements.createCatalogSelection(room.measurementUuid, price.uuid, ElementQuantity.Exact(60_000_000_000L),
                    120, approval = ElementApproval.APPROVED, businessRole = BusinessRole.ADDITIONAL_WORK)
                history.saveIndividual(FrozenIndividualEstimateProof.command(db, room, discount = "0"), 2026, 150L + index)
            }
            val free = FrozenRevisionProof.withLegacyAlternative(driver, listOf(quotes.first().seriesUuid), first.measurementUuid) {
                history.saveIndividual(FrozenIndividualEstimateProof.command(db, first, discount = "100"), 2026, 200)
            }
            val store = MeasurementElementsStore(db, { 300 })
            try {
                val catalog = store.consolidatedRooms(first.projectId)
                val options = catalog.rooms.flatMap { it.revisions }
                val large = options.filter { it.ref.seriesUuid != free.seriesUuid }.map { it.ref }
                val zero = options.single { it.ref.seriesUuid == free.seriesUuid }.ref
                assertEquals(ConsolidatedDocumentResult.Rejected(ConsolidatedDocumentProblem.OVERFLOW),
                    store.consolidatedDocument(first.projectId, catalog.projectUuid, large))
                val result = assertIs<ConsolidatedDocumentResult.Ready>(
                    store.consolidatedDocument(first.projectId, catalog.projectUuid, listOf(zero))
                )
                assertEquals(0L, result.document.totalCents)
                assertTrue(result.document.sections.single().document.amounts.subtotalCents > Long.MAX_VALUE / 2)
            } finally {
                store.closeAndAwaitWrites()
            }
        }
    }

}
