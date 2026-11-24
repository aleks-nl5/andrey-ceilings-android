package com.andreyceilings.shared.estimate

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.db.FrozenIndividualEstimateProof
import com.andreyceilings.shared.db.FrozenRevisionProof
import com.andreyceilings.shared.db.MeasurementElementRepository
import com.andreyceilings.shared.db.MeasurementPricingFixture
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceGroupRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.localization.AppLanguage
import com.andreyceilings.shared.localization.AppLanguagePreference
import com.andreyceilings.shared.localization.AppLanguageState
import com.andreyceilings.shared.localization.AppLanguageStorage
import com.andreyceilings.shared.measurement.MeasurementCreateIntent
import com.andreyceilings.shared.measurement.MeasurementEditStore
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.measurement.reduce
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

internal suspend fun proveExactEstimateDocuments(driver: SqlDriver) {
    val db = PrototypeDatabase(driver)
    val fixture = MeasurementPricingFixture.seed(db)
    val history = ImmutableEstimateRepository(db)
    val createdAt = Instant.parse("2026-11-17T23:30:00Z").toEpochMilliseconds()
    val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, createdAt)
    val series = first.seriesUuid
    assertTrue(history.selectRevision(series, first.initialRevisionUuid, 1, createdAt + 1))
    val second = history.appendIndividual(FrozenRevisionProof.request(db, fixture, series), createdAt + 2)
    val measurement = assertNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
    val edit = MeasurementEditStore(db) { 603 }
    val original = edit.load(fixture.projectId, measurement.id)
    edit.update(original, original.draft.reduce(MeasurementCreateIntent.Dimension("length", "4.12"))
        .reduce(MeasurementCreateIntent.Dimension("width", "3.34")))
    val prices = PriceRepository(db)
    prices.updateSalePrice(assertNotNull(prices.find(fixture.priceId)), 100.0, 604)
    val third = history.appendIndividual(FrozenRevisionProof.request(db, fixture, series), createdAt + 5)
    val ids = listOf(first, second, third).map { it.initialRevisionUuid }
    assertTrue(history.setWorkflowStatus(ids[0], 1, EstimateWorkflowStatus.APPROVED, createdAt + 6))
    assertTrue(history.setWorkflowStatus(ids[1], 1, EstimateWorkflowStatus.REJECTED, createdAt + 6))
    assertTrue(history.setWorkflowStatus(ids[2], 1, EstimateWorkflowStatus.SENT, createdAt + 6))
    val frozen = ids.map { assertNotNull(history.exactIndividual(series, it)) }
    assertEquals(listOf(1, 2, 3), frozen.map { it.frozen.formatVersion })
    assertEquals(first.initialRevisionUuid, frozen.last().selectedRevisionUuid)

    val store = MeasurementElementsStore(db, { createdAt + 10 })
    try {
        val rooms = store.consolidatedRooms(fixture.projectId).rooms
        val refs = rooms.single().revisions.associateBy { it.ref.revisionUuid }
        assertEquals(ids.reversed(), rooms.single().revisions.map { it.ref.revisionUuid })
        val projectUuid = frozen.first().frozen.source.projectUuid
        val old = refs.getValue(ids.first()).ref
        val projects = ProjectRepository(db)
        val project = assertNotNull(projects.find(fixture.projectId))
        projects.update(project.id, project.clientId, "Назва другої ревізії", project.address,
            project.status, project.notes, 606)
        val secondRoom = MeasurementPricingFixture.seed(db, fixture.projectId)
        val secondRoomQuote = history.saveIndividual(FrozenIndividualEstimateProof.command(db, secondRoom), 2026, createdAt + 7)
        val secondSeries = FrozenRevisionProof.withLegacyAlternative(driver, listOf(secondRoomQuote.seriesUuid), secondRoom.measurementUuid) {
            history.saveIndividual(FrozenIndividualEstimateProof.command(db, secondRoom), 2026, createdAt + 8)
        }
        val foreignRoom = MeasurementPricingFixture.seed(db)
        val foreignQuote = history.saveIndividual(FrozenIndividualEstimateProof.command(db, foreignRoom), 2026, createdAt + 9)
        val foreign = store.consolidatedRooms(foreignRoom.projectId).rooms.single().revisions.single().ref
        val secondOptions = store.consolidatedRooms(fixture.projectId).rooms
            .single { it.uuid == secondRoom.measurementUuid }.revisions
        assertEquals(setOf(secondRoomQuote.seriesUuid, secondSeries.seriesUuid), secondOptions.map { it.ref.seriesUuid }.toSet())
        val other = secondOptions.single { it.ref.seriesUuid == secondRoomQuote.seriesUuid }.ref
        val secondRoomFrozen = assertNotNull(history.exactIndividual(other.seriesUuid, other.revisionUuid)).frozen
        projects.update(project.id, project.clientId, "Квартира / Центр", project.address,
            project.status, project.notes, 607)
        for ((index, id) in ids.withIndex()) {
            val selected = refs.getValue(id).ref
            val result = assertIs<ConsolidatedDocumentResult.Ready>(
                store.consolidatedDocument(fixture.projectId, projectUuid, listOf(selected, other))
            ).document
            assertEquals("Квартира / Центр", result.currentProjectTitle)
            assertEquals("zvedenyi-koshtorys-Квартира-Центр.pdf", result.fileName)
            assertEquals(listOf("Назва другої ревізії", project.title), result.sections.map { it.document.project })
            val chosen = result.sections.single { it.document.publicNumber == frozen[index].frozen.publicNumber }
            assertEquals(index + 1L, chosen.document.version)
            assertEquals(frozen[index].status, chosen.status)
            assertEquals(frozen[index].frozen.amounts, chosen.document.amounts)
            assertEquals(listOf(secondRoomFrozen.publicNumber, frozen[index].frozen.publicNumber),
                result.sections.map { it.document.publicNumber })
            assertEquals(frozen[index].frozen.amounts.finalCents +
                secondRoomFrozen.amounts.finalCents,
                result.totalCents)
        }
        suspend fun checkRejected(problem: ConsolidatedDocumentProblem, vararg selection: ConsolidatedDocumentRef) {
            assertEquals(ConsolidatedDocumentResult.Rejected(problem),
                store.consolidatedDocument(fixture.projectId, projectUuid, selection.toList()))
        }
        checkRejected(ConsolidatedDocumentProblem.EMPTY)
        checkRejected(ConsolidatedDocumentProblem.DUPLICATE, old, old)
        checkRejected(ConsolidatedDocumentProblem.FOREIGN_REVISION, old.copy(measurementUuid = secondRoom.measurementUuid))
        checkRejected(ConsolidatedDocumentProblem.FOREIGN_REVISION, foreign)
        checkRejected(ConsolidatedDocumentProblem.FOREIGN_REVISION, foreign.copy(measurementUuid = fixture.measurementUuid))
        assertEquals(foreignQuote.initialRevisionUuid, foreign.revisionUuid)
        checkRejected(ConsolidatedDocumentProblem.DUPLICATE, *secondOptions.map { it.ref }.toTypedArray())
        checkRejected(ConsolidatedDocumentProblem.STALE, old.copy(fingerprint = "invalid"))
        checkRejected(ConsolidatedDocumentProblem.REVISION_MISSING, old.copy(revisionUuid = fixture.elementUuid))
        driver.execute(null, "DELETE FROM estimate_save_receipts WHERE revisionUuid = ?", 1) { bindString(0, other.revisionUuid) }
        checkRejected(ConsolidatedDocumentProblem.INVALID_REVISION, old, other)
        assertIs<ConsolidatedDocumentResult.Ready>(store.consolidatedDocument(fixture.projectId, projectUuid, listOf(old)))
        assertTrue(store.consolidatedRooms(fixture.projectId).rooms.single { it.uuid == secondRoom.measurementUuid }.unavailableRevisions)

        val documents = ids.map { assertNotNull(store.estimateDocument(series, it)) }
        val utc = assertNotNull(store.estimateDocument(series, first.initialRevisionUuid, TimeZone.UTC))
        val stockholm = assertNotNull(store.estimateDocument(series, first.initialRevisionUuid, TimeZone.of("Europe/Stockholm")))
        assertEquals("2026-11-17", utc.createdOn)
        assertEquals("2026-11-18", stockholm.createdOn)
        assertEquals(utc.fileName, stockholm.fileName)
        assertEquals(createdAt, frozen.first().createdAt)
        assertEquals(listOf(1L, 2L, 3L), documents.map { it.version })
        assertEquals(listOf(12007L, 12000L, 137610L), documents.map { it.amounts.subtotalCents })
        assertEquals(1234, documents.first().amounts.discountBasisPoints)
        assertTrue(documents.first().rows.any { it.unitPriceCents == 0L })
        assertEquals("13,7608", documents.last().measuredArea)
        val canvas = documents.last().rows.single { it.roundedAreaMilli != null }
        assertEquals(13761L, canvas.roundedAreaMilli)
        assertEquals(13761L, canvas.quantityMilli)
        assertEquals(137610L, canvas.totalCents)
        assertTrue(documents.all { it.publicNumber == documents.first().publicNumber })
        assertNotEquals(documents.first().drawing.contour, documents.last().drawing.contour)
        assertNull(store.estimateDocument(first.seriesUuid, fixture.elementUuid))

        val liveMeasurements = MeasurementRepository(db).all()
        val liveElements = MeasurementElementRepository(db).forMeasurement(fixture.measurementUuid)
        val livePrices = prices.all()
        val liveGroups = PriceGroupRepository(db).all()
        val languages = AppLanguageState(object : AppLanguageStorage {
            override fun read(): String? = null
            override fun write(value: String) = Unit
        })
        for (preference in listOf(AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN), AppLanguagePreference.System)) {
            languages.select(preference)
            assertEquals(liveMeasurements, MeasurementRepository(db).all())
            assertEquals(liveElements, MeasurementElementRepository(db).forMeasurement(fixture.measurementUuid))
            assertEquals(livePrices, prices.all())
            assertEquals(liveGroups, PriceGroupRepository(db).all())
            assertEquals(frozen, ids.map { history.exactIndividual(series, it) })
            val onSelectedLanguage = ids.map { assertNotNull(store.estimateDocument(series, it)) }
            assertEquals(documents.map { it.rows }, onSelectedLanguage.map { it.rows })
            assertEquals(documents.map { it.amounts }, onSelectedLanguage.map { it.amounts })
        }

        projects.update(project.id, project.clientId, "Renamed project", "Changed address",
            project.status, project.notes, 608)
        prices.updateSalePrice(assertNotNull(prices.find(fixture.priceId)), 150.0, 609)
        MeasurementRepository(db).delete(measurement.id)
        checkRejected(ConsolidatedDocumentProblem.ROOM_MISSING, old)
        val printedAgain = ids.map { assertNotNull(store.estimateDocument(series, it)) }
        assertEquals(documents.map { it.rows }, printedAgain.map { it.rows })
        assertEquals(documents.map { it.drawing }, printedAgain.map { it.drawing })
        assertEquals(documents.map { it.project }, printedAgain.map { it.project })
        assertTrue(printedAgain.all { it.currentAddress == "Changed address" })
        assertEquals(frozen, ids.map { history.exactIndividual(series, it) })
    } finally {
        store.closeAndAwaitWrites()
    }
}
