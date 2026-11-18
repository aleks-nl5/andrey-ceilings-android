package com.andreyceilings.shared.estimate

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.db.FrozenIndividualEstimateProof
import com.andreyceilings.shared.db.FrozenRevisionProof
import com.andreyceilings.shared.db.MeasurementPricingFixture
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
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
    val frozen = ids.map { assertNotNull(history.exactIndividual(series, it)) }
    assertEquals(listOf(1, 2, 3), frozen.map { it.frozen.formatVersion })
    assertEquals(first.initialRevisionUuid, frozen.last().selectedRevisionUuid)

    val store = MeasurementElementsStore(db, { createdAt + 10 })
    try {
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

        val project = assertNotNull(ProjectRepository(db).find(fixture.projectId))
        ProjectRepository(db).update(project.id, project.clientId, "Renamed project", "Changed address",
            project.status, project.notes, 608)
        prices.updateSalePrice(assertNotNull(prices.find(fixture.priceId)), 150.0, 609)
        MeasurementRepository(db).delete(measurement.id)
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
