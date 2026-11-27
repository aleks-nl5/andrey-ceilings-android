package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.EstimateIssuancePolicy
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlin.test.*

object EstimateV22MigrationProof {
    val v21Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 21L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            WallVersionMigrationProof.v19Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 19L, version)
            return QueryResult.Unit
        }

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
            vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Historical v21 fixture")
    }

    fun seed(driver: SqlDriver): MeasurementPricingFixture.Fixture {
        val database = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(database)
        val approved = seedHistoricalEstimate(driver, fixture.projectId, fixture.measurementUuid, 21)
        val deleted = seedHistoricalEstimate(driver, fixture.projectId, fixture.measurementUuid, 21, "SENT")
        driver.execute(null, "INSERT INTO estimate_revision_tombstones VALUES (?,42)", 1) { bindString(0, deleted.latest) }
        driver.execute(null, "UPDATE estimate_series SET deletedAt=42 WHERE seriesUuid=?", 1) { bindString(0, deleted.series) }
        driver.execute(null, "INSERT INTO estimate_number_settings VALUES (1,'{uuid}','AC',5,300,'legacy')", 0)
        EstimateRepository(database).create(
            fixture.projectId, "Legacy", "PVC", "standard", "simple", 2.0, 3.0, 6.0, 10.0,
            50.0, 10.0, 0.0, 0.0, 60.0, createdAt = 14
        )
        driver.execute(null, "UPDATE estimates SET seriesUuid=?", 1) { bindString(0, approved.series) }
        val project = ProjectRepository(database)
        project.updateStatus(fixture.projectId, "in_progress", 18)
        project.updatePaymentStatus(fixture.projectId, "paid", 19)
        return fixture
    }

    fun counts(driver: SqlDriver): Map<String, Long> = preV22EstimateTables.associateWith { table ->
        PriceNameUniquenessProof.scalar(driver, "SELECT count(*) FROM $table")
    }

    private fun businessSnapshot(driver: SqlDriver) = MeasurementElementMigrationProof.rows(driver,
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", 1)
        .map { checkNotNull(it.single()) }
        .filterNot { it.startsWith("consolidated_") || it.startsWith("calendar_") || it in preV22EstimateTables || it in setOf("estimate_issuance_policy", "estimate_number_reservations") }
        .associateWith { table ->
            val columns = MeasurementElementMigrationProof.rows(driver, "PRAGMA table_info($table)", 6)
                .map { checkNotNull(it[1]) }
            MeasurementElementMigrationProof.rows(driver,
                "SELECT ${columns.joinToString { "quote($it)" }} FROM $table ORDER BY " +
                    columns.indices.joinToString { (it + 1).toString() }, columns.size)
        }

    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val fixture = open(v21Schema).use(::seed)
        val oldCounts = open(v21Schema).use(::counts)
        assertTrue(oldCounts.values.all { it > 0 })
        val original = open(v21Schema).use(::businessSnapshot)
        val oldPayloads = open(v21Schema).use { MeasurementElementMigrationProof.rows(it,
            "SELECT detailsJson FROM estimate_revisions ORDER BY revisionUuid", 1) }
        val failure = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                vararg callbacks: AfterVersion) = production.migrate(driver, oldVersion, newVersion, *callbacks,
                AfterVersion(21) { throw IllegalStateException("Injected after estimate purge") })
        }
        assertFails { open(failure).close() }
        open(v21Schema).use {
            assertEquals(original, businessSnapshot(it))
            assertEquals(oldPayloads, MeasurementElementMigrationProof.rows(it,
                "SELECT detailsJson FROM estimate_revisions ORDER BY revisionUuid", 1))
            assertEquals(oldCounts, counts(it))
            verifyCalendarStructure(it, 21)
        }
        val (series, revision) = open(production).use { driver ->
            verifyFrozenEstimateContents(driver)
            assertTrue(counts(driver).values.all { it == 0L })
            assertEquals(original, businessSnapshot(driver))
            if (production.version >= 25) {
                verifyCalendarStructure(driver, production.version)
                verifyCalendarContents(driver)
                assertEquals(0L, PriceNameUniquenessProof.scalar(driver, "SELECT count(*) FROM calendar_works"))
            }
            val db = PrototypeDatabase(driver)
            val history = ImmutableEstimateRepository(db)
            assertEquals(EstimateIssuancePolicy(), history.issuancePolicy())
            assertTrue(db.frozenRevisionQueries.selectProjectQuoteMetadata(fixture.projectId).executeAsList().isEmpty())
            val command = FrozenIndividualEstimateProof.command(db, fixture)
            val saved = history.saveIndividual(command, 2026, 600)
            assertEquals(1L, saved.localSequence)
            assertTrue(history.setWorkflowStatus(saved.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 601))
            val metadata = db.frozenRevisionQueries.selectProjectQuoteMetadata(fixture.projectId).executeAsOne()
            assertEquals("APPROVED", metadata.workflowStatus)
            assertEquals(command.expectedAmounts.finalCents, metadata.finalCents)
            saved.seriesUuid to saved.initialRevisionUuid
        }
        open(production).use { driver ->
            val history = ImmutableEstimateRepository(PrototypeDatabase(driver))
            assertEquals("КШ-2026-0001", history.exactIndividual(series, revision)?.frozen?.publicNumber)
        }
    }
}
