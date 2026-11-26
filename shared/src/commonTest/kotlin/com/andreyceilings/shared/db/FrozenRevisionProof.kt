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
        return AppendIndividualRevisionRequest(checkNotNull(presenter.revisionTarget), checkNotNull(presenter.saveRequest(fixture.projectId, "Current measurement v${latest.versionNumber + 1}")))
    }
    /** Seed a historical alternative for compatibility proofs without a production bypass. */
    fun <T> withLegacyAlternative(driver: SqlDriver, seriesIds: List<String>, measurementUuid: String, create: () -> T): T {
        fun link(id: String, uuid: String?) {
            driver.execute(null, "UPDATE estimate_series SET measurementUuid = ? WHERE seriesUuid = ?", 2) {
                bindString(0, uuid)
                bindString(1, id)
            }
        }
        seriesIds.forEach { link(it, null) }
        return try { create() } finally { seriesIds.forEach { link(it, measurementUuid) } }
    }

    fun run(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val previousCount = ImmutableEstimateRepository(db).frozenIndividuals().size
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
        val v1 = checkNotNull(history.frozenIndividual(first.seriesUuid))
        assertFalse(v1.payload.contains("revisionIdentity")) // Preserve the exact v1 codec/canonical proof.
        val sequence = nextIndividualSequence(db)
        assertTrue(history.setWorkflowStatus(first.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 601))
        assertTrue(history.selectRevision(first.seriesUuid, first.initialRevisionUuid, 1, 601))
        val staleStatus = request(db, fixture, first.seriesUuid)
        assertTrue(history.setWorkflowStatus(first.initialRevisionUuid, 2, EstimateWorkflowStatus.SENT, 602))
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
        assertEquals(sequence, nextIndividualSequence(db))
        val second = checkNotNull(history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid))
        assertEquals(2, second.versionNumber)
        assertEquals(first.initialRevisionUuid, second.parentRevisionUuid)
        assertEquals(EstimateWorkflowStatus.DRAFT, second.status)
        assertEquals(1, second.statusVersion)
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
        for (table in listOf("estimate_revision_receipts", "estimate_revision_workflow")) {
            val failing = object : SqlDriver by driver {
                override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                    val result = driver.execute(identifier, sql, parameters, binders)
                    if (sql.startsWith("INSERT INTO $table")) error("Injected late revision failure after insert")
                    return result
                }
            }
            assertFails { ImmutableEstimateRepository(PrototypeDatabase(failing)).appendIndividual(thirdCommand, 613) }
            assertEquals(2, history.revisions(first.seriesUuid).size)
            assertEquals(second, history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid))
            assertNull(db.frozenRevisionQueries.selectRevisionReceipt(thirdCommand.calculation.operationUuid).executeAsOneOrNull())
            verifyFrozenEstimateContents(driver)
        }
        val v3 = history.appendIndividual(thirdCommand, 614)
        assertEquals(3, history.revision(v3.initialRevisionUuid)!!.versionNumber)
        assertEquals(v2.initialRevisionUuid, history.revision(v3.initialRevisionUuid)!!.parentRevisionUuid)
        assertEquals(previousCount + 3, history.frozenIndividuals().size)
        assertEquals(listOf(3L, 2L, 1L), history.individualHistory(first.seriesUuid).map { it.versionNumber })
        assertEquals(v1.payload, db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOne().payload)
        assertFails { verifyFrozenRevisionIdentity(second.frozen, first.seriesUuid, v3.initialRevisionUuid, 3, v2.initialRevisionUuid) }
        // Alternatives preserve independent metadata, explicit choice, exact frozen UUIDs.
        assertEquals(EstimateWorkflowStatus.SENT, history.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.status)
        assertEquals(EstimateWorkflowStatus.DRAFT, history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid)!!.status)
        assertEquals(EstimateWorkflowStatus.DRAFT, history.exactIndividual(first.seriesUuid, v3.initialRevisionUuid)!!.status)
        assertTrue(history.setWorkflowStatus(first.initialRevisionUuid, 3, EstimateWorkflowStatus.APPROVED, 615))
        assertEquals(first.initialRevisionUuid, history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid)!!.selectedRevisionUuid)
        assertFalse(history.selectRevision(first.seriesUuid, v3.initialRevisionUuid, 1, 617))
        assertEquals(first.initialRevisionUuid, history.exactIndividual(first.seriesUuid, v3.initialRevisionUuid)!!.selectedRevisionUuid)
        assertTrue(history.selectRevision(first.seriesUuid, v2.initialRevisionUuid, 2, 618))
        assertTrue(history.selectRevision(first.seriesUuid, first.initialRevisionUuid, 3, 619))
        assertEquals(EstimateWorkflowStatus.APPROVED, history.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.status)
        assertEquals(EstimateWorkflowStatus.DRAFT, history.exactIndividual(first.seriesUuid, v3.initialRevisionUuid)!!.status)
        val duplicate = request(db, fixture, first.seriesUuid)
        val unchangedTitle = history.exactIndividual(first.seriesUuid, v3.initialRevisionUuid)!!.frozen.title
        assertEquals(IndividualEstimateSaveFailure.Reason.DUPLICATE_CONTENT,
            assertFailsWith<IndividualEstimateSaveFailure> {
                history.appendIndividual(duplicate.copy(calculation = duplicate.calculation.copy(title = unchangedTitle)), 619)
            }.reason)
        assertEquals(3, history.revisions(first.seriesUuid).size)
        assertNull(db.frozenRevisionQueries.selectRevisionReceipt(duplicate.calculation.operationUuid).executeAsOneOrNull())
        val alternative = withLegacyAlternative(driver, listOf(first.seriesUuid), fixture.measurementUuid) {
            history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 620)
        }
        val previousApproval = mapOf(first.initialRevisionUuid to 4L)
        val alternativeId = alternative.initialRevisionUuid
        assertFalse(history.setWorkflowStatus(alternativeId, 1, EstimateWorkflowStatus.APPROVED, 621))
        assertFalse(history.setWorkflowStatus(alternativeId, 1, EstimateWorkflowStatus.APPROVED, 621,
            mapOf(first.initialRevisionUuid to 3L)))
        assertTrue(history.setWorkflowStatus(alternativeId, 1, EstimateWorkflowStatus.APPROVED, 621, previousApproval))
        assertEquals(EstimateWorkflowStatus.SENT, history.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.status)
        assertEquals(EstimateWorkflowStatus.APPROVED, history.exactIndividual(alternative.seriesUuid, alternativeId)!!.status)
        assertFalse(history.setWorkflowStatus(v3.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 622, previousApproval))
        assertEquals(EstimateWorkflowStatus.DRAFT, history.exactIndividual(first.seriesUuid, v3.initialRevisionUuid)!!.status)
        assertTrue(history.setWorkflowStatus(alternativeId, 2, EstimateWorkflowStatus.APPROVED, 622))
        assertEquals(2, history.exactIndividual(alternative.seriesUuid, alternativeId)!!.statusVersion)
        assertEquals(sequence + 1, nextIndividualSequence(db))
        verifyFrozenEstimateContents(driver)
        // Protected versions survive both repository delete routes. Deleting siblings preserves their receipts.
        history.softDeleteSeries(alternative.seriesUuid, 623)
        assertFalse(history.deleteIndividualRevision(alternative.seriesUuid, alternativeId, 2, 623))
        val frozenQueries = db.frozenRevisionQueries
        assertNull(frozenQueries.insertRevisionTombstone(deletedAt = 623, revisionUuid = alternativeId,
            seriesUuid = alternative.seriesUuid, expectedStatusVersion = 2).executeAsOneOrNull())
        assertNull(frozenQueries.insertRevisionTombstone(deletedAt = 623, revisionUuid = v3.initialRevisionUuid,
            seriesUuid = alternative.seriesUuid, expectedStatusVersion = 1).executeAsOneOrNull())
        assertNull(frozenQueries.insertRevisionTombstone(deletedAt = 623, revisionUuid = v3.initialRevisionUuid,
            seriesUuid = first.seriesUuid, expectedStatusVersion = 2).executeAsOneOrNull())
        assertFalse(history.deleteIndividualSeries(alternative.seriesUuid, mapOf(alternativeId to 2L), 623))
        assertEquals(EstimateWorkflowStatus.APPROVED, history.exactIndividual(alternative.seriesUuid, alternativeId)!!.status)
        val staleAppend = request(db, fixture, first.seriesUuid)
        assertFalse(history.deleteIndividualRevision(first.seriesUuid, v3.initialRevisionUuid, 2, 624))
        assertTrue(history.deleteIndividualRevision(first.seriesUuid, v3.initialRevisionUuid, 1, 624))
        assertNull(history.exactIndividual(first.seriesUuid, v3.initialRevisionUuid))
        assertFalse(history.setWorkflowStatus(v3.initialRevisionUuid, 1, EstimateWorkflowStatus.SENT, 625))
        assertFalse(history.selectRevision(first.seriesUuid, v3.initialRevisionUuid, 4, 625))
        assertEquals(second, history.exactIndividual(first.seriesUuid, v2.initialRevisionUuid)?.copy(
            selectedRevisionUuid = second.selectedRevisionUuid, choiceVersion = second.choiceVersion))
        assertEquals(0L, db.frozenRevisionQueries.selectHistoricalExactRevision(first.seriesUuid, v3.initialRevisionUuid)
            .executeAsOne().isActive)
        assertEquals(v3, history.appendIndividual(thirdCommand, 625))
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_REVISION,
            assertFailsWith<IndividualEstimateSaveFailure> { history.appendIndividual(staleAppend, 625) }.reason)
        val newestActive = request(db, fixture, first.seriesUuid)
        val afterDeletion = newestActive.copy(target = newestActive.target.copy(
            historicalHead = HistoricalRevisionHead(v3.initialRevisionUuid, 3)))
        val fourth = history.appendIndividual(afterDeletion, 626)
        val fourthQuote = assertNotNull(history.exactIndividual(first.seriesUuid, fourth.initialRevisionUuid))
        assertEquals(4, fourthQuote.versionNumber)
        assertEquals(v3.initialRevisionUuid, fourthQuote.parentRevisionUuid)
        assertFalse(history.deleteIndividualSeries(first.seriesUuid, mapOf(
            first.initialRevisionUuid to 5L, v2.initialRevisionUuid to 1L), 627))
        assertTrue(history.deleteIndividualSeries(first.seriesUuid, mapOf(
            first.initialRevisionUuid to 5L, v2.initialRevisionUuid to 1L, fourth.initialRevisionUuid to 1L), 627))
        assertNull(history.frozenIndividual(first.seriesUuid))
        assertTrue(history.individualHistory(first.seriesUuid).isEmpty())
        assertEquals(old.frozen, decodeFrozenIndividualEstimate(db.frozenRevisionQueries
            .selectHistoricalExactRevision(first.seriesUuid, first.initialRevisionUuid).executeAsOne().payload))
        assertEquals(fourth, history.appendIndividual(afterDeletion, 628))
        assertTrue(history.setWorkflowStatus(alternativeId, 2, EstimateWorkflowStatus.REJECTED, 629))
        assertTrue(history.deleteIndividualRevision(alternative.seriesUuid, alternativeId, 3, 630))
        assertNull(history.frozenIndividual(alternative.seriesUuid))
        assertNotNull(history.series(alternative.seriesUuid)?.deletedAt)
        verifyFrozenEstimateContents(driver, PriceCatalogSchema.version)
        // Receipts are primary even after mutable source/catalog changes or deletion.
        prices.updateSalePrice(prices.find(fixture.priceId)!!, 25.0, 620)
        MeasurementRepository(db).delete(m.id)
        assertIs<PricingReadResult.Unavailable>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        assertEquals(v2, history.appendIndividual(secondCommand, 621))
        assertEquals(v3, history.appendIndividual(thirdCommand, 622))
        assertEquals(old.frozen, decodeFrozenIndividualEstimate(db.frozenRevisionQueries
            .selectHistoricalExactRevision(first.seriesUuid, first.initialRevisionUuid).executeAsOne().payload))
        assertEquals(sequence + 1, nextIndividualSequence(db))
        verifyFrozenEstimateContents(driver)
        proveDeletedSeriesContinuation(driver, db)
    }

    private fun proveDeletedSeriesContinuation(driver: SqlDriver, db: PrototypeDatabase) {
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        val older = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 799)
        val first = withLegacyAlternative(driver, listOf(older.seriesUuid), fixture.measurementUuid) {
            history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 800)
        }
        fun candidates() = db.frozenRevisionQueries.selectIndividualSeriesForCeiling(
            fixture.projectId, fixture.measurementUuid).executeAsList()
        assertEquals(setOf(older.seriesUuid, first.seriesUuid), candidates().toSet())
        val second = history.appendIndividual(request(db, fixture, first.seriesUuid), 801)
        val old = history.individualHistory(first.seriesUuid)
        assertTrue(history.deleteIndividualRevision(first.seriesUuid, first.initialRevisionUuid, 1, 802))
        // Whole-series deletion historically left the remaining version without an individual tombstone.
        assertTrue(history.deleteIndividualSeries(first.seriesUuid, mapOf(second.initialRevisionUuid to 1L), 803))
        val parent = old.first().copy(seriesDeletedAt = 803)
        val fresh = TransientQuotePresenter(fixture.measurementUuid, revisionBase = parent, revisionCarrySource = null)
        fresh.acceptRead(fresh.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        val proposal = AppendIndividualRevisionRequest(checkNotNull(fresh.revisionTarget),
            checkNotNull(fresh.saveRequest(fixture.projectId, parent.frozen.title)))
        // An active older series outranks the newer deleted one.
        assertEquals(listOf(older.seriesUuid), candidates())
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_REVISION,
            assertFailsWith<IndividualEstimateSaveFailure> { history.appendIndividual(proposal, 804) }.reason)
        assertTrue(history.deleteIndividualRevision(older.seriesUuid, older.initialRevisionUuid, 1, 804))
        // All historical series are now deleted: continue the newest-created one.
        assertEquals(listOf(first.seriesUuid), candidates())
        assertEquals(IndividualEstimateSaveFailure.Reason.SERIES_EXISTS,
            assertFailsWith<IndividualEstimateSaveFailure> {
                history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 804)
            }.reason)
        val failing = object : SqlDriver by driver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                val result = driver.execute(identifier, sql, parameters, binders)
                if (sql.startsWith("INSERT INTO estimate_revision_receipts")) error("Injected continuation failure")
                return result
            }
        }
        assertFails { ImmutableEstimateRepository(PrototypeDatabase(failing)).appendIndividual(proposal, 804) }
        assertEquals(803L, history.series(first.seriesUuid)?.deletedAt)
        assertTrue(history.individualHistory(first.seriesUuid).isEmpty())
        assertEquals(2, history.revisions(first.seriesUuid).size)
        assertNull(db.frozenRevisionQueries.selectRevisionReceipt(proposal.calculation.operationUuid).executeAsOneOrNull())

        val third = history.appendIndividual(proposal, 805)
        assertEquals(first.seriesUuid, third.seriesUuid)
        assertEquals(first.documentNumber, third.documentNumber)
        assertEquals(first.localSequence, third.localSequence)
        val active = history.individualHistory(first.seriesUuid).single()
        assertEquals(3, active.versionNumber)
        assertEquals(listOf(first.seriesUuid), candidates())
        assertNull(history.exactIndividual(older.seriesUuid, older.initialRevisionUuid))
        assertNotNull(history.series(older.seriesUuid)?.deletedAt)
        assertEquals(second.initialRevisionUuid, active.parentRevisionUuid)
        assertEquals(EstimateWorkflowStatus.DRAFT, active.status)
        assertNull(db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOneOrNull())
        for (quote in old) {
            assertNull(history.exactIndividual(first.seriesUuid, quote.revisionUuid))
            assertEquals(quote.frozen, decodeFrozenIndividualEstimate(db.frozenRevisionQueries
                .selectHistoricalExactRevision(first.seriesUuid, quote.revisionUuid).executeAsOne().payload))
        }
        assertEquals(third, history.appendIndividual(proposal, 806))
        verifyFrozenEstimateContents(driver)
    }
}
