package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

/** Identical production repository/domain proof on Android WAL, Windows SQLCipher and iOS Simulator. */
object PointPlacementProof {
    private fun draft(project: Long) = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER,
        "Кухня", "", MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5.1", "width" to "4")))
    data class Fixture(val project: Long, val id: Long, val element: MeasurementElement, val contourVersion: Long)
    fun fixture(db: PrototypeDatabase, name: String = "Кухня"): Fixture {
        val project = ProjectRepository(db).createReturningId(null, name, createdAt = 1)
        val draft = draft(project)
        val id = MeasurementCreateStore(db) { 2 }.create(draft.copy(name = name))
        val row = checkNotNull(MeasurementRepository(db).find(id))
        val prices = PriceRepository(db)
        val group = PriceGroupRepository(db).all().first()
        val priceId = prices.createInGroup("fixture.${newRecordUuid()}", "Point ${newRecordUuid()}", group.uuid, 220.0, 3,
            "шт.", com.andreyceilings.shared.prices.QuantityMode.MANUAL, com.andreyceilings.shared.prices.PlacementKind.POINT)
        val spot = prices.find(priceId)!!
        val element = MeasurementElementRepository(db).createCatalogSelection(row.uuid, spot.uuid, ElementQuantity.Exact(6000), 5)
        return Fixture(project, id, element, row.updatedAt)
    }

    suspend fun run(driver: SqlDriver): Unit = coroutineScope {
        val db = PrototypeDatabase(driver)
        val f = fixture(db)
        val elements = MeasurementElementRepository(db); val points = PointPlacementRepository(db)
        val measurements = MeasurementRepository(db)
        val originalGeometry = measurements.find(f.id)!!
        val counts = Channel<Int>(Channel.UNLIMITED)
        val summaries = Channel<Long>(Channel.UNLIMITED)
        val observer = launch { points.observeForMeasurement(f.element.measurementUuid).collect { counts.send(it.size) } }
        val summaryObserver = launch { elements.observeForMeasurement(f.element.measurementUuid).collect { summaries.send(it.single().placedQuantityMilli) } }
        suspend fun count(n: Int) = withTimeout(10_000) { while (counts.receive() != n) { } }
        suspend fun summary(n: Long) = withTimeout(10_000) { while (summaries.receive() != n) { } }
        fun current() = elements.find(f.element.uuid)!!
        fun checkCount(n: Int) {
            assertEquals(n * 1000L, current().placedQuantityMilli)
            assertEquals(if (n == 0) ElementPlacement.UNPLACED else ElementPlacement.APPROXIMATE, current().placement)
            assertEquals(n, points.forMeasurement(f.element.measurementUuid).size)
            assertEquals(originalGeometry, measurements.find(f.id), "Placement never changes geometry, notes, totals or version")
        }
        try {
            count(0); summary(0); checkCount(0)
            val first = points.addPoint(current().version, f.contourVersion, 1000, 1000, 10, "first-point-${f.id}")
            count(1); summary(1000); checkCount(1)
            assertEquals(first, points.addPoint(f.element.version, f.contourVersion, 1000, 1000, 99, first.uuid))
            assertFailsWith<PointPlacementFailure.RetryConflict> {
                points.addPoint(current().version, f.contourVersion, 1001, 1000, 11, first.uuid)
            }
            val second = points.addPoint(current().version, f.contourVersion, 2000, 1000, 11, "second-point-${f.id}")
            count(2); summary(2000); checkCount(2)
            assertEquals(first, points.find(first.uuid)); assertEquals(second, points.find(second.uuid))
            val stable = current()
            assertFailsWith<MeasurementElementFailure.PlacementsInUse> {
                elements.edit(stable.version, "no", stable.approval, ElementQuantity.Exact(1000), 12)
            }
            assertFailsWith<MeasurementElementFailure.PlacementsInUse> {
                elements.edit(stable.version, "no", stable.approval, ElementQuantity.Unknown, 12)
            }
            assertFailsWith<MeasurementElementFailure.PlacementsInUse> {
                elements.edit(stable.version, "no", stable.approval, ElementQuantity.Range(1000, 6000), 12)
            }
            assertEquals(stable, current()); checkCount(2)
            assertFailsWith<PointPlacementFailure.StaleVersion> { points.removePoint(f.element.version, first, 12) }
            val removed = points.removePoint(current().version, second, 12)
            count(1); summary(1000); checkCount(1)
            assertEquals(removed, points.removePoint(current().version, second, 99))
            points.removePoint(current().version, first, 13)
            count(0); summary(0); checkCount(0)
            assertEquals(2, points.tombstones().count { it.elementUuid == f.element.uuid })
            assertFailsWith<PointPlacementFailure.RetryConflict> {
                points.addPoint(current().version, f.contourVersion, 1000, 1000, 14, first.uuid)
            }
            repeat(6) { i -> points.addPoint(current().version, f.contourVersion, 1000L + i * 100, 1500, 20L + i) }
            checkCount(6)
            assertFailsWith<PointPlacementFailure.CapacityReached> { points.addPoint(current().version, f.contourVersion, 1200, 1800, 30) }
            assertFailsWith<PointPlacementFailure.StaleContour> { points.addPoint(current().version, f.contourVersion - 1, 1200, 1800, 30) }
            for ((x,y) in listOf(0L to 1000L, -1L to 1000L, 999999L to 999999L, Long.MAX_VALUE to 1L))
                assertFailsWith<PointPlacementFailure.OutsideContour> { points.addPoint(current().version, f.contourVersion, x, y, 30) }
            // A price rename/delete never mutates an existing selection or prevents deleting its points.
            val item = (current().source as ElementSource.CatalogSelection).priceItemUuid
            val price = PriceRepository(db).all().single { it.uuid == item }
            PriceRepository(db).delete(price.id)
            assertEquals(f.element.label, current().label)
            elements.delete(current().version, 40)
            assertTrue(points.forMeasurement(f.element.measurementUuid).isEmpty())
            assertEquals(0L, current().placedQuantityMilli)
            assertEquals(8, points.tombstones().count { it.elementUuid == f.element.uuid })
            assertFailsWith<PointPlacementFailure.ParentUnavailable> { points.addPoint(current().version, f.contourVersion, 1500, 1500, 50) }
        } finally { observer.cancel(); summaryObserver.cancel(); counts.close(); summaries.close() }
    }

    fun geometryAndDeletionGuards(driver: SqlDriver) {
        val db = PrototypeDatabase(driver); val f = fixture(db, "Geometry guard")
        val elements = MeasurementElementRepository(db); val points = PointPlacementRepository(db)
        val measurements = MeasurementRepository(db)
        val marker = points.addPoint(f.element.version, f.contourVersion, 1000, 1000, 10)
        val edit = MeasurementEditStore(db) { 20 }; val document = edit.load(f.project, f.id)
        val changed = document.draft.reduce(MeasurementCreateIntent.Dimension("length", "8"))
        assertFailsWith<MeasurementContourHasPoints> { edit.update(document, changed) }
        val row = measurements.find(f.id)!!
        assertFails { driver.execute(null, "UPDATE room_measurements SET drawingJson='{}' WHERE id=${f.id}", 0) }
        assertFails { driver.execute(null, "UPDATE room_measurements SET areaSquareMeters=999 WHERE id=${f.id}", 0) }
        assertFailsWith<MeasurementContourHasPoints> {
            measurements.update(f.id, row.name, row.shape, row.lengthMeters, row.widthMeters, row.areaSquareMeters, row.perimeterMeters, "{}", row.notes)
        }
        assertFails { driver.execute(null, "UPDATE measurement_elements SET quantityKind='unknown', quantityMilli=NULL WHERE uuid='${f.element.uuid}'", 0) }
        assertFails { driver.execute(null, "UPDATE measurement_elements SET placedQuantityMilli=0, placementStatus='unplaced' WHERE uuid='${f.element.uuid}'", 0) }
        assertFails { driver.execute(null, "UPDATE point_placements SET xMm=1100 WHERE uuid='${marker.uuid}'", 0) }
        val renamed = edit.update(document, document.draft.copy(name = "Нова назва", notes = "Примітка"))
        assertEquals(row.drawingJson, measurements.find(f.id)!!.drawingJson)
        assertEquals(marker, points.find(marker.uuid))
        points.removePoint(elements.find(f.element.uuid)!!.version, marker, 30)
        edit.update(renamed, changed.copy(name = renamed.draft.name, notes = renamed.draft.notes))
        assertNotEquals(row.drawingJson, measurements.find(f.id)!!.drawingJson)
        assertFailsWith<PointPlacementFailure.StaleContour> {
            points.addPoint(elements.find(f.element.uuid)!!.version, f.contourVersion, 1000, 1000, 40)
        }
        for (kind in listOf("measurement", "project", "raw measurement", "raw project")) {
            val child = fixture(db, kind)
            val p = points.addPoint(child.element.version, child.contourVersion, 1000, 1000, 10)
            when (kind) {
                "measurement" -> measurements.delete(child.id)
                "project" -> ProjectRepository(db).delete(child.project)
                "raw measurement" -> driver.execute(null, "UPDATE room_measurements SET updatedAt=99,deletedAt=99 WHERE id=${child.id}", 0)
                else -> driver.execute(null, "UPDATE projects SET updatedAt=99,deletedAt=99 WHERE id=${child.project}", 0)
            }
            assertNotNull(points.find(p.uuid)!!.deletedAt)
            val element = elements.find(child.element.uuid)!!
            assertNotNull(element.deletedAt); assertEquals(0L, element.placedQuantityMilli)
            assertFailsWith<PointPlacementFailure.ParentUnavailable> { points.addPoint(element.version, child.contourVersion, 1500, 1500, 100) }
        }
        assertTrue(MeasurementElementMigrationProof.rows(driver, "PRAGMA foreign_key_check", 4).isEmpty())
    }

    fun eligibilityAndIsolation(driver: SqlDriver) {
        val db = PrototypeDatabase(driver); val a = fixture(db); val b = fixture(db)
        val points = PointPlacementRepository(db); val elements = MeasurementElementRepository(db)
        val measurements = MeasurementRepository(db)
        val siblingId = MeasurementCreateStore(db) { 2 }.create(draft(a.project))
        val siblingUuid = measurements.find(siblingId)!!.uuid
        val marker = points.addPoint(a.element.version, a.contourVersion, 1001, 1003, 10)
        assertTrue(points.forMeasurement(b.element.measurementUuid).isEmpty())
        assertTrue(points.forMeasurement(siblingUuid).isEmpty())
        assertFailsWith<PointPlacementFailure.ParentUnavailable> {
            points.addPoint(a.element.version.copy(measurementUuid = b.element.measurementUuid), a.contourVersion, 1100, 1100, 11)
        }
        assertFailsWith<PointPlacementFailure.ParentUnavailable> {
            points.addPoint(a.element.version.copy(uuid = "old-missing-uuid"), a.contourVersion, 1100, 1100, 11)
        }
        val wish = elements.createWish(b.element.measurementUuid, "Wish", 10, unit = "шт.", quantity = ElementQuantity.Exact(6000))
        assertFailsWith<PointPlacementFailure.UnsupportedElement> { points.addPoint(wish.version, b.contourVersion, 1000, 1000, 11) }
        for (q in listOf(ElementQuantity.Unknown, ElementQuantity.Range(1000, 6000))) {
            val live = elements.find(b.element.uuid)!!
            val changed = elements.edit(live.version, "", live.approval, q, 20)
            assertFailsWith<PointPlacementFailure.UnsupportedElement> { points.addPoint(changed.version, b.contourVersion, 1000, 1000, 21) }
        }
        val group = PriceGroupRepository(db).all().first()
        val prices = PriceRepository(db)
        for ((placement, unit, mode, quantity) in listOf(
            listOf("none", "шт.", "manual", ElementQuantity.Exact(6000)),
            listOf("line", "шт.", "manual", ElementQuantity.Exact(6000)),
            listOf("area", "шт.", "manual", ElementQuantity.Exact(6000)),
            listOf("point", "м.п.", "manual", ElementQuantity.Exact(2500)),
            listOf("point", "м²", "ceiling_area", ElementQuantity.CeilingArea),
            listOf("point", "м.п.", "ceiling_perimeter", ElementQuantity.CeilingPerimeter)
        )) {
            val priceId = prices.createInGroup("unsupported.${newRecordUuid()}", "Unsupported ${newRecordUuid()}", group.uuid, 1.0, 30,
                unit as String, com.andreyceilings.shared.prices.QuantityMode.entries.single { it.value == mode },
                com.andreyceilings.shared.prices.PlacementKind.entries.single { it.value == placement })
            val item = prices.find(priceId)!!
            val selected = elements.createCatalogSelection(b.element.measurementUuid, item.uuid, quantity as ElementQuantity, 31)
            assertFailsWith<PointPlacementFailure.UnsupportedElement> { points.addPoint(selected.version, b.contourVersion, 1000, 1000, 32) }
        }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> {
            elements.createCatalogSelection(b.element.measurementUuid, (b.element.source as ElementSource.CatalogSelection).priceItemUuid, ElementQuantity.Exact(2500), 40)
        }
        val historicalId = measurements.createReturningId(b.project, "Historical", lengthMeters = 5.0, widthMeters = 4.0, createdAt = 40)
        val historical = elements.createCatalogSelection(measurements.find(historicalId)!!.uuid,
            (b.element.source as ElementSource.CatalogSelection).priceItemUuid, ElementQuantity.Exact(6000), 41)
        assertFailsWith<PointPlacementFailure.InvalidContour> { points.addPoint(historical.version, 40, 1000, 1000, 42) }
        assertEquals(marker, points.find(marker.uuid)); assertEquals(1, points.forMeasurement(a.element.measurementUuid).size)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        assertFails { driver.execute(null, "INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt,deletedAt) VALUES('bad','missing',1,1,1,1,1,NULL)", 0) }
        for (value in listOf("1.5", "1000001", "9223372036854775808", "'not a coordinate'"))
            assertFails { driver.execute(null, "INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt,deletedAt) VALUES('bad','${a.element.uuid}',$value,1,2,1,1,NULL)", 0) }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        assertFails { driver.execute(null, "INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt,deletedAt) VALUES('bad','missing',1,1,1,1,1,NULL)", 0) }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    }
}
