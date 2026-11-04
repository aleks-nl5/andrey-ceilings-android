package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Shared production proof on Android WAL, Windows encrypted JDBC and iOS SQLCipher. */
object PointPositionProof {
    suspend fun run(driver: SqlDriver): Unit = coroutineScope {
        val db = PrototypeDatabase(driver); val f = PointPlacementProof.fixture(db, "Move proof")
        val points = PointPlacementRepository(db); val elements = MeasurementElementRepository(db)
        val measurements = MeasurementRepository(db)
        val geometry = measurements.find(f.id)!!
        fun element() = elements.find(f.element.uuid)!!
        val first = points.addPoint(element().version, f.contourVersion, 1000, 1000, 10)
        val second = points.addPoint(element().version, f.contourVersion, 2000, 1000, 11)
        val before = element()
        val observed = Channel<List<PointPlacement>>(Channel.UNLIMITED)
        val collector = launch { points.observeForMeasurement(f.element.measurementUuid).collect { observed.send(it) } }
        suspend fun awaitPosition(x: Long) = withTimeout(10_000) { while (observed.receive().find { it.uuid == first.uuid }?.xMm != x) { } }
        try {
            awaitPosition(1000)
            val moved = points.movePoint(before.version, first, f.contourVersion, 1500, 1200, PointPrecision.EXACT, 12, "move-${f.id}")
            awaitPosition(1500)
            assertEquals(first.uuid, moved.point.uuid); assertEquals(first.elementUuid, moved.point.elementUuid)
            assertEquals(first.createdAt, moved.point.createdAt); assertEquals(first.contourUpdatedAt, moved.point.contourUpdatedAt)
            assertEquals(2000L, moved.element.placedQuantityMilli); assertEquals(ElementPlacement.APPROXIMATE, moved.element.placement)
            assertEquals(PointPrecision.EXACT, moved.point.precision); assertEquals(second, points.find(second.uuid))
            assertEquals(moved, points.movePoint(before.version, first, f.contourVersion, 1500, 1200, PointPrecision.EXACT, 99, "move-${f.id}"))
            assertFailsWith<PointPlacementFailure.RetryConflict> {
                points.movePoint(before.version, first, f.contourVersion, 1501, 1200, PointPrecision.EXACT, 13, "move-${f.id}")
            }
            assertFailsWith<PointPlacementFailure.StaleVersion> { points.movePoint(before.version, first, f.contourVersion, 1600, 1200, PointPrecision.EXACT, 13) }
            assertFailsWith<PointPlacementFailure.StaleContour> { points.movePoint(element().version, moved.point, f.contourVersion + 1, 1600, 1200, PointPrecision.EXACT, 13) }
            for ((x,y) in listOf(0L to 1000L, -1L to 1000L, 999999L to 999999L, Long.MAX_VALUE to 1L))
                assertFailsWith<PointPlacementFailure.OutsideContour> { points.movePoint(element().version, moved.point, f.contourVersion, x, y, PointPrecision.EXACT, 13) }
            assertEquals(moved.point, points.find(first.uuid))
            val exactSecond = points.movePoint(element().version, second, f.contourVersion, 2300, 1400, PointPrecision.EXACT, 14)
            assertEquals(ElementPlacement.EXACT, exactSecond.element.placement)
            val freeDrag = points.movePoint(element().version, moved.point, f.contourVersion, 1700, 1500, PointPrecision.APPROXIMATE, 14)
            assertEquals(PointPrecision.APPROXIMATE, freeDrag.point.precision)
            assertEquals(ElementPlacement.APPROXIMATE, freeDrag.element.placement)
            assertEquals(2000L, freeDrag.element.placedQuantityMilli)
            assertEquals(geometry, measurements.find(f.id), "Move leaves captured bytes/totals/version/name/notes unchanged")
            assertFails { driver.execute(null, "UPDATE point_placements SET xMm=1800,updatedAt=updatedAt+1 WHERE uuid='${first.uuid}'", 0) }
            assertFails { driver.execute(null, "UPDATE point_placements SET precision='exact',updatedAt=updatedAt+1 WHERE uuid='${first.uuid}'", 0) }
            assertFails { driver.execute(null, "UPDATE point_placements SET elementUuid='${second.elementUuid}',uuid='other',updatedAt=updatedAt+1 WHERE uuid='${first.uuid}'", 0) }
            assertFails { driver.execute(null, "UPDATE point_position_operations SET xMm=1 WHERE uuid='move-${f.id}'", 0) }
            assertFailsWith<PointPlacementFailure.StaleVersion> {
                points.movePoint(before.version, first, f.contourVersion, 1500, 1200, PointPrecision.EXACT, 20, "move-${f.id}")
            }
            val editor = MeasurementEditStore(db) { 30 }
            val document = editor.load(f.project, f.id)
            val loaded = assertIs<PointOverlayState.Loaded>(editor.observePoints(document).first { it !is PointOverlayState.Loading })
            assertEquals(setOf(first.uuid, second.uuid), loaded.points.map { it.uuid }.toSet())
            assertEquals(PointOverlayState.Missing, editor.observePoints(document.copy(draft = document.draft.copy(recordUuid = "old-uuid"))).first { it !is PointOverlayState.Loading })
            val named = editor.update(document, document.draft.copy(name = "Renamed", notes = "Changed notes"))
            assertEquals(loaded.points, points.forMeasurement(f.element.measurementUuid))
            assertFailsWith<MeasurementContourHasPoints> { editor.update(named, named.draft.reduce(MeasurementCreateIntent.Dimension("length", "8"))) }
            editor.closePointReads()
            assertEquals(PointOverlayState.Unavailable, editor.observePoints(document).first())
            val foreign = PointPlacementProof.fixture(db, "Other ceiling")
            assertFailsWith<PointPlacementFailure.ParentUnavailable> {
                points.movePoint(element().version.copy(measurementUuid = foreign.element.measurementUuid), freeDrag.point,
                    f.contourVersion, 1800, 1600, PointPrecision.EXACT, 40)
            }
            points.removePoint(element().version, freeDrag.point, 40)
            assertEquals(ElementPlacement.EXACT, element().placement)
            assertFailsWith<PointPlacementFailure.StaleVersion> {
                points.movePoint(element().version, freeDrag.point, named.updatedAt, 1800, 1600, PointPrecision.EXACT, 41)
            }
            elements.delete(element().version, 42)
            assertEquals(0L, element().placedQuantityMilli)
            assertTrue(points.forMeasurement(f.element.measurementUuid).isEmpty())
            assertTrue(MeasurementElementMigrationProof.rows(driver, "PRAGMA foreign_key_check", 4).isEmpty())
            directSqlInteriorParity(db, f)
        } finally { collector.cancel(); observed.close() }
    }

    private fun directSqlInteriorParity(db: PrototypeDatabase, fixture: PointPlacementProof.Fixture) {
        val item = (fixture.element.source as ElementSource.CatalogSelection).priceItemUuid
        val square = listOf(0L to 0L, 5000L to 0L, 5000L to 4000L, 0L to 4000L)
        val l = listOf(0L to 0L, 5000L to 0L, 5000L to 2000L, 2000L to 2000L, 2000L to 4000L, 0L to 4000L)
        val diagonal = listOf(-2000L to -2000L, 1000L to -1000L, 0L to 2000L, -3000L to 1000L)
        for (vertices in listOf(square, l, l.reversed(), diagonal, diagonal.reversed())) {
            val geometry = com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract.capture("polygon",
                vertices.map { com.andreyceilings.shared.calculation.CeilingPointMm(it.first, it.second) }, emptyMap(), 0.001)
            val measurements = MeasurementRepository(db)
            val id = measurements.createReturningId(fixture.project, "SQL interior", shape = "polygon",
                drawingJson = MeasurementGeometryCodec.write(geometry), areaSquareMeters = geometry.areaSquareMeters,
                perimeterMeters = geometry.perimeterMeters, createdAt = 2)
            val parent = measurements.find(id)!!
            val elements = MeasurementElementRepository(db); val points = PointPlacementRepository(db)
            val element = elements.createCatalogSelection(parent.uuid, item, ElementQuantity.Exact(6000), 3)
            val initial = listOf(1000L to 1000L, -1500L to -1000L).first { pointStrictlyInside(geometry, it.first, it.second) }
            var point = points.addPoint(element.version, parent.updatedAt, initial.first, initial.second, 4)
            for ((x,y) in listOf(initial, 1500L to 1500L, 4500L to 3500L, -1000L to -1000L, vertices.first(), 999999L to 999999L, Long.MAX_VALUE to 1L)) {
                val current = elements.find(element.uuid)!!; val receipt = newRecordUuid()
                val expected = point
                val operation = {
                    db.transaction {
                        db.pointPlacementsQueries.insertMoveOperation(receipt, expected.uuid, expected.updatedAt,
                            current.updatedAt, parent.updatedAt, x, y, "exact", 50)
                        db.pointPlacementsQueries.movePoint(x, y, "exact", receipt, 50, expected.uuid, current.uuid, expected.updatedAt)
                    }
                }
                if (pointStrictlyInside(geometry, x, y)) {
                    operation()
                    point = points.find(point.uuid)!!
                    assertEquals(PointPositionMm(x,y), PointPositionMm(point.xMm, point.yMm))
                } else {
                    assertFails("Raw SQL must reject boundary/outside/bounds: $x,$y") { operation() }
                    assertEquals(expected, points.find(expected.uuid))
                }
            }
        }
    }
}
