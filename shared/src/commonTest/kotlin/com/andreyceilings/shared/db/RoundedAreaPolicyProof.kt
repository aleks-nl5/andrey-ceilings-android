package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

/** Same quantity policy, immutable payload and encrypted lifecycle proof on all platforms. */
object RoundedAreaPolicyProof {
    fun run(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val f = MeasurementPricingFixture.seed(db)
        val repo = ImmutableEstimateRepository(db)
        val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, f), 2026, 600)
        val firstPayload = repo.frozenIndividual(first.seriesUuid)!!.payload
        assertTrue(repo.setWorkflowStatus(first.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 601))
        assertTrue(repo.selectRevision(first.seriesUuid, first.initialRevisionUuid, 1, 602))
        val original = MeasurementEditStore(db) { 603 }.load(f.projectId, MeasurementRepository(db).findByUuid(f.measurementUuid)!!.id)
        val draft = original.draft.reduce(MeasurementCreateIntent.Dimension("length", "4.12"))
            .reduce(MeasurementCreateIntent.Dimension("width", "3.34"))
        MeasurementEditStore(db) { 603 }.update(original, draft)
        PriceRepository(db).updateSalePrice(PriceRepository(db).find(f.priceId)!!, 100.0, 604)
        val source = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(f.projectId, f.measurementUuid))
        val drawing = MeasurementRepository(db).findByUuid(f.measurementUuid)!!.drawingJson
        assertEquals(13.7608, source.source.geometry.areaSquareMeters, 0.000000000001)
        val preview = source.buildPreview()
        assertTrue(preview.canFinalizeFull)
        val canvas = preview.priceableLines.single { it.elementUuid == f.elementUuid }
        assertEquals(13761, canvas.sourceQuantityMilli)
        assertEquals(137610, canvas.totalCents) // First area -> 13.761, then cents. Not 137608.
        assertEquals(QuantityRoundingPolicy.CAPTURED_AREA_TO_MILLI_HALF_UP_V1, canvas.quantityRoundingPolicy)
        val request = FrozenRevisionProof.request(db, f, first.seriesUuid)
        val next = repo.appendIndividual(request, 605)
        val second = repo.exactIndividual(first.seriesUuid, next.initialRevisionUuid)!!
        assertEquals(3, second.frozen.formatVersion)
        assertFailsWith<IllegalArgumentException> { second.frozen.copy(formatVersion = 2).validate() }
        val row = second.frozen.rows.single { it.line.elementUuid == f.elementUuid }
        for (tampered in listOf(row.line.copy(quantityRoundingPolicy = null),
            row.line.copy(sourceQuantityMilli = row.line.sourceQuantityMilli - 1))) {
            assertFailsWith<IllegalArgumentException> {
                second.frozen.copy(rows = second.frozen.rows.map { if (it.uuid == row.uuid) it.copy(line = tampered) else it }).validate()
            }
        }
        assertEquals(drawing, second.frozen.source.drawingJson)
        assertEquals(canvas, second.frozen.rows.single { it.line.elementUuid == f.elementUuid }.line)
        assertEquals(EstimateWorkflowStatus.APPROVED, repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.status)
        assertEquals(first.initialRevisionUuid, second.selectedRevisionUuid)
        assertEquals(firstPayload, db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOne().payload)
        assertEquals(next, repo.appendIndividual(request, 606))
        assertEquals(drawing, MeasurementRepository(db).findByUuid(f.measurementUuid)!!.drawingJson)
        verifyFrozenEstimateContents(driver)
        // New v1 uses the same explicit format/policy without inventing a parent identity.
        val standalone = repo.saveIndividual(FrozenRevisionProof.presenter(db, f).saveRequest(f.projectId, "Rounded area")!!, 2026, 607)
        val saved = repo.exactIndividual(standalone.seriesUuid, standalone.initialRevisionUuid)!!
        assertEquals(3, saved.frozen.formatVersion)
        assertNull(saved.frozen.revisionIdentity)
        verifyFrozenEstimateContents(driver)
    }

    val v18Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 18L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            RevisionWorkflowMigrationProof.v17Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 17, 18)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("v18 fixture")
    }
    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        var series = ""; var revision = ""; var payload = ""
        open(v18Schema).use { d ->
            val db = PrototypeDatabase(d); val f = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val saved = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, f), 2026, 600)
            series = saved.seriesUuid; revision = saved.initialRevisionUuid
            repo.setWorkflowStatus(revision, 1, EstimateWorkflowStatus.APPROVED, 601)
            repo.selectRevision(series, revision, 1, 602)
            payload = repo.frozenIndividual(series)!!.payload
        }
        open(production).use { d ->
            val repo = ImmutableEstimateRepository(PrototypeDatabase(d))
            assertEquals(payload, repo.frozenIndividual(series)!!.payload)
            assertEquals(EstimateWorkflowStatus.APPROVED, repo.exactIndividual(series, revision)!!.status)
            assertEquals(revision, repo.exactIndividual(series, revision)!!.selectedRevisionUuid)
            run(d)
        }
        open(production).use { d ->
            verifyFrozenEstimateContents(d)
            val repo = ImmutableEstimateRepository(PrototypeDatabase(d))
            assertEquals(payload, repo.frozenIndividual(series)!!.payload)
            assertTrue(repo.frozenIndividuals().any { decodeFrozenIndividualEstimate(it.payload).formatVersion == 3 })
            repo.frozenIndividuals().forEach { repo.exactIndividual(it.seriesUuid, it.revisionUuid) }
        }
    }
}
