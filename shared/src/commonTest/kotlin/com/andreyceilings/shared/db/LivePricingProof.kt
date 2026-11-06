package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

/** Same assertions on JDBC and physical Android/Windows/iOS SQLCipher, no alternative pricing implementation. */
object LivePricingProof {
    data class Fixture(val projectId: Long, val measurementUuid: String, val elementUuid: String, val priceId: Long)
    fun seed(database: PrototypeDatabase): Fixture {
        val suffix = newRecordUuid()
        val project = ProjectRepository(database).createReturningId(null, "Pricing $suffix", createdAt = 100)
        val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Room", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")), newRecordUuid())
        MeasurementCreateStore(database) { 101 }.create(draft)
        val group = PriceGroupRepository(database).create("Pricing $suffix", 102, "м²", QuantityMode.CEILING_AREA)
        val priceId = PriceRepository(database).createInGroup("pricing.$suffix", "Canvas $suffix", group, 10.0, 103)
        val item = PriceRepository(database).find(priceId)!!
        val element = MeasurementElementRepository(database).createCatalogSelection(draft.recordUuid, item.uuid, ElementQuantity.CeilingArea, 104,
            approval = ElementApproval.APPROVED)
        MeasurementElementRepository(database).createWish(draft.recordUuid, "Discuss this", 105)
        return Fixture(project, draft.recordUuid, element.uuid, priceId)
    }

    fun run(driver: SqlDriver) {
        val database = PrototypeDatabase(driver)
        val fixture = seed(database)
        // Reject every SQL execute on the reader's driver. SQLDelight transaction control is delegated.
        var writeAttempts = 0
        val readOnly = object : SqlDriver by driver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                writeAttempts++
                error("P4A attempted SQL write")
            }
        }
        fun changes() = driver.executeQuery(null, "SELECT total_changes()", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value
        val beforeChanges = changes()
        val reader = LivePricingReader(PrototypeDatabase(readOnly))
        val loaded = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(2, loaded.source.elements.size) // NONE/unplaced plus Wish
        val roles = BusinessRoleBindings(loaded.source.signature, mapOf(fixture.elementUuid to BusinessRole.CANVAS))
        val preview = LivePricingEngine.buildPreview(loaded.source, loaded.catalog, roles)
        assertEquals(12000, preview.confirmedTotalCents)
        assertEquals(1, preview.unresolvedItems.size)
        assertTrue(preview.potentialIncomplete)
        assertTrue(preview.canFinalizeFull)
        assertTrue(LivePricingEngine.buildPreview(loaded.source, loaded.catalog).fatalProblems.any { it.code == PricingProblemCode.ROLE_UNDETERMINED })
        repeat(100) {
            val fresh = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid, loaded.source.signature, loaded.catalog.signature))
            assertEquals(preview, LivePricingEngine.buildPreview(fresh.source, fresh.catalog, roles))
        }
        assertEquals(0, writeAttempts)
        assertEquals(beforeChanges, changes())
        val measurement = MeasurementRepository(database).findByUuid(fixture.measurementUuid)!!
        val model = MeasurementEditModel(null, MeasurementEditStore(database) { 200 }.load(fixture.projectId, measurement.id))
        val draft = assertIs<PricingReadResult.Loaded>(loaded.withValidatedDraft(model, loaded.source.signature))
        assertEquals(loaded.source.elements, draft.source.elements)
        assertEquals(loaded.source.geometry, draft.source.geometry)
        assertEquals(beforeChanges, changes())
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE), loaded.withValidatedDraft(model, "old"))
        model.accept(MeasurementCreateIntent.Dimension("length", "5"))
        val changedDraft = assertIs<PricingReadResult.Loaded>(loaded.withValidatedDraft(model, loaded.source.signature))
        assertEquals(15.0, changedDraft.source.geometry.areaSquareMeters)
        assertNotEquals(loaded.source.signature, changedDraft.source.signature)
        assertEquals(12000, preview.confirmedTotalCents) // original snapshot remains immutable
        model.accept(MeasurementCreateIntent.Dimension("length", ""))
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY), loaded.withValidatedDraft(model, loaded.source.signature))
        assertEquals(beforeChanges, changes())
        val elements = MeasurementElementRepository(database)
        val e = elements.find(fixture.elementUuid)!!
        elements.edit(e.version, "child-only change", e.approval, e.quantity, 106)
        assertEquals(measurement.updatedAt, MeasurementRepository(database).findByUuid(fixture.measurementUuid)!!.updatedAt)
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE), reader.read(fixture.projectId, fixture.measurementUuid, loaded.source.signature))
        val afterChild = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
        assertNotEquals(loaded.source.signature, afterChild.source.signature)
        val prices = PriceRepository(database)
        prices.updateSalePrice(prices.find(fixture.priceId)!!, 20.0, 107)
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.STALE_CATALOG), reader.read(fixture.projectId, fixture.measurementUuid, expectedCatalogSignature = loaded.catalog.signature))
        val afterPrice = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(24000, LivePricingEngine.buildPreview(afterPrice.source, afterPrice.catalog,
            BusinessRoleBindings(afterPrice.source.signature, mapOf(fixture.elementUuid to BusinessRole.CANVAS))).confirmedTotalCents)
        prices.delete(fixture.priceId)
        val afterDelete = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
        val deletedPreview = LivePricingEngine.buildPreview(afterDelete.source, afterDelete.catalog,
            BusinessRoleBindings(afterDelete.source.signature, mapOf(fixture.elementUuid to BusinessRole.CANVAS)))
        assertTrue(deletedPreview.unresolvedItems.any { PricingProblemCode.CATALOG_MISSING in it.reasons })
        assertFalse(deletedPreview.canFinalizeFull)
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.PARENT_UNAVAILABLE), reader.read(-1, fixture.measurementUuid))
        assertEquals(PricingProblemCode.REVISION_UNAVAILABLE, reader.verifyFrozenRevision(fixture.projectId, "missing", "missing"))
        val history = ImmutableEstimateRepository(database)
        val created = history.createSeries(fixture.projectId, 2026, 200, ImmutableEstimateDraft("{}", "{}", 0.001, "kmp-v1", 10.0,
            listOf(ImmutableEstimateLine("legacy", "legacy", "шт.", 1.0, 10.0, 10.0))))
        assertEquals(PricingProblemCode.PROVENANCE_MISSING, reader.verifyFrozenRevision(fixture.projectId, created.seriesUuid, created.initialRevisionUuid))
        val other = ProjectRepository(database).createReturningId(null, "Other", createdAt = 200)
        assertEquals(PricingProblemCode.CROSS_PROJECT, reader.verifyFrozenRevision(other, created.seriesUuid, created.initialRevisionUuid))
        history.softDeleteSeries(created.seriesUuid, 201)
        assertEquals(PricingProblemCode.REVISION_UNAVAILABLE, reader.verifyFrozenRevision(fixture.projectId, created.seriesUuid, created.initialRevisionUuid))
        val failing = object : SqlDriver by driver {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = error("Injected database failure")
        }
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), LivePricingReader(PrototypeDatabase(failing)).read(fixture.projectId, fixture.measurementUuid))
        reader.closeReads()
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(0, writeAttempts)
    }
}
