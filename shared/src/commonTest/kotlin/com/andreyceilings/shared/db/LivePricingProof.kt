package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.prices.*
import kotlin.test.*

/** Same assertions on JDBC and physical Android/Windows/iOS SQLCipher, no alternative pricing implementation. */
object LivePricingProof {
    fun seed(database: PrototypeDatabase) = MeasurementPricingFixture.seed(database)

    private suspend fun pointMoveKeepsPricingAvailable(driver: SqlDriver) {
        val database = PrototypeDatabase(driver)
        val fixture = seed(database)
        val measurements = MeasurementRepository(database)
        val elements = MeasurementElementRepository(database)
        val points = PointPlacementRepository(database)
        val initial = assertNotNull(measurements.findByUuid(fixture.measurementUuid))
        val reader = LivePricingReader(database)
        fun read() = reader.read(fixture.projectId, fixture.measurementUuid)
        fun walls() = database.livePricingQueries.selectPricingWalls(fixture.measurementUuid)
            .executeAsList().filter { it.active == 1L }

        val originalPricing = assertIs<PricingReadResult.Loaded>(read())
        val history = ImmutableEstimateRepository(database)
        val savedQuote = history.saveIndividual(FrozenIndividualEstimateProof.command(database, fixture), 2026, 109)
        val frozenBefore = assertNotNull(history.frozenIndividual(savedQuote.seriesUuid))
        val group = PriceGroupRepository(database).all().first().uuid
        val prices = PriceRepository(database)
        val priceId = prices.createInGroup("pricing.point", "Test chandelier", group, 25.0, 110,
            unit = "шт.", quantityMode = QuantityMode.MANUAL, placementKind = PlacementKind.POINT)
        val element = elements.createCatalogSelection(fixture.measurementUuid, assertNotNull(prices.find(priceId)).uuid,
            ElementQuantity.Exact(1000), 111, approval = ElementApproval.APPROVED, businessRole = BusinessRole.ADDITIONAL_WORK)
        val added = points.addPoint(element.version, initial.updatedAt, 500, 600, 112)
        assertIs<PricingReadResult.Loaded>(read())
        val direct = points.movePoint(assertNotNull(elements.find(element.uuid)).version, added, initial.updatedAt,
            600, 700, PointPrecision.APPROXIMATE, 113).point
        assertEquals(initial.updatedAt, assertNotNull(measurements.find(initial.id)).updatedAt)
        val before = assertIs<PricingReadResult.Loaded>(read())
        val beforeWalls = walls()
        val store = MeasurementEditStore(database) { 120 }
        val model = MeasurementEditModel(store, store.load(fixture.projectId, initial.id))
        model.selectPoint(direct.uuid)
        model.beginPointDrag(direct.uuid)
        model.previewPointDrag(1000.0, 1200.0)
        model.finishPointDrag()
        assertTrue(model.state.value.dirty)
        assertEquals(1000L, model.displayPoints().single().xMm)
        assertEquals(direct, points.forMeasurement(fixture.measurementUuid).single())
        assertEquals(before, read())
        assertEquals(beforeWalls, walls())

        model.save()
        assertNull(model.state.value.error)
        assertTrue(model.state.value.saved)
        val reopened = store.load(fixture.projectId, initial.id)
        assertEquals(initial.drawingJson, reopened.sourceDrawingJson)
        assertEquals(120L, reopened.updatedAt)
        assertEquals(1000L, reopened.placements.points.single().xMm)
        assertEquals(1200L, reopened.placements.points.single().yMm)
        val savedWalls = walls()
        val after = assertIs<PricingReadResult.Loaded>(read(),
            "Save advanced the measurement to ${reopened.updatedAt}, wall versions are ${savedWalls.map { it.geometryVersion }}")
        assertEquals(beforeWalls.map { it.copy(geometryVersion = reopened.updatedAt) }, savedWalls)
        assertEquals(before.source.geometry, after.source.geometry)
        assertNotEquals(before.source.signature, after.source.signature)
        assertEquals(before.buildPreview().confirmedTotalCents, after.buildPreview().confirmedTotalCents)
        assertEquals(14500L, after.buildPreview().confirmedTotalCents)
        assertTrue(after.buildPreview().canFinalizeFull)
        assertTrue(after.buildPreview().fatalProblems.isEmpty())
        assertEquals(ElementQuantity.Exact(1000), assertNotNull(elements.find(element.uuid)).quantity)
        val quote = TransientQuotePresenter(fixture.measurementUuid)
        quote.acceptRead(quote.beginRead(), after)
        assertNotNull(quote.saveRequest(fixture.projectId, "After point move"))
        quote.close()
        assertEquals(frozenBefore, history.frozenIndividual(savedQuote.seriesUuid))

        val moved = reopened.placements.points.single()
        points.removePoint(assertNotNull(elements.find(element.uuid)).version, moved, 121)
        assertTrue(store.load(fixture.projectId, initial.id).placements.points.isEmpty())
        assertEquals(originalPricing.source.geometry, assertIs<PricingReadResult.Loaded>(read()).source.geometry)
    }

    suspend fun run(driver: SqlDriver) {
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
        assertEquals(3, loaded.source.elements.size) // NONE/unplaced plus Wish
        val roles = BusinessRoleBindings(loaded.source.signature, mapOf(fixture.elementUuid to BusinessRole.CANVAS, fixture.profileUuid to BusinessRole.PRIMARY_PROFILE))
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
        // P4B2: real coherent physical snapshot and real presenter; every SQL execute is forbidden.
        val quote = TransientQuotePresenter(fixture.measurementUuid)
        quote.acceptRead(quote.beginRead(), loaded)
        assertEquals(12000, quote.state.value.amounts?.subtotalCents)
        quote.editDiscount("12,50"); assertNull(quote.state.value.amounts)
        quote.confirmDiscount(); assertEquals(10500, quote.state.value.amounts?.finalCents)
        quote.editQuantity(fixture.elementUuid, "13")
        assertNull(quote.state.value.amounts)
        quote.confirmQuantity(fixture.elementUuid)
        assertEquals(13000, quote.state.value.amounts?.subtotalCents)
        quote.resetQuantity(fixture.elementUuid)
        quote.acceptRead(quote.beginRead(), reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(10500, quote.state.value.amounts?.finalCents)
        assertEquals(loaded.source, assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid)).source)
        quote.close()
        assertNull(quote.state.value.amounts)
        assertEquals(beforeChanges, changes())
        assertEquals(0, writeAttempts)
        val measurement = MeasurementRepository(database).findByUuid(fixture.measurementUuid)!!
        val model = MeasurementEditModel(null, MeasurementEditStore(database) { 200 }.load(fixture.projectId, measurement.id))
        val quoteStore = MeasurementElementsStore(database, { 200 }, kotlinx.coroutines.Dispatchers.Unconfined)
        val sessions = MeasurementQuoteSession()
        val details = AppNavigationState()
            .selectTab(AppRoute.MEASUREMENTS)
            .openMeasurement(fixture.projectId, measurement.id)
        sessions.navigate(details)
        val firstVisit = sessions.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, quoteStore)
        firstVisit.acceptRead(firstVisit.beginRead(), loaded)
        firstVisit.editDiscount("10"); firstVisit.confirmDiscount()
        sessions.navigate(details.openMeasurementQuote()); sessions.navigate(details)
        assertSame(firstVisit, sessions.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, quoteStore))
        assertEquals("10", firstVisit.state.value.discountText)
        assertNull(firstVisit.state.value.amounts)
        sessions.navigate(details.editMeasurement(fixture.projectId, measurement.id)); sessions.navigate(details)
        assertSame(firstVisit, sessions.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, quoteStore))
        sessions.navigate(details.back())

        val quoteRoute = details.openMeasurementQuote()
        sessions.navigate(quoteRoute)
        val livingQuote = sessions.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, quoteStore)
        assertNotEquals(firstVisit.sessionId, livingQuote.sessionId)
        assertEquals("0", livingQuote.state.value.discountText)
        livingQuote.acceptRead(livingQuote.beginRead(), loaded)
        livingQuote.editDiscount("10")
        livingQuote.confirmDiscount()
        livingQuote.editQuantity(fixture.elementUuid, "13")
        livingQuote.confirmQuantity(fixture.elementUuid)
        assertEquals(11700L, livingQuote.state.value.amounts?.finalCents)

        val restoredQuote = AppNavigationState.restore(quoteRoute.snapshot())
        assertTrue(restoredQuote.measurementQuote)
        sessions.navigate(restoredQuote)
        val restoredVisit = sessions.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, quoteStore)
        assertSame(livingQuote, restoredVisit)
        assertEquals("10", restoredVisit.state.value.discountText)
        assertEquals("13", restoredVisit.state.value.edits[fixture.elementUuid]?.text)
        assertNull(restoredVisit.state.value.amounts, "Restoration must invalidate the displayed total until repricing")
        restoredVisit.acceptRead(restoredVisit.beginRead(), reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(11700L, restoredVisit.state.value.amounts?.finalCents)

        sessions.clear()
        assertNull(livingQuote.state.value.amounts)
        val freshSessions = MeasurementQuoteSession()
        freshSessions.navigate(restoredQuote)
        val newVisit = freshSessions.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, quoteStore)
        assertNotEquals(livingQuote.sessionId, newVisit.sessionId)
        assertNotEquals(livingQuote.compositionUuid, newVisit.compositionUuid)
        assertEquals("0", newVisit.state.value.discountText)
        assertTrue(newVisit.state.value.edits.isEmpty())
        newVisit.acceptRead(newVisit.beginRead(), reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(12000L, newVisit.state.value.amounts?.finalCents)
        quoteStore.close()
        newVisit.acceptRead(newVisit.beginRead(), loaded); assertNull(newVisit.state.value.amounts)
        freshSessions.clear()
        assertEquals(beforeChanges, changes())
        val basis = DraftPricingBasis.Saved(loaded)
        val draft = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(model, model.state.value, basis))
        assertEquals(loaded.source.elements.toSet(), draft.projection.elements.toSet())
        assertEquals(loaded.source.geometry, draft.projection.geometry)
        assertEquals(preview.confirmedTotalCents, draft.preview.confirmedTotalCents)
        assertEquals(beforeChanges, changes())
        val oldState = model.state.value
        model.accept(MeasurementCreateIntent.Dimension("length", "5"))
        assertEquals(DraftPricingResult.Unavailable(PricingProblemCode.STALE_SOURCE), projectDraftPricing(model, oldState, basis))
        val changedDraft = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(model, model.state.value, basis))
        assertEquals(15.0, changedDraft.projection.geometry.areaSquareMeters)
        assertEquals(15000L, changedDraft.preview.confirmedTotalCents)
        assertNotEquals(loaded.source.signature, changedDraft.projection.signature)
        assertEquals(12000, preview.confirmedTotalCents)
        model.accept(MeasurementCreateIntent.Dimension("length", ""))
        assertEquals(DraftPricingResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY),
            projectDraftPricing(model, model.state.value, basis))
        assertEquals(beforeChanges, changes())
        val createStore = MeasurementEditStore(database) { 205 }
        val creation = MeasurementEditModel(createStore, MeasurementEditDocument(0L, 0L,
            MeasurementCreateDraft(fixture.projectId, MeasurementPrecision.ONE_MILLIMETER, "Pre-save",
                outline = MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")))))
        val picker = createStore.placementCatalog()
        val canvas = picker.single { it.uuid == (loaded.source.elements.single { e -> e.businessRole == BusinessRole.CANVAS }
            .source as ElementSource.CatalogSelection).priceItemUuid }
        val profile = picker.single { it.uuid == (loaded.source.elements.single { e -> e.businessRole == BusinessRole.PRIMARY_PROFILE }
            .source as ElementSource.CatalogSelection).priceItemUuid }
        assertTrue(creation.chooseMaterial(BusinessRole.CANVAS, canvas))
        assertTrue(creation.chooseMaterial(BusinessRole.PRIMARY_PROFILE, profile))
        val additional = picker.firstOrNull { it.placementKind == PlacementKind.POINT && it.mode == QuantityMode.MANUAL && it.unit == "шт." }
        additional?.let { creation.addCatalogWork(it, ElementQuantity.Exact(6000), place = false) }
        val createBasis = DraftPricingBasis.Created(loaded.source.projectUuid, loaded.catalog)
        val beforeSave = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(creation, creation.state.value, createBasis))
        assertEquals(12000L, beforeSave.preview.confirmedTotalCents)
        additional?.let { work ->
            val pointRow = beforeSave.preview.priceableLines.single { it.catalogItemUuid == work.uuid }
            assertEquals(6000L, pointRow.effectiveBillableQuantityMilli)
            assertTrue(PricingProblemCode.PLACEMENT_UNPLACED in pointRow.reviewFlags)
            creation.undo()
            val undone = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(creation, creation.state.value, createBasis))
            assertTrue(undone.preview.priceableLines.none { it.catalogItemUuid == work.uuid })
            creation.redo()
            val redone = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(creation, creation.state.value, createBasis))
            assertEquals(beforeSave.preview.priceableLines, redone.preview.priceableLines)
        }
        assertEquals(beforeChanges, changes())
        assertTrue(creation.saveInWorkspace(forQuote = true))
        val committed = creation.state.value
        assertFalse(committed.creating)
        assertFalse(committed.dirty)
        val newCanonical = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, committed.draft.recordUuid))
        assertEquals(beforeSave.preview.priceableLines, newCanonical.buildPreview().priceableLines)
        assertEquals(beforeSave.preview.confirmedTotalCents, newCanonical.buildPreview().confirmedTotalCents)
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
            BusinessRoleBindings(afterPrice.source.signature, mapOf(fixture.elementUuid to BusinessRole.CANVAS, fixture.profileUuid to BusinessRole.PRIMARY_PROFILE))).confirmedTotalCents)
        prices.delete(fixture.priceId)
        val afterDelete = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
        val deletedPreview = LivePricingEngine.buildPreview(afterDelete.source, afterDelete.catalog,
            BusinessRoleBindings(afterDelete.source.signature, mapOf(fixture.elementUuid to BusinessRole.CANVAS, fixture.profileUuid to BusinessRole.PRIMARY_PROFILE)))
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
        var beginAttempts = 0
        val cannotBegin = object : SqlDriver by driver {
            override fun newTransaction(): Nothing { beginAttempts++; error("Closed session began transaction") }
        }
        val closedReader = LivePricingReader(PrototypeDatabase(cannotBegin))
        closedReader.closeReads()
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), closedReader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(PricingProblemCode.DATABASE_UNAVAILABLE, closedReader.verifyFrozenRevision(fixture.projectId, created.seriesUuid, created.initialRevisionUuid))
        assertEquals(0, beginAttempts)
        reader.closeReads()
        assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), reader.read(fixture.projectId, fixture.measurementUuid))
        assertEquals(0, writeAttempts)
        FrozenIndividualEstimateProof.run(driver)
        pointMoveKeepsPricingAvailable(driver)
    }
}
