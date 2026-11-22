package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.livepricing.LivePricingReader
import com.andreyceilings.shared.livepricing.PricingReadResult
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WorkspacePlacementDraftTest {
    @Test
    fun oneSavePersistsAllKindsAndRecoversAfterLostCommitResponse() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw)
            val db = PrototypeDatabase(raw)
            val fixture = PointPlacementProof.fixture(db)
            val loseResponse = java.util.concurrent.atomic.AtomicBoolean()
            val driver = object : SqlDriver by raw {
                override fun notifyListeners(vararg queryKeys: String) {
                    raw.notifyListeners(*queryKeys)
                    if ("ceiling_operations" in queryKeys && loseResponse.compareAndSet(true, false))
                        error("lost acknowledgement after commit")
                }
            }
            val store = MeasurementEditStore(PrototypeDatabase(driver)) { 100 }
            val original = store.load(fixture.project, fixture.id)
            val measurement = original.draft.recordUuid
            val editor = MeasurementEditModel(store, original)

            val pointItem = store.placementCatalog().single { item -> item.code.startsWith("fixture.") }
            editor.addCatalogWork(pointItem, ElementQuantity.Exact(3000), place = true)
            assertTrue(editor.placeWorkspacePoint(700, 800))
            val point = editor.displayPoints().single()
            editor.openWorkspaceCoordinates(WorkspaceCoordinatesKind.POINT)
            editor.changeWorkspaceCoordinate(0, "0,75")
            editor.applyWorkspaceCoordinates()
            assertNull(editor.state.value.error)
            assertNull(editor.state.value.coordinates)
            assertEquals(750L, editor.displayPoints().single().xMm)
            editor.openMounting()
            val walls = editor.draftWalls()
            val anchor = WallAnchor(WallAnchor.Kind.ALONG_INWARD, walls.first().uuid, null, 800, 900)
            editor.stagePoint(point.uuid, PointMountingEdit(anchor = anchor))

            val lineItem = addItem(db, "Workspace line", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(lineItem, ElementQuantity.Exact(3000), place = true)
            assertTrue(editor.finishWorkspaceLine(PointPositionMm(300, 500), PointPositionMm(1600, 500)))
            val line = editor.displayLines().single()

            val areaItem = addItem(db, "Workspace area", PlacementKind.AREA, "шт.")
            editor.addCatalogWork(areaItem, ElementQuantity.Exact(2000), place = true)
            editor.areaTap(1200, 1100)
            editor.areaTap(1800, 1500)
            editor.acceptAreaCreation()
            val catalogArea = editor.displayAreas().single()
            editor.beginArea(AreaRole.VOID_MARKER, tool = AreaTool.CIRCLE)
            editor.areaTap(2900, 1700)
            editor.setAreaDiameter("0,2")
            editor.acceptAreaCreation()
            val circle = editor.displayAreas().single { it.role == AreaRole.VOID_MARKER }

            editor.undo()
            assertEquals(listOf(catalogArea.uuid), editor.displayAreas().map { it.uuid })
            editor.redo()
            assertEquals(setOf(catalogArea.uuid, circle.uuid), editor.displayAreas().map { it.uuid }.toSet())
            val snapshot = editor.snapshot()
            val restored = assertNotNull(MeasurementEditModel.restore(store, snapshot, fixture.project, fixture.id))
            restored.reconcile(keepEditing = true)
            assertEquals(setOf(point.uuid, line.uuid, catalogArea.uuid, circle.uuid), (
                restored.displayPoints().map { it.uuid } + restored.displayLines().map { it.uuid } +
                    restored.displayAreas().map { it.uuid }).toSet())
            assertEquals(original, store.load(fixture.project, fixture.id))
            assertEquals(1, MeasurementElementRepository(db).forMeasurement(measurement).size)

            loseResponse.set(true)
            restored.saveInWorkspace()
            assertTrue(restored.state.value.uncertain, restored.state.value.error)
            restored.saveInWorkspace()
            assertFalse(restored.state.value.uncertain, restored.state.value.error)
            assertFalse(restored.state.value.dirty)
            val reopened = store.load(fixture.project, fixture.id)
            assertEquals(point.uuid, reopened.placements.points.single().uuid)
            assertEquals(anchor, reopened.placements.points.single().mounting.anchor)
            assertEquals(PointPositionMm(800, 900), reopened.placements.points.single().let { PointPositionMm(it.xMm, it.yMm) })
            assertEquals(line.uuid, reopened.placements.lines.single().uuid)
            val areas = reopened.placements.areas.associateBy { it.uuid }
            assertEquals(AreaShape.Circle(PointPositionMm(2900, 1700), 100), areas.getValue(circle.uuid).shape)
            assertEquals(catalogArea.elementUuid, areas.getValue(catalogArea.uuid).elementUuid)
            val pointWork = assertNotNull(MeasurementElementRepository(db).find(point.elementUuid))
            assertEquals(ElementQuantity.Exact(3000), pointWork.quantity)
            assertEquals(1000L, pointWork.placedQuantityMilli)
            val pricing = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.project, measurement))
            assertEquals(ElementQuantity.Exact(3000), pricing.source.elements.single { it.uuid == point.elementUuid }.quantity)
            assertEquals(4, MeasurementElementRepository(db).forMeasurement(measurement).size)
        }
    }

    @Test
    fun staleContourKeepsAllDraftsAndPersistsNone() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw)
            val db = PrototypeDatabase(raw)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 100 }
            val original = store.load(fixture.project, fixture.id)
            val editor = MeasurementEditModel(store, original)
            val item = addItem(db, "Unplaced draft", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(item, ElementQuantity.Exact(1800), place = false)
            editor.startWorkspacePlacement(fixture.element)
            assertTrue(editor.placeWorkspacePoint(800, 800))
            editor.beginArea(AreaRole.OBSTACLE)
            editor.areaTap(1100, 1200)
            editor.areaTap(1700, 1800)
            editor.acceptAreaCreation()
            val pending = editor.state.value.additions

            store.update(original, original.draft.copy(name = "Other editor"))
            editor.saveInWorkspace()
            assertTrue(editor.state.value.conflict, editor.state.value.error)
            assertEquals(pending, editor.state.value.additions)
            val reopened = store.load(fixture.project, fixture.id)
            assertTrue(reopened.placements.points.isEmpty())
            assertTrue(reopened.placements.areas.isEmpty())
            assertEquals(1, MeasurementElementRepository(db).forMeasurement(original.draft.recordUuid).size)
        }
    }

    @Test
    fun failureWhileInstallingPointRollsBackContourCatalogAndLine() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw)
            val db = PrototypeDatabase(raw)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 100 }
            val original = store.load(fixture.project, fixture.id)
            val item = addItem(db, "No partial work", PlacementKind.LINE, "м.п.")
            val element = item.toWorkspaceElement("draft-line-work", original.draft.recordUuid, ElementQuantity.Exact(2000))
            val point = PointPlacement("outside-point", fixture.element.uuid, -500, -500, original.updatedAt, 0, 0, null)
            val line = LinePlacement("draft-line", element.uuid, PointPositionMm(500, 500),
                PointPositionMm(1200, 500), original.updatedAt, 0, 0)

            assertFailsWith<MeasurementEditRejected> {
                store.update(original, original.draft.copy(name = "No partial change"),
                    lineEdits = mapOf(line.uuid to LinePlacementEdit(line)),
                    additions = WorkspacePlacementDraft(mapOf(element.uuid to element), mapOf(point.uuid to point)))
            }
            assertEquals(original, store.load(fixture.project, fixture.id))
            assertNull(MeasurementElementRepository(db).find(element.uuid))
            assertEquals(0L, PriceNameUniquenessProof.scalar(raw, "SELECT count(*) FROM ceiling_operations"))
        }
    }

    @Test
    fun materialSelectorsSaveRealTypedBindingsAndDeleteMarkerWithoutDeletingWork() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw)
            val db = PrototypeDatabase(raw)
            val fixture = PointPlacementProof.fixture(db)
            val pointRepository = PointPlacementRepository(db)
            val marker = pointRepository.addPoint(fixture.element.version, fixture.contourVersion, 900, 800, 10)
            val store = MeasurementEditStore(db) { 100 }
            val measurement = fixture.element.measurementUuid
            val prices = PriceRepository(db)
            val group = PriceGroupRepository(db).all().first().uuid
            fun item(code: String, unit: String, mode: QuantityMode): String =
                checkNotNull(prices.find(prices.createInGroup(code, code, group, 10.0, 25, unit, mode, PlacementKind.NONE))).uuid
            val oldCanvas = item("material.old", "м²", QuantityMode.CEILING_AREA)
            val newCanvas = item("material.new", "м²", QuantityMode.CEILING_AREA)
            val profile = item("material.profile", "м.п.", QuantityMode.CEILING_PERIMETER)
            val elements = MeasurementElementRepository(db)
            val old = elements.createCatalogSelection(measurement, oldCanvas, ElementQuantity.CeilingArea, 30,
                businessRole = BusinessRole.CANVAS)
            val original = store.load(fixture.project, fixture.id)
            val model = MeasurementEditModel(store, original)
            val catalog = store.placementCatalog().associateBy { it.uuid }
            model.chooseMaterial(BusinessRole.PRIMARY_PROFILE, catalog.getValue(newCanvas))
            assertTrue(model.materials(BusinessRole.PRIMARY_PROFILE).isEmpty())
            model.chooseMaterial(BusinessRole.CANVAS, catalog.getValue(newCanvas))
            model.chooseMaterial(BusinessRole.PRIMARY_PROFILE, catalog.getValue(profile))
            model.deleteWorkspacePoint(marker.uuid)
            assertTrue(model.displayPoints().isEmpty())
            assertEquals(marker, pointRepository.find(marker.uuid))
            assertNull(elements.find(old.uuid)?.deletedAt)
            model.undo()
            assertEquals(marker, model.displayPoints().single())
            model.redo()
            model.saveInWorkspace()
            assertFalse(model.state.value.dirty, model.state.value.error)
            val saved = store.load(fixture.project, fixture.id)
            val canvas = saved.materialElements.single { it.businessRole == BusinessRole.CANVAS }
            val savedProfile = saved.materialElements.single { it.businessRole == BusinessRole.PRIMARY_PROFILE }
            assertEquals(newCanvas, (canvas.source as ElementSource.CatalogSelection).priceItemUuid)
            assertEquals(profile, (savedProfile.source as ElementSource.CatalogSelection).priceItemUuid)
            assertEquals(ElementQuantity.CeilingArea, canvas.quantity)
            assertEquals(ElementQuantity.CeilingPerimeter, savedProfile.quantity)
            assertNotNull(elements.find(old.uuid)?.deletedAt)
            assertNotNull(pointRepository.find(marker.uuid)?.deletedAt)
            assertEquals(fixture.element.quantity, elements.find(fixture.element.uuid)?.quantity)
        }
    }

    private suspend fun addItem(db: PrototypeDatabase, title: String, kind: PlacementKind, unit: String): ElementCatalogItem {
        val repository = PriceRepository(db)
        val group = PriceGroupRepository(db).all().first()
        val id = repository.createInGroup("p5b.${kind.value}", title, group.uuid, 10.0, 12, unit, QuantityMode.MANUAL, kind)
        val uuid = repository.find(id)!!.uuid
        return MeasurementEditStore(db) { 100 }.placementCatalog().single { it.uuid == uuid }
    }
}
