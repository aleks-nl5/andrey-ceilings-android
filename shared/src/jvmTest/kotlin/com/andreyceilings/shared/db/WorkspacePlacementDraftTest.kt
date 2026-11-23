package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import java.util.concurrent.atomic.AtomicBoolean
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.livepricing.LivePricingReader
import com.andreyceilings.shared.livepricing.PricingReadResult
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WorkspacePlacementDraftTest {
    @Test
    fun workListDetailsUsesExistingPlacementAndWorkUuid() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = store.placementCatalog().single { it.code.startsWith("fixture.") }
            editor.addCatalogWork(item, ElementQuantity.Exact(2000), place = true)
            val workId = assertNotNull(editor.state.value.pointElementUuid)
            assertTrue(editor.placeWorkspacePoint(900, 950))
            val pointId = editor.displayPoints().single().uuid
            assertTrue(editor.selectFirstWorkspacePlacement(workId))
            assertEquals(pointId, editor.state.value.selectedPointUuid)
            assertEquals(pointId, editor.state.value.mountingInput?.pointUuid,
                "Settings must open the point wall-distance editor, not just select a marker")
            assertEquals(1, editor.workspaceWorks().count { it.uuid == workId })
            assertFalse(editor.selectFirstWorkspacePlacement("missing"))
        }
    }

    @Test
    fun workSettingsOpensLineAndAreaParametersWithoutChangingPayableQuantities() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))

            val lineItem = addItem(db, "Line settings", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(lineItem, ElementQuantity.Exact(3000), place = true)
            assertTrue(editor.finishWorkspaceLine(PointPositionMm(500, 600), PointPositionMm(1500, 600)))
            val line = editor.displayLines().single()
            editor.clearDrawingSelection()
            assertTrue(editor.selectFirstWorkspacePlacement(line.elementUuid))
            assertEquals(line.uuid, editor.state.value.selectedLineUuid)
            assertEquals(line.uuid, editor.state.value.lineProperties?.uuid)
            editor.discardLineProperties()

            val areaItem = addItem(db, "Area settings", PlacementKind.AREA, "шт.")
            editor.addCatalogWork(areaItem, ElementQuantity.Exact(2000), place = true)
            editor.areaTap(1100, 900)
            editor.areaTap(1700, 1300)
            editor.acceptAreaCreation()
            val area = editor.displayAreas().single()
            val areaWorkId = assertNotNull(area.elementUuid)
            editor.clearDrawingSelection()
            assertTrue(editor.selectFirstWorkspacePlacement(areaWorkId))
            assertEquals(area.uuid, editor.state.value.area.selected)
            assertEquals(area.uuid, editor.state.value.area.properties?.uuid)
            assertEquals(2, editor.workspaceWorks().size)
            assertEquals(ElementQuantity.Exact(3000),
                editor.workspaceWorks().single { it.uuid == line.elementUuid }.quantity)
            assertEquals(ElementQuantity.Exact(2000),
                editor.workspaceWorks().single { it.uuid == areaWorkId }.quantity)
        }
    }

    @Test
    fun drawingLengthTransfersToEditableWorkAndManualQuantityMayBeLarger() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = addItem(db, "Transferred length", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(item, ElementQuantity.Exact(3000), place = true)
            assertTrue(editor.finishWorkspaceLine(PointPositionMm(500, 600), PointPositionMm(1500, 600)))
            val workId = editor.displayLines().single().elementUuid
            assertEquals(ElementQuantity.Exact(3000), editor.workspaceWorks().single { it.uuid == workId }.quantity)
            editor.requestDeclaredLength(workId)
            editor.takeDeclaredLength()
            assertEquals(ElementQuantity.Exact(1000),
                editor.workspaceWorks().single { it.uuid == workId }.quantity,
                "Pending drawing length must be visible before global Save")
            assertTrue(workId !in editor.state.value.declaredLengths,
                "Transferring a quantity must not lock it to geometry equality")
            assertTrue(editor.state.value.additions.elements[workId]?.quantity == ElementQuantity.Exact(1000))
            assertTrue(editor.editWorkspaceQuantity(workId, ElementQuantity.Exact(2000)))
            assertEquals(ElementQuantity.Exact(2000), editor.workspaceWorks().single { it.uuid == workId }.quantity)
            assertTrue(editor.canSaveMountingDraft(), "A greater declared quantity must not block Save")
            assertTrue(workId !in editor.state.value.declaredLengths)
            val initial = editor.displayLines().single()
            editor.selectLine(initial.uuid)
            editor.openLineProperties()
            assertEquals("2", editor.state.value.lineProperties?.length,
                "Visible length must start from the declared billable quantity")
            editor.changeLineProperties(length = "1.5")
            val proposed = assertNotNull(editor.state.value.lineProperties)
            assertEquals(initial, editor.linePropertiesCandidate(initial, proposed),
                "Editing declared length must never resize the drawing")
            editor.applyLineProperties()
            assertEquals(initial, editor.displayLines().single())
            assertEquals(ElementQuantity.Exact(1500),
                editor.workspaceWorks().single { it.uuid == workId }.quantity)
            editor.beginLineHandleDrag(initial.uuid, 2)
            editor.previewLineDrag(200.0, 0.0)
            editor.finishLineDrag()
            assertEquals(1200L, editor.displayLines().single().lengthMm)
            assertEquals(ElementQuantity.Exact(1500),
                editor.workspaceWorks().single { it.uuid == workId }.quantity,
                "Dragging a line endpoint must never overwrite the declared amount")
            assertTrue(editor.canSaveMountingDraft())
            editor.saveInWorkspace()
            assertFalse(editor.state.value.dirty, editor.state.value.error)
            val saved = assertNotNull(MeasurementElementRepository(db).find(workId))
            assertEquals(ElementQuantity.Exact(1500), saved.quantity)
            assertEquals(1200L, saved.placedQuantityMilli,
                "Claimed 1.5m and drawn 1.2m must persist independently in one Save")
        }
    }

    @Test
    fun pendingLineInputCannotTransferStaleDrawingLength() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = addItem(db, "Do not transfer stale value", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(item, ElementQuantity.Exact(3000), place = true)
            assertTrue(editor.finishWorkspaceLine(PointPositionMm(500, 600), PointPositionMm(1500, 600)))
            val line = editor.displayLines().single()
            val workId = line.elementUuid
            editor.selectLine(line.uuid)
            editor.openLineProperties()
            editor.changeLineProperties(length = "1.5")
            assertTrue(editor.state.value.lineProperties?.dirty == true)
            editor.requestDeclaredLength(workId)
            editor.takeDeclaredLength()
            assertEquals(ElementQuantity.Exact(3000),
                editor.workspaceWorks().single { it.uuid == workId }.quantity,
                "A pending typed length must not silently transfer the old drawing sum")
            assertEquals(1000L, editor.displayLines().single().lengthMm)
            editor.applyLineProperties()
            assertEquals(1000L, editor.displayLines().single().lengthMm)
            assertEquals(ElementQuantity.Exact(1500),
                editor.workspaceWorks().single { it.uuid == workId }.quantity,
                "Typing in the length field changes only the declared amount")
            editor.openLineProperties()
            assertEquals("1,5", editor.state.value.lineProperties?.length)
            editor.takeDeclaredLength()
            assertEquals(ElementQuantity.Exact(1000),
                editor.workspaceWorks().single { it.uuid == workId }.quantity,
                "Transfer changes billed quantity, not the drawn segment")
            assertEquals(1000L, editor.displayLines().single().lengthMm)
            assertEquals("1", editor.state.value.lineProperties?.length,
                "The existing visible field must refresh on explicit drawing transfer")
            assertFalse(editor.state.value.lineProperties?.dirty ?: true)
            editor.discardLineProperties()
            assertTrue(editor.canSaveMountingDraft())
        }
    }

    @Test
    fun editingLineWidthDoesNotEraseExistingRangeQuantity() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = addItem(db, "Range line", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(item, ElementQuantity.Range(1500, 3000), place = true)
            assertTrue(editor.finishWorkspaceLine(PointPositionMm(500, 600), PointPositionMm(1500, 600)))
            val segment = editor.displayLines().single()
            val workId = segment.elementUuid
            editor.openLineProperties()
            assertEquals("", editor.state.value.lineProperties?.length)
            editor.changeLineProperties(width = "15")
            editor.applyLineProperties()
            assertEquals(ElementQuantity.Range(1500, 3000),
                editor.workspaceWorks().single { it.uuid == workId }.quantity)
            assertEquals(15L, editor.displayLines().single().widthMm)
            editor.saveInWorkspace()
            assertFalse(editor.state.value.dirty, editor.state.value.error)
            assertEquals(ElementQuantity.Range(1500, 3000),
                assertNotNull(MeasurementElementRepository(db).find(workId)).quantity)
        }
    }

    @Test
    fun areaTransferAndManualIncreasePersistWithTheDrawing() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = addItem(db, "Measured area", PlacementKind.AREA, "шт.")
            editor.addCatalogWork(item, ElementQuantity.Unknown, place = true)
            editor.areaTap(700, 900)
            editor.areaTap(1200, 1300)
            editor.acceptAreaCreation()
            val workId = assertNotNull(editor.displayAreas().single().elementUuid)
            assertEquals(ElementQuantity.Unknown,
                editor.workspaceWorks().single { it.uuid == workId }.quantity)
            editor.requestAreaQuantity(workId)
            editor.takeAreaQuantity()
            assertEquals(ElementQuantity.Exact(1000),
                editor.workspaceWorks().single { it.uuid == workId }.quantity)
            assertTrue(workId !in editor.state.value.area.quantities)
            assertTrue(editor.editWorkspaceQuantity(workId, ElementQuantity.Exact(2000)))
            assertEquals(ElementQuantity.Exact(2000),
                editor.workspaceWorks().single { it.uuid == workId }.quantity)
            assertTrue(editor.canSaveMountingDraft())
            editor.saveInWorkspace()
            assertFalse(editor.state.value.dirty, editor.state.value.error)
            val saved = assertNotNull(MeasurementElementRepository(db).find(workId))
            assertEquals(ElementQuantity.Exact(2000), saved.quantity)
            assertEquals(1000L, saved.placedQuantityMilli)
        }
    }

    @Test
    fun optionalWorkWithoutQuantityCanBeSavedAndLaterCompleted() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = addItem(db, "Unspecified optional work", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(item, ElementQuantity.Unknown, place = false)
            val workId = editor.workspaceWorks().single { it.label == item.title }.uuid
            assertTrue(editor.canSaveMountingDraft())
            editor.saveInWorkspace()
            assertFalse(editor.state.value.dirty, editor.state.value.error)
            assertEquals(ElementQuantity.Unknown,
                assertNotNull(MeasurementElementRepository(db).find(workId)).quantity)
            assertTrue(editor.editWorkspaceQuantity(workId, ElementQuantity.Exact(2000)))
            editor.saveInWorkspace()
            assertEquals(ElementQuantity.Exact(2000),
                assertNotNull(MeasurementElementRepository(db).find(workId)).quantity)
        }
    }

    @Test
    fun switchingWorkCannotSilentlyDiscardDirtyLineProperties() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = addItem(db, "Two placed lines", PlacementKind.LINE, "м.п.")
            editor.addCatalogWork(item, ElementQuantity.Exact(3000), place = true)
            assertTrue(editor.finishWorkspaceLine(PointPositionMm(500, 600), PointPositionMm(1300, 600)))
            val original = editor.displayLines().single()
            editor.selectLine(original.uuid)
            editor.openLineProperties()
            editor.changeLineProperties(note = "Not yet applied")
            assertTrue(editor.state.value.lineProperties?.dirty == true)
            assertFalse(editor.selectFirstWorkspacePlacement(original.elementUuid))
            assertTrue(editor.state.value.linePropertiesDismiss)
            assertEquals("Not yet applied", editor.state.value.lineProperties?.note)
            assertEquals(original.uuid, editor.state.value.lineProperties?.uuid)
            editor.continueLineProperties()
            assertFalse(editor.state.value.linePropertiesDismiss)
            assertTrue(editor.promptForDirtyElementBeforeNavigation())
            assertTrue(editor.state.value.linePropertiesDismiss)
            editor.discardLineProperties()
            assertNull(editor.state.value.lineProperties)
        }
    }

    @Test
    fun globalSaveRemainsAvailableWhileMorePointsCanStillBePlaced() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 101 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = store.placementCatalog().single { it.code.startsWith("fixture.") }
            editor.addCatalogWork(item, ElementQuantity.Exact(3000), place = true)
            val workId = assertNotNull(editor.state.value.pointElementUuid)
            assertTrue(editor.placeWorkspacePoint(800, 800))
            assertEquals(workId, editor.state.value.pointElementUuid)
            assertTrue(editor.state.value.dirty)
            assertTrue(editor.canSaveMountingDraft(), "An active POINT tool must not disable global Save")
            editor.saveInWorkspace()
            assertFalse(editor.state.value.dirty, editor.state.value.error)
            val loaded = store.load(fixture.project, fixture.id)
            assertEquals(1, loaded.placements.points.size)
            assertEquals(ElementQuantity.Exact(3000),
                MeasurementElementRepository(db).find(workId)?.quantity)
            assertEquals(1000L, MeasurementElementRepository(db).find(workId)?.placedQuantityMilli)
        }
    }

    @Test
    fun unusedAreaToolDoesNotCreateDirtyExitButIncompleteAreaBlocksSave() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val editor = MeasurementEditModel(MeasurementEditStore(db) { 101 },
                MeasurementEditStore(db) { 101 }.load(fixture.project, fixture.id))
            editor.beginArea(AreaRole.OBSTACLE)
            assertFalse(editor.state.value.dirty, "Entering an empty tool is a UI state, not a database change")
            editor.areaTap(1100, 1200)
            assertTrue(editor.state.value.dirty)
            assertFalse(editor.canSaveMountingDraft(), "Incomplete AREA must not silently persist")
            editor.cancelPlacement()
            assertFalse(editor.state.value.dirty)
        }
    }

    @Test
    fun coincidentPointMarkersCanBeSelectedIndividuallyWithoutMovingGeometry() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 100 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = store.placementCatalog().single { it.code.startsWith("fixture.") }
            editor.addCatalogWork(item, ElementQuantity.Exact(3000), place = true)
            val workId = assertNotNull(editor.state.value.pointElementUuid)
            repeat(3) { assertTrue(editor.placeWorkspacePoint(1100, 900)) }
            val ids = editor.displayPoints().map { it.uuid }
            assertEquals(3, ids.toSet().size)
            editor.selectPoint(ids.first())
            assertEquals(ids, editor.overlappingPointsForSelected().map { it.uuid })
            editor.openMounting()
            assertNotNull(editor.state.value.mountingInput)
            editor.selectNextOverlappingPoint()
            assertEquals(ids.first(), editor.state.value.selectedPointUuid)
            editor.discardMounting()
            val additions = editor.state.value.additions
            for (index in 1..3) {
                editor.selectNextOverlappingPoint()
                assertEquals(ids[index % 3], editor.state.value.selectedPointUuid)
                assertEquals(additions, editor.state.value.additions)
                assertEquals(setOf(1100L to 900L), editor.displayPoints().map { it.xMm to it.yMm }.toSet())
            }
            assertEquals(ElementQuantity.Exact(3000), editor.workspaceWorks().single { it.uuid == workId }.quantity)
            editor.undo()
            assertEquals(2, editor.displayPoints().size)
            editor.redo()
            assertEquals(3, editor.displayPoints().size)
            assertTrue(store.load(fixture.project, fixture.id).placements.points.isEmpty())
        }
    }

    @Test
    fun multiplePointPlacementsKeepModeUntilDeclaredCapacity() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 100 }
            val editor = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val item = store.placementCatalog().single { it.code.startsWith("fixture.") }
            editor.addCatalogWork(item, ElementQuantity.Exact(3000), place = true)
            val workId = assertNotNull(editor.state.value.pointElementUuid)
            assertTrue(editor.placeWorkspacePoint(700, 800))
            assertEquals(workId, editor.state.value.pointElementUuid)
            assertEquals(1, editor.displayPoints().size)
            assertTrue(editor.placeWorkspacePoint(1100, 800))
            assertEquals(workId, editor.state.value.pointElementUuid)
            assertEquals(2, editor.displayPoints().size)
            assertTrue(editor.placeWorkspacePoint(1400, 800))
            assertNull(editor.state.value.pointElementUuid)
            assertEquals(3, editor.displayPoints().size)
            assertFalse(editor.placeWorkspacePoint(1600, 800))
            editor.undo()
            assertEquals(2, editor.displayPoints().size)
            editor.redo()
            assertEquals(3, editor.displayPoints().size)
            // No persisted geometry is touched before the one explicit overall Save.
            assertTrue(store.load(fixture.project, fixture.id).placements.points.isEmpty())
        }
    }

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
            // One tap must not end placement for a multi-point work.
            assertEquals(editor.displayPoints().single().elementUuid, editor.state.value.pointElementUuid)
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
            val loseResponse = AtomicBoolean()
            val driver = object : SqlDriver by raw {
                override fun notifyListeners(vararg queryKeys: String) {
                    raw.notifyListeners(*queryKeys)
                    if ("ceiling_operations" in queryKeys && loseResponse.compareAndSet(true, false)) error("lost material receipt")
                }
            }
            var clock = 100L
            val store = MeasurementEditStore(PrototypeDatabase(driver)) { ++clock }
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
            assertEquals(ElementApproval.DISCUSSING, model.materials(BusinessRole.CANVAS).single().approval)
            model.chooseMaterial(BusinessRole.CANVAS, catalog.getValue(oldCanvas))
            assertEquals(old.copy(approval = ElementApproval.APPROVED), model.materials(BusinessRole.CANVAS).single())
            assertEquals(old, elements.find(old.uuid))
            model.undo()
            assertEquals(old, model.materials(BusinessRole.CANVAS).single())
            model.redo()
            val stale = MeasurementEditModel(store, original)
            stale.chooseMaterial(BusinessRole.CANVAS, catalog.getValue(oldCanvas))
            loseResponse.set(true)
            model.saveInWorkspace()
            assertTrue(model.state.value.uncertain, model.state.value.error)
            model.saveInWorkspace()
            assertFalse(model.state.value.uncertain, model.state.value.error)
            assertEquals(old.uuid, store.load(fixture.project, fixture.id).materialElements.single().uuid)
            assertEquals(ElementApproval.APPROVED, elements.find(old.uuid)?.approval)
            stale.saveInWorkspace()
            assertTrue(stale.state.value.conflict, stale.state.value.error)

            model.chooseMaterial(BusinessRole.CANVAS, catalog.getValue(newCanvas))
            model.chooseMaterial(BusinessRole.PRIMARY_PROFILE, catalog.getValue(profile))
            assertTrue(listOf(BusinessRole.CANVAS, BusinessRole.PRIMARY_PROFILE).all {
                model.materials(it).single().approval == ElementApproval.APPROVED
            })
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
            assertEquals(ElementApproval.APPROVED, canvas.approval)
            assertEquals(ElementApproval.APPROVED, savedProfile.approval)
            assertEquals(fixture.element.approval, elements.find(fixture.element.uuid)?.approval)
            assertNotNull(elements.find(old.uuid)?.deletedAt)
            assertNotNull(pointRepository.find(marker.uuid)?.deletedAt)
            assertEquals(fixture.element.quantity, elements.find(fixture.element.uuid)?.quantity)
        }
    }

    @Test
    fun workDeletionCascadesAllKindsAtomicallyAndCanReplayAfterLostResponse() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw)
            val db = PrototypeDatabase(raw)
            val fixture = MeasurementPricingFixture.seed(db)
            val id = assertNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid)).id
            val loseResponse = AtomicBoolean()
            val driver = object : SqlDriver by raw {
                override fun notifyListeners(vararg queryKeys: String) {
                    raw.notifyListeners(*queryKeys)
                    if ("ceiling_operations" in queryKeys && loseResponse.compareAndSet(true, false)) error("lost receipt")
                }
            }
            var clock = 1_000L
            val store = MeasurementEditStore(PrototypeDatabase(driver)) { ++clock }
            val model = MeasurementEditModel(store, store.load(fixture.projectId, id))
            model.addCatalogWork(addItem(db, "Point work", PlacementKind.POINT, "шт."), ElementQuantity.Exact(2000), true)
            assertTrue(model.placeWorkspacePoint(500, 500))
            model.addCatalogWork(addItem(db, "Line work", PlacementKind.LINE, "м.п."), ElementQuantity.Exact(1000), true)
            assertTrue(model.finishWorkspaceLine(PointPositionMm(500, 900), PointPositionMm(1500, 900)))
            model.addCatalogWork(addItem(db, "Area work", PlacementKind.AREA, "шт."), ElementQuantity.Exact(1000), true)
            model.areaTap(650, 1200)
            model.areaTap(1000, 1400)
            model.acceptAreaCreation()
            model.saveInWorkspace()
            assertFalse(model.state.value.dirty, model.state.value.error)

            val original = store.load(fixture.projectId, id)
            val point = original.placements.points.single()
            val line = original.placements.lines.single()
            val area = original.placements.areas.single()
            val workIds = listOf(point.elementUuid, line.elementUuid, assertNotNull(area.elementUuid))
            val history = ImmutableEstimateRepository(db)
            val saved = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, ++clock)
            val frozen = assertNotNull(history.frozenIndividual(saved.seriesUuid)).payload

            workIds.forEach(model::deleteWorkspaceWork)
            assertTrue(model.displayPoints().isEmpty() && model.displayLines().isEmpty() && model.displayAreas().isEmpty())
            repeat(workIds.size) { model.undo() }
            assertEquals(listOf(point), model.displayPoints())
            assertEquals(listOf(line), model.displayLines())
            assertEquals(listOf(area), model.displayAreas())
            repeat(workIds.size) { model.redo() }
            assertEquals(original, store.load(fixture.projectId, id))

            loseResponse.set(true)
            model.saveInWorkspace()
            assertTrue(model.state.value.uncertain, model.state.value.error)
            val committed = store.load(fixture.projectId, id)
            val elements = MeasurementElementRepository(db)
            val tombstones = workIds.map { assertNotNull(elements.find(it)?.deletedAt) }
            assertNotNull(PointPlacementRepository(db).tombstones().single { it.uuid == point.uuid }.deletedAt)
            assertNotNull(LinePlacementRepository(db).all().single { it.uuid == line.uuid }.deletedAt)
            assertNotNull(AreaFeatureRepository(db).all().single { it.uuid == area.uuid }.deletedAt)
            assertTrue(committed.placements.points.isEmpty() && committed.placements.lines.isEmpty() && committed.placements.areas.isEmpty())

            model.saveInWorkspace()
            assertFalse(model.state.value.uncertain, model.state.value.error)
            assertFalse(model.state.value.dirty)
            assertEquals(committed, store.load(fixture.projectId, id))
            assertEquals(tombstones, workIds.map { elements.find(it)?.deletedAt })
            assertEquals(frozen, history.frozenIndividual(saved.seriesUuid)?.payload)
        }
    }

    @Test
    fun lineAndAreaCapacityAreCheckedBeforeStartAndBeforeAccept() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw)
            val db = PrototypeDatabase(raw)
            val fixture = PointPlacementProof.fixture(db)
            val store = MeasurementEditStore(db) { 100 }
            val model = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
            val start = PointPositionMm(500, 500)
            val lineItem = addItem(db, "Capacity line", PlacementKind.LINE, "м.п.")
            model.addCatalogWork(lineItem, ElementQuantity.Range(500, 1000), true)
            assertTrue(model.finishWorkspaceLine(start, PointPositionMm(1200, 500)))
            val line = model.displayLines().single()
            assertTrue(model.lineHasCapacity(line.elementUuid))
            model.beginLine(line.elementUuid)
            assertFalse(model.finishWorkspaceLine(start, PointPositionMm(850, 600)))
            assertTrue(model.finishWorkspaceLine(start, PointPositionMm(800, 500)))
            assertFalse(model.lineHasCapacity(line.elementUuid))
            model.beginLine(line.elementUuid)
            assertNull(model.state.value.lineElementUuid)

            val areaItem = addItem(db, "Capacity area", PlacementKind.AREA, "шт.")
            model.addCatalogWork(areaItem, ElementQuantity.Exact(1000), true)
            model.areaTap(700, 1000)
            model.areaTap(1200, 1500)
            model.acceptAreaCreation()
            val areaId = assertNotNull(model.displayAreas().single().elementUuid)
            assertFalse(model.areaHasCapacity(areaId))
            model.beginArea(AreaRole.GENERAL_AREA, areaId)
            assertNull(model.state.value.area.tool)

            assertTrue(model.editWorkspaceQuantity(areaId, ElementQuantity.Unknown))
            assertTrue(model.areaHasCapacity(areaId))
            model.beginArea(AreaRole.GENERAL_AREA, areaId)
            model.areaTap(1300, 1600)
            model.areaTap(1700, 1800)
            model.acceptAreaCreation()
            assertEquals(2, model.displayAreas().size)
            assertNull(model.areaCapacityError())
            assertTrue(model.editWorkspaceQuantity(line.elementUuid, ElementQuantity.Unknown))
            assertTrue(model.lineHasCapacity(line.elementUuid))
            assertNull(model.lineCapacityError())
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
