package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.FrozenIndividualEstimateProof
import com.andreyceilings.shared.db.MeasurementPricingFixture
import com.andreyceilings.shared.db.PriceCatalogSchema
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.SqlCipherWindowsDriverFactory
import com.andreyceilings.shared.db.openDesktopPriceCatalog
import com.andreyceilings.shared.measurement.ElementApproval
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
}
