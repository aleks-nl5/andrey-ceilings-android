package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Runs unchanged on SQLite JDBC and real Android/Windows/iOS SQLCipher drivers. */
object FrozenIndividualEstimateProof {
    fun command(db: PrototypeDatabase, fixture: MeasurementPricingFixture.Fixture, partial: Boolean = false): IndividualEstimateSaveRequest {
        val presenter = TransientQuotePresenter(fixture.measurementUuid)
        presenter.acceptRead(presenter.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        presenter.editQuantity(fixture.elementUuid, "12.007"); presenter.confirmQuantity(fixture.elementUuid)
        presenter.editDiscount("12,34"); presenter.confirmDiscount()
        if (partial) { assertTrue(presenter.state.value.canAcknowledge); presenter.acknowledge(true) }
        return assertNotNull(presenter.saveRequest(fixture.projectId, "  Kitchen v1  "))
    }

    fun run(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        history.configureNumberPolicy(EstimateNumberPolicy("{prefix}-{year}-{sequence}-{suffix}", "КШ", 5, "ІП"))
        val original = command(db, fixture)
        val before = db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne() .nextSequence
        val saved = history.saveIndividual(original, 2026, 300)
        assertEquals(saved, history.saveIndividual(original, 2027, 900))
        assertEquals(before + 1, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        val record = assertNotNull(history.frozenIndividual(saved.seriesUuid))
        val frozen = decodeFrozenIndividualEstimate(record.payload)
        assertEquals("Kitchen v1", frozen.title)
        assertEquals(12000, frozen.rows.single { it.line.elementUuid == fixture.elementUuid }.line.sourceQuantityMilli)
        assertEquals(12007, frozen.rows.single { it.line.elementUuid == fixture.elementUuid }.line.effectiveBillableQuantityMilli)
        assertEquals(0, frozen.rows.single { it.line.elementUuid == fixture.profileUuid }.line.unitPriceCents)
        assertEquals(12007, frozen.amounts.subtotalCents)
        assertEquals(QuoteDiscount.calculate(12007, 1234), frozen.amounts)
        assertEquals("DRAFT", record.status)
        assertTrue(frozen.publicNumber.startsWith("КШ-2026-"))
        assertTrue(frozen.publicNumber.contains("-ІП-"))
        assertFalse(frozen.publicNumber.contains(saved.seriesUuid))
        val revisionRows = history.revisions(saved.seriesUuid)
        var statusVersion = 1L
        for (status in EstimateWorkflowStatus.entries.reversed()) {
            assertTrue(history.setWorkflowStatus(saved.seriesUuid, statusVersion, status, 350))
            assertFalse(history.setWorkflowStatus(saved.seriesUuid, statusVersion, EstimateWorkflowStatus.DRAFT, 350))
            statusVersion++
            assertEquals(revisionRows, history.revisions(saved.seriesUuid))
            assertEquals(record.payload, history.frozenIndividual(saved.seriesUuid)!!.payload)
        }
        assertFailsWith<IndividualEstimateSaveFailure> { history.saveIndividual(original.copy(title = "Other"), 2026, 300) }
        val another = history.saveIndividual(original.copy(operationUuid = newRecordUuid()), 2026, 301)
        assertNotEquals(saved.seriesUuid, another.seriesUuid)
        assertEquals(before + 2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        // Failure after number/v1/status insertion must roll back every row and the counter.
        val rollbackCommand = original.copy(operationUuid = newRecordUuid())
        val failing = object : SqlDriver by driver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (sql.startsWith("INSERT INTO estimate_save_receipts")) error("Injected late receipt failure")
                return driver.execute(identifier, sql, parameters, binders)
            }
        }
        val countBefore = history.frozenIndividuals().size
        assertFails { ImmutableEstimateRepository(PrototypeDatabase(failing)).saveIndividual(rollbackCommand, 2026, 302) }
        assertEquals(countBefore, history.frozenIndividuals().size)
        assertNull(db.frozenEstimateQueries.selectEstimateSaveReceipt(rollbackCommand.operationUuid).executeAsOneOrNull())
        assertEquals(before + 2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        // Valid-looking field corruption must fail explicitly, even when amounts still reconcile.
        val envelope = Json.parseToJsonElement(record.payload).jsonObject
        val document = envelope.getValue("document").jsonObject
        for ((field, value) in listOf("title" to JsonPrimitive("Changed title"), "partial" to JsonPrimitive(true))) {
            val modified = JsonObject(envelope + ("document" to JsonObject(document + (field to value))))
            assertFails { decodeFrozenIndividualEstimate(modified.toString()) }
        }
        val rows = document.getValue("rows").jsonArray.toMutableList()
        val first = rows.first().jsonObject
        rows[0] = JsonObject(first + ("line" to JsonObject(first.getValue("line").jsonObject + ("title" to JsonPrimitive("Changed row")))))
        assertFails { decodeFrozenIndividualEstimate(JsonObject(envelope + ("document" to JsonObject(document + ("rows" to JsonArray(rows))))).toString()) }
        // History is independent from mutable pricing/source and settings.
        val prices = PriceRepository(db)
        prices.updateSalePrice(prices.find(fixture.priceId)!!, 25.5, 400)
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_CATALOG,
            assertFailsWith<IndividualEstimateSaveFailure> { history.saveIndividual(original.copy(operationUuid = newRecordUuid()), 2026, 401) }.reason)
        assertEquals(saved, history.saveIndividual(original, 2026, 402))
        assertEquals(before + 2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        val elements = MeasurementElementRepository(db)
        val element = elements.find(fixture.elementUuid)!!
        elements.edit(element.version, "changed source", element.approval, element.quantity, 405, businessRole = element.businessRole)
        assertEquals(IndividualEstimateSaveFailure.Reason.STALE_SOURCE,
            assertFailsWith<IndividualEstimateSaveFailure> { history.saveIndividual(original.copy(operationUuid = newRecordUuid()), 2026, 406) }.reason)
        val measurement = MeasurementRepository(db).findByUuid(fixture.measurementUuid)!!
        MeasurementRepository(db).delete(measurement.id)
        history.configureNumberPolicy(EstimateNumberPolicy("{year}/{sequence}/{suffix}", "NEW", 3, "NEW"))
        assertEquals(record.payload, history.frozenIndividual(saved.seriesUuid)!!.payload)
        assertEquals(frozen, decodeFrozenIndividualEstimate(history.frozenIndividual(saved.seriesUuid)!!.payload))
        // Partial documents require the exact omission set and current acknowledgement.
        val partial = MeasurementPricingFixture.seed(db)
        MeasurementElementRepository(db).createWish(partial.measurementUuid, "Optional unresolved", 500, approval = ElementApproval.APPROVED)
        val partialCommand = command(db, partial, partial = true)
        val next = db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence
        assertFailsWith<IndividualEstimateSaveFailure> { history.saveIndividual(partialCommand.copy(acknowledgedFingerprint = null), 2026, 501) }
        assertFailsWith<IndividualEstimateSaveFailure> { history.saveIndividual(partialCommand.copy(expectedOmissions = emptyList()), 2026, 501) }
        assertEquals(next, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        val partialSaved = history.saveIndividual(partialCommand, 2026, 501)
        assertTrue(decodeFrozenIndividualEstimate(history.frozenIndividual(partialSaved.seriesUuid)!!.payload).partial)
        // SQLite must not promote an exhausted counter to REAL.
        driver.execute(null, "UPDATE estimate_number_settings SET nextSequence = 9223372036854775807", 0)
        assertFails { history.saveIndividual(partialCommand.copy(operationUuid = newRecordUuid()), 2026, 502) }
        assertEquals(Long.MAX_VALUE, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        driver.execute(null, "UPDATE estimate_number_settings SET nextSequence = ${next + 1}", 0)
    }
}
