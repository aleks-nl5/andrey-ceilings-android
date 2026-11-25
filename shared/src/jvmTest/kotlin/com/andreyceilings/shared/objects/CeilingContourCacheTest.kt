package com.andreyceilings.shared.objects

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceCatalogSchema
import com.andreyceilings.shared.db.PriceGroupRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.measurement.AndroidMeasurementFixtures
import com.andreyceilings.shared.measurement.ElementQuantity
import com.andreyceilings.shared.measurement.MeasurementCreateFixtures
import com.andreyceilings.shared.measurement.MeasurementCreateIntent
import com.andreyceilings.shared.measurement.MeasurementCreateStore
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import com.andreyceilings.shared.measurement.MeasurementEditModel
import com.andreyceilings.shared.measurement.MeasurementGeometryCodec
import com.andreyceilings.shared.measurement.measurementDrawingProjection
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CeilingContourCacheTest {
    @Test
    fun thumbnailProjectionMatchesSavedVerticesAndFitsWithoutDistortion() {
        for (source in AndroidMeasurementFixtures.all) {
            val geometry = MeasurementGeometryCodec.read(source)
            val contour = assertNotNull(CeilingContourSource(geometry.shapeType, source, 0.0, 0.0).project())
            assertTrue(contour.sideLabels.isEmpty())
            assertEquals(geometry.vertices.map { it.xMm to it.yMm },
                contour.points.map { it.x.toLong() to it.y.toLong() })
            val fit = assertNotNull(measurementDrawingProjection(contour, 64f, 52f))
            val points = contour.points.map(fit::project)
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val vertices = geometry.vertices
            val actualRatio = (vertices.maxOf { it.xMm } - vertices.minOf { it.xMm }).toDouble() /
                (vertices.maxOf { it.yMm } - vertices.minOf { it.yMm })
            assertEquals(actualRatio, (maxX - minX) / (maxY - minY), 1e-8)
            assertEquals(32.0, (maxX + minX) / 2, 1e-8)
            assertEquals(26.0, (maxY + minY) / 2, 1e-8)
        }
        assertNull(CeilingContourSource("rectangle", "bad", 5.0, 4.0).project())
        assertNull(CeilingContourSource("l_shaped", "{}", 5.0, 4.0).project())
        assertNotNull(CeilingContourSource("rectangle", "{}", 5.0, 4.0).project())
    }

    @Test
    fun cacheReusesSameSavedGeometryAndOnlyInvalidatesEditedCeilingAndClearsAtClose(): Unit = runBlocking {
        var preparations = 0
        val cache = CeilingContourCache(capacity = 2) {
            preparations++
            it.project()
        }
        val rectangle = CeilingContourSource("rectangle", AndroidMeasurementFixtures.rectangleV2, 5.0, 4.0)
        val changed = CeilingContourSource("l_shaped", AndroidMeasurementFixtures.lShapeV2, 5.0, 4.0)
        val a = assertNotNull(cache.projection("a", rectangle))
        val b = assertNotNull(cache.projection("b", rectangle))
        // A placement-only save can change the room's general version. Its saved
        // source is identical and the general version never enters this cache.
        assertSame(a, cache.projection("a", rectangle.copy()))
        assertEquals(2, preparations)
        assertEquals(6, assertNotNull(cache.projection("a", changed)).points.size)
        assertSame(b, cache.projection("b", rectangle))
        assertEquals(3, preparations)
        cache.projection("c", rectangle)
        assertSame(b, cache.projection("b", rectangle))
        assertEquals(4, preparations)
        cache.projection("a", changed)
        assertEquals(5, preparations)
        cache.close()
        assertNull(cache.projection("a", changed))
        assertEquals(5, preparations)
        assertNotNull(CeilingContourCache().projection("a", changed))
    }

    @Test
    fun visibleContourTracksContourSaveAndReusesProjectionAfterPlacementSave(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val store = ObjectsClientsStore(db) { 101 }
            val project = store.saveProject(null, ProjectDraft("A"))
            val draft = MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_MILLIMETER)
            val ceiling = MeasurementCreateStore(db) { 20 }.create(draft)
            val measurements = MeasurementRepository(db)
            val saved = assertNotNull(measurements.find(ceiling))
            val stream = store.observeCeilingContour(project, saved.uuid)
            val updates = Channel<MeasurementDrawingContour?>(Channel.UNLIMITED)
            val observer = launch { stream.collect { updates.send(it) } }
            val before = assertNotNull(withTimeout(5_000) { updates.receive() })

            val prices = PriceRepository(db)
            val group = PriceGroupRepository(db).all().first()
            val itemId = prices.createInGroup("p6a.point", "Point", group.uuid, 10.0, 30,
                "шт.", QuantityMode.MANUAL, PlacementKind.POINT)
            val itemUuid = assertNotNull(prices.find(itemId)).uuid
            val item = store.measurementEdit.placementCatalog().single { it.uuid == itemUuid }
            val placement = MeasurementEditModel(store.measurementEdit, store.measurementEdit.load(project, ceiling))
            placement.addCatalogWork(item, ElementQuantity.Exact(1000), place = true)
            assertTrue(placement.placeWorkspacePoint(900, 850))
            assertTrue(placement.saveInWorkspace(), placement.state.value.error)
            val withPlacement = assertNotNull(measurements.find(ceiling))
            assertTrue(withPlacement.updatedAt > saved.updatedAt)
            assertEquals(saved.drawingJson, withPlacement.drawingJson)
            assertSame(before, stream.first())

            val edit = MeasurementEditModel(store.measurementEdit, store.measurementEdit.load(project, ceiling))
            edit.accept(MeasurementCreateIntent.Dimension("length", "6"))
            assertTrue(edit.saveInWorkspace(), edit.state.value.error)
            val changed = assertNotNull(withTimeout(5_000) { updates.receive() })
            assertEquals(6000.0, changed.points.maxOf { it.x })
            store.closeAndAwaitProjectReads()
            assertNull(withTimeout(5_000) { updates.receive() })
            observer.cancelAndJoin()
        }
    }

}
