package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

/** Identical proofs run on JDBC and the three physical encrypted drivers. */
object FrozenRevisionProof {
    fun presenter(db: PrototypeDatabase, fixture: MeasurementPricingFixture.Fixture, base: SavedIndividualQuote? = null) =
        TransientQuotePresenter(fixture.measurementUuid, revisionBase = base).also {
            it.acceptRead(it.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        }
    fun request(db: PrototypeDatabase, fixture: MeasurementPricingFixture.Fixture, seriesUuid: String): AppendIndividualRevisionRequest {
        val history = ImmutableEstimateRepository(db)
        val latest = checkNotNull(history.frozenIndividual(seriesUuid))
        val base = checkNotNull(history.exactIndividual(seriesUuid, latest.revisionUuid))
        val presenter = presenter(db, fixture, base)
        return AppendIndividualRevisionRequest(checkNotNull(presenter.revisionTarget), checkNotNull(presenter.saveRequest(fixture.projectId, "Current measurement")))
    }
    fun run(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val previousCount = ImmutableEstimateRepository(db).frozenIndividuals().size
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
        val v1 = checkNotNull(history.frozenIndividual(first.seriesUuid))
        assertFalse(v1.payload.contains("revisionIdentity")) // Preserve the exact v1 codec/canonical proof.
        val sequence = db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence
        assertTrue(history.setWorkflowStatus(first.seriesUuid, 1, EstimateWorkflowStatus.APPROVED, 601))
        val staleStatus = request(db, fixture, first.seriesUuid)
        assertTrue(history.setWorkflowStatus(first.seriesUuid, 2, EstimateWorkflowStatus.SENT, 602))
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_STATUS,
            assertFailsWith<IndividualEstimateSaveFailure> { history.appendIndividual(staleStatus, 603) }.reason)
        // The new draft starts with current quantities and no old discount/override.
        val old = checkNotNull(history.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
        val fresh = presenter(db, fixture, old)
        assertEquals("0", fresh.state.value.discountText)
        assertTrue(fresh.state.value.edits.isEmpty())
        assertEquals(12000, fresh.state.value.amounts!!.finalCents)
        assertTrue(fresh.carryQuantity(fixture.elementUuid))
        assertFalse(fresh.state.value.edits.getValue(fixture.elementUuid).confirmed)
        assertNull(fresh.state.value.amounts)
        fresh.confirmQuantity(fixture.elementUuid)
        assertTrue(fresh.carryDiscount())
        assertFalse(fresh.state.value.discountConfirmed)
        fresh.confirmDiscount()
        assertEquals(old.frozen.amounts, fresh.state.value.amounts)
        val staleCatalog = request(db, fixture, first.seriesUuid)
        val prices = PriceRepository(db)
        prices.updateSalePrice(prices.find(fixture.priceId)!!, 20.0, 604)
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_CATALOG,
            assertFailsWith<IndividualEstimateSaveFailure> { history.appendIndividual(staleCatalog, 605) }.reason)
        // Change real canonical geometry; subsequent revisions must capture the new drawing and quantity.
        val m = checkNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
        val geometry = checkNotNull(validateMeasurementCreate(MeasurementCreateDraft(fixture.projectId,
            MeasurementPrecision.ONE_MILLIMETER, "Room updated", "", MeasurementOutlineDraft.Template("rectangle",
                mapOf("length" to "5", "width" to "3")), newRecordUuid())).geometry)
        MeasurementRepository(db).update(m.id, "Room updated", geometry.shapeType, 5.0, 3.0,
            geometry.areaSquareMeters, geometry.perimeterMeters, MeasurementGeometryCodec.write(geometry), "current source")
        val secondCommand = request(db, fixture, first.seriesUuid)
        assertEquals(30000, secondCommand.calculation.expectedAmounts.finalCents)
        val v2 = history.appendIndividual(secondCommand, 610)
        assertEquals(first.seriesUuid, v2.seriesUuid)
        assertEquals(first.documentNumber, v2.documentNumber)
        assertEquals(sequence, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        val second = checkNotNull(history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid))
        assertEquals(2, second.versionNumber)
        assertEquals(first.initialRevisionUuid, second.parentRevisionUuid)
        assertEquals(EstimateWorkflowStatus.DRAFT, second.status)
        assertEquals(4, second.statusVersion)
        assertEquals(15000, second.frozen.rows.single { it.line.elementUuid == fixture.elementUuid }.line.sourceQuantityMilli)
        assertNotEquals(old.frozen.source.drawingJson, second.frozen.source.drawingJson)
        assertEquals(v1.payload, db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOne().payload)
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_REVISION,
            assertFailsWith<IndividualEstimateSaveFailure> {
                history.appendIndividual(secondCommand.copy(calculation = secondCommand.calculation.copy(operationUuid = newRecordUuid())), 611)
            }.reason)
        assertEquals(v2, history.appendIndividual(secondCommand, 612))
        assertEquals(IndividualEstimateSaveFailure.Reason.OPERATION_CONFLICT,
            assertFailsWith<IndividualEstimateSaveFailure> {
                history.appendIndividual(secondCommand.copy(calculation = secondCommand.calculation.copy(title = "Changed")), 612)
            }.reason)
        // Late failure rolls back the revision, frozen values, workflow and receipt together.
        val thirdCommand = request(db, fixture, first.seriesUuid)
        val failing = object : SqlDriver by driver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (sql.startsWith("INSERT INTO estimate_revision_receipts")) error("Injected late revision receipt failure")
                return driver.execute(identifier, sql, parameters, binders)
            }
        }
        assertFails { ImmutableEstimateRepository(PrototypeDatabase(failing)).appendIndividual(thirdCommand, 613) }
        assertEquals(2, history.revisions(first.seriesUuid).size)
        assertEquals(second, history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid))
        assertNull(db.frozenRevisionQueries.selectRevisionReceipt(thirdCommand.calculation.operationUuid).executeAsOneOrNull())
        val v3 = history.appendIndividual(thirdCommand, 614)
        assertEquals(3, history.revision(v3.initialRevisionUuid)!!.versionNumber)
        assertEquals(v2.initialRevisionUuid, history.revision(v3.initialRevisionUuid)!!.parentRevisionUuid)
        assertEquals(previousCount + 1, history.frozenIndividuals().size)
        assertEquals(listOf(3L, 2L, 1L), history.individualHistory(first.seriesUuid).map { it.versionNumber })
        assertEquals(v1.payload, db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOne().payload)
        assertFails { verifyFrozenRevisionIdentity(second.frozen, first.seriesUuid, v3.initialRevisionUuid, 3, v2.initialRevisionUuid) }
        verifyFrozenEstimateContents(driver)
        // Receipts are primary even after mutable source/catalog changes or deletion.
        prices.updateSalePrice(prices.find(fixture.priceId)!!, 25.0, 620)
        MeasurementRepository(db).delete(m.id)
        assertIs<PricingReadResult.Unavailable>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        assertEquals(v2, history.appendIndividual(secondCommand, 621))
        assertEquals(v3, history.appendIndividual(thirdCommand, 622))
        assertEquals(old.frozen, history.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.frozen)
        assertEquals(sequence, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        verifyFrozenEstimateContents(driver)
    }
}
