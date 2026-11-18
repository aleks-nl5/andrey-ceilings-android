package com.andreyceilings.shared.estimate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

class FrozenRevisionValidationTest {
    @Test fun partialAppendRequiresCurrentAcknowledgementAndRejectsSubstitutedOwnership() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            MeasurementElementRepository(db).createWish(fixture.measurementUuid, "Unpriced optional", 601, approval = ElementApproval.APPROVED)
            val base = repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!
            val p = FrozenRevisionProof.presenter(db, fixture, base)
            assertTrue(p.state.value.canAcknowledge)
            assertNull(p.saveRequest(fixture.projectId, "Partial"))
            p.acknowledge(true)
            val command = AppendIndividualRevisionRequest(p.revisionTarget!!, p.saveRequest(fixture.projectId, "Partial")!!)
            for (bad in listOf(command.copy(calculation = command.calculation.copy(acknowledgedFingerprint = null)),
                command.copy(calculation = command.calculation.copy(expectedOmissions = emptyList())))) {
                assertEquals(IndividualEstimateSaveFailure.Reason.ACK_REQUIRED,
                    assertFailsWith<IndividualEstimateSaveFailure> { repo.appendIndividual(bad, 602) }.reason)
            }
            assertEquals(IndividualEstimateSaveFailure.Reason.INVALID_COMMAND,
                assertFailsWith<IndividualEstimateSaveFailure> { repo.appendIndividual(command.copy(calculation = command.calculation.copy(projectId = fixture.projectId + 1)), 602) }.reason)
            assertEquals(1, repo.revisions(first.seriesUuid).size)
            val next = repo.appendIndividual(command, 603)
            assertTrue(repo.exactIndividual(first.seriesUuid, next.initialRevisionUuid)!!.frozen.partial)
            assertEquals(2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
            verifyFrozenEstimateContents(driver)
        }
    }

    @Test fun importAuditRejectsPayloadSwapNormalizedMoneyAndReceiptCorruption() {
        for (mutation in listOf("payload", "money", "receipt", "parent", "schema")) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val repo = ImmutableEstimateRepository(db)
                val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
                repo.appendIndividual(FrozenRevisionProof.request(db, fixture, first.seriesUuid), 601)
                val second = repo.frozenIndividual(first.seriesUuid)!!
                repo.appendIndividual(FrozenRevisionProof.request(db, fixture, first.seriesUuid), 602)
                when (mutation) {
                    "payload" -> driver.execute(null, "UPDATE estimate_revision_frozen SET payload = ?", 1) { bindString(0, second.payload) }
                    "money" -> driver.execute(null, "UPDATE estimate_revision_frozen SET subtotalCents = subtotalCents + 1, totalCents = totalCents + 1", 0)
                    "receipt" -> driver.execute(null, "UPDATE estimate_revision_receipts SET command = replace(command, 'Current measurement', 'Other title')", 0)
                    "parent" -> driver.execute(null, "UPDATE estimate_revisions SET parentRevisionUuid = NULL WHERE versionNumber = 2", 0)
                    "schema" -> {
                        driver.execute(null, "DROP TABLE estimate_revision_receipts", 0)
                        driver.execute(null, "CREATE TABLE estimate_revision_receipts(operationUuid TEXT, command TEXT, seriesUuid TEXT, revisionUuid TEXT)", 0)
                    }
                }
                assertFails {
                    PriceCatalogSchema.verifyStructure(driver)
                    verifyFrozenEstimateContents(driver)
                }
            }
        }
    }
}
