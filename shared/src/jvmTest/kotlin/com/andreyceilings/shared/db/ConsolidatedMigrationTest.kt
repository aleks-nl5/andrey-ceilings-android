package com.andreyceilings.shared.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.*

class ConsolidatedMigrationTest {
    @Test fun populatedV23MigrationRollsBackAndPreservesIndividualAndProjectData() =
        migrationAndReopen { file -> JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}") }

    @Test fun encryptedWindowsV23MigrationAndConsolidatedAudit() {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return
        val key = ByteArray(32) { 24 }
        try {
            migrationAndReopen { file -> SqlCipherWindowsDriverFactory.open(file, key) }
        } finally {
            key.fill(0)
        }
    }

    private fun migrationAndReopen(open: (java.io.File) -> SqlDriver) {
        val directory = Files.createTempDirectory("consolidated-migration").toFile()
        val file = directory.resolve("business.db")
        try {
            val (fixture, prior, payload) = open(file).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val repository = ImmutableEstimateRepository(db)
                val saved = repository.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 700)
                val payload = assertNotNull(repository.revision(saved.initialRevisionUuid)).detailsJson
                removeEmptyConsolidatedSchema(driver)
                driver.execute(null, "PRAGMA user_version = 23", 0)
                PriceCatalogSchema.verifyStructure(driver, 23)
                verifyFrozenEstimateContents(driver, 23)
                Triple(fixture, saved, payload)
            }
            val identity = open(file).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                assertFailsWith<IllegalStateException> {
                    PriceCatalogSchema.migrate(driver, 23, 24, AfterVersion(23) { error("Injected migration failure") })
                }
                PriceCatalogSchema.verifyStructure(driver, 23)
                assertEquals(23L, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                openDesktopPriceCatalog(driver, true)
                val db = PrototypeDatabase(driver)
                val repository = ImmutableEstimateRepository(db)
                assertEquals(payload, repository.revision(prior.initialRevisionUuid)?.detailsJson)
                val project = assertNotNull(ProjectRepository(db).find(fixture.projectId))
                val saved = assertNotNull(repository.exactIndividual(prior.seriesUuid, prior.initialRevisionUuid))
                val ref = ConsolidatedDocumentRef(fixture.measurementUuid, saved.seriesUuid, saved.revisionUuid,
                    saved.frozen.consolidatedSourceProof())
                val command = ConsolidatedSave(newRecordUuid(), project.id, project.uuid, null, null,
                    ConsolidatedDraft("Project estimate", ConsolidatedDiscount(QuoteDiscountMode.FIXED, 100), listOf(ref)))
                ConsolidatedEstimateRepository(db).save(command, LocalDate(2026, 11, 26), 701)
            }
            open(file).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                openDesktopPriceCatalog(driver, true)
                val db = PrototypeDatabase(driver)
                assertEquals("Project estimate", ConsolidatedEstimateRepository(db).read(identity.revisionUuid)?.frozen?.draft?.title)
                driver.execute(null, "UPDATE consolidated_revisions SET proof = 'invalid'", 0)
                assertFails { verifyFrozenEstimateContents(driver) }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
