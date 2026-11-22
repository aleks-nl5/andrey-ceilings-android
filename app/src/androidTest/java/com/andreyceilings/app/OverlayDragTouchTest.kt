package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.LivePricingReader
import com.andreyceilings.shared.livepricing.PricingReadResult
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Physical Android gestures on the production Details route and both editor surfaces. */
class OverlayDragTouchTest {
    @get:Rule val compose=createComposeRule()
    @Test fun detailsLight()=scenario(false,"details")
    @Test fun detailsDark()=scenario(true,"details")
    @Test fun editorNormalLight()=scenario(false,"editor")
    @Test fun editorFullscreenDark()=scenario(true,"fullscreen")
    private fun scenario(dark:Boolean,mode:String) {
        val context=ApplicationProvider.getApplicationContext<Context>();System.loadLibrary("sqlcipher")
        val name="overlay-drag-$mode-$dark.db";context.deleteDatabase(name);val key=ByteArray(32){92}
        var driver=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        val db=PrototypeDatabase(driver);val f=OverlayDragFixture.create(db);val store=MeasurementEditStore(db){200}
        val details=MeasurementDetailsStore(db);val doc=store.load(f.project,f.id)
        fun captureSelected(stage: String) {
            if (InstrumentationRegistry.getArguments().getString("p5aSelectedScreenshots") == "true") {
                compose.waitForIdle()
                captureMeasurementWorkspaceScreenshot("short-${if (dark) "dark" else "light"}-$mode-$stage")
            }
        }
        fun pricing(database: PrototypeDatabase) = assertIs<PricingReadResult.Loaded>(
            LivePricingReader(database).read(f.project, doc.draft.recordUuid))
        val m=MeasurementEditModel(store,doc)
        var mounted by mutableStateOf(true)
        compose.setContent {CeilingsTheme(dark) {if(mounted) {
            if(mode=="details")MeasurementDetailsScreen(f.project,f.id,details,PaddingValues(0.dp),0.dp,{},editStore=store)
            else MeasurementEditScreen(m,PaddingValues(0.dp),0.dp,{},null)
        }}}
        val matcher=SemanticsMatcher("ceiling canvas") {n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        fun click(label:String) {
            val action = (hasText(label) or hasContentDescription(label)) and hasClickAction()
            if(compose.onAllNodes(action).fetchSemanticsNodes().isEmpty()) {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(action)
            }
            val node=compose.onNode(action)
            // A partly clipped Text can be "displayed" under the pinned toolbar.
            // Scroll the merged action itself into view; pinned actions have no scroll ancestor.
            if(compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty())node.performScrollTo()
            // Standard controls use their accessible UI action; all Canvas selection,
            // movement, pinch and pan below still inject real Android pointers.
            node.assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { action ->
                assertTrue(action(),"UI action rejected: $label")
            }
        }
        fun canvas()=compose.onNode(matcher).also {
            if (mode == "details") it.performScrollTo()
            compose.waitForIdle()
        }
        fun position(x:Long,y:Long):Offset {
            val projection = canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
            val p = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
            return Offset(p.x.toFloat(),p.y.toFloat())
        }
        fun tap(x:Long,y:Long){val p=position(x,y);canvas().performTouchInput {click(p)}}
        val panel = compose.onNodeWithTag("measurement-edit-context-panel")
        fun openFromCanvas(x: Long, y: Long) {
            if (mode != "fullscreen") return
            panel.assertDoesNotExist()
            val p = position(x, y)
            canvas().performTouchInput { advanceEventTime(400); doubleClick(p) }
            panel.assertExists()
        }
        fun clearSelectionByTappingCanvas() {
            val before = canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Viewport]
            val draft = m.state.value
            tap(3450, 550)
            val after = canvas().fetchSemanticsNode().config
            assertNull(after.getOrNull(MeasurementDrawingSemantics.SelectedPoint))
            assertNull(after.getOrNull(MeasurementDrawingSemantics.SelectedLine))
            assertNull(after.getOrNull(MeasurementDrawingSemantics.SelectedArea))
            assertEquals(before, after[MeasurementDrawingSemantics.Viewport])
            assertEquals(draft.draft, m.state.value.draft)
            assertEquals(draft.area.edits, m.state.value.area.edits)
            assertEquals(draft.lineEdits, m.state.value.lineEdits)
            assertEquals(draft.pointEdits, m.state.value.pointEdits)
        }
        fun drag(x:Long,y:Long,toX:Long,toY:Long) {
            val a=position(x,y);val b=position(toX,toY);canvas().performTouchInput {swipe(a,b,500)}
        }
        try {
            compose.waitUntil(15000){compose.onAllNodes(matcher).fetchSemanticsNodes().any {it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled)==true}}
            val initialPricing = pricing(db)
            if(mode=="fullscreen")click("На весь екран")
            val before=OverlayDragFixture.snapshot(driver)
            if (mode != "details") {
                val point = doc.placements.points.single()
                val idleViewport = canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Viewport]
                drag(point.xMm, point.yMm, point.xMm - 800, point.yMm - 900)
                val afterIdleDrag = canvas().fetchSemanticsNode().config
                assertNull(afterIdleDrag.getOrNull(MeasurementDrawingSemantics.SelectedPoint))
                assertEquals(idleViewport, afterIdleDrag[MeasurementDrawingSemantics.Viewport])
                assertEquals(point, m.displayPoints().single())
                tap(point.xMm, point.yMm)
                val selected = canvas().fetchSemanticsNode()
                assertEquals(point.uuid, selected.config[MeasurementDrawingSemantics.SelectedPoint])
                if (mode == "fullscreen") {
                    panel.assertDoesNotExist()
                    captureSelected("single-tap-point")
                    drag(point.xMm, point.yMm, 2900, 2150)
                    panel.assertDoesNotExist()
                    compose.runOnIdle {
                        assertEquals(2900L, m.displayPoints().single().xMm)
                        assertEquals(2150L, m.displayPoints().single().yMm)
                    }
                    click("Скасувати дію")
                    panel.assertDoesNotExist()
                }
                openFromCanvas(point.xMm, point.yMm)
                captureSelected("selected-point")
                click("Задати розміри від стін")
                captureSelected("point-dialog")
                compose.onNode(hasText("Скасувати") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                if (mode == "fullscreen") click("Закрити панель")
                clearSelectionByTappingCanvas()
                tap(point.xMm, point.yMm)
                canvas().performTouchInput {
                    pinch(center - Offset(25f, 0f), center + Offset(25f, 0f),
                        center - Offset(45f, 0f), center + Offset(45f, 0f))
                }
                val viewport = canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Viewport]
                assertTrue(viewport.zoom > 1f)
                val firstWorldPoint = DrawingPoint(1000.0, 1500.0)
                val secondWorldPoint = DrawingPoint(3000.0, 1500.0)
                fun pixelDistance(projection: MeasurementDrawingProjection) =
                    projection.project(secondWorldPoint).x - projection.project(firstWorldPoint).x
                val baselineDistance = pixelDistance(canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection])
                click("Контур")
                if (mode == "fullscreen") {
                    compose.onNodeWithTag("measurement-edit-context-cost").assertDoesNotExist()
                    click("Закрити панель")
                } else {
                    click("Вартість")
                    compose.onNodeWithText("Розрахунок за останнім збереженим заміром").assertExists()
                }
                click("Елементи")
                val restored = canvas().fetchSemanticsNode()
                assertEquals(selected.id, restored.id)
                assertEquals(point.uuid, restored.config[MeasurementDrawingSemantics.SelectedPoint])
                assertEquals(viewport, restored.config[MeasurementDrawingSemantics.Viewport])
                assertEquals(baselineDistance, pixelDistance(restored.config[MeasurementDrawingSemantics.Projection]), 0.01)
                click("Вписати креслення")
                click("Зняти виділення")
                if (mode == "fullscreen") click("Закрити панель")
                assertEquals(before, OverlayDragFixture.snapshot(driver))
            }
            tap(1000,1500);drag(1000,1050,1300,1250) // selected AREA takes priority over LINE halo
            if(mode!="details")compose.runOnIdle {
                assertEquals(PointPositionMm(800,1200),m.displayAreas().first {it.uuid==f.polygon}.shape.positions.first(),"Canvas must move the selected polygon")
            }
            if (mode != "details") {
                if (mode == "fullscreen") {
                    panel.assertDoesNotExist()
                    captureSelected("single-tap-drag-area")
                }
                openFromCanvas(1300, 1250)
                captureSelected("selected-area")
                if (InstrumentationRegistry.getArguments().getString("p5aSelectedScreenshots") == "true") {
                    click("Властивості фігури")
                    captureSelected("area-dialog")
                    compose.onNode(hasText("Скасувати") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                }
                if (mode == "fullscreen") click("Закрити панель")
                clearSelectionByTappingCanvas()
                tap(1000, 1500)
                openFromCanvas(1000, 1500)
            }
            click("Скасувати дію");click("Повторити");click("Скасувати дію")
            click("Вершина 1");if (mode == "fullscreen") click("Закрити панель")
            drag(500,1000,800,1200)
            if(mode!="details")compose.runOnIdle {
                val polygon=m.displayAreas().first {it.uuid==f.polygon}
                assertEquals(PointPositionMm(800,1200),polygon.shape.positions.first(),"Canvas must move the explicit vertex")
                assertNull(polygon.mountings.first().anchor)
                assertTrue(polygon.mountings.drop(1).all {it.anchor!=null})
            }
            if (mode == "fullscreen") click("Елементи")
            click("Зняти виділення")
            if (mode == "fullscreen") click("Закрити панель")
            tap(1000,950)
            if(mode!="details")compose.runOnIdle {assertEquals(f.line,m.state.value.selectedLineUuid,"Canvas tap must select LINE UUID before pointer drag; area=${m.state.value.area.selected}; error=${m.state.value.error}")}
            if (mode != "details") {
                if (mode == "fullscreen") {
                    panel.assertDoesNotExist()
                    captureSelected("single-tap-line")
                }
                openFromCanvas(1000, 950)
                captureSelected("selected-line")
                if (InstrumentationRegistry.getArguments().getString("p5aSelectedScreenshots") == "true") {
                    click("Параметри лінії")
                    captureSelected("line-dialog")
                    compose.onNode(hasText("Скасувати") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
                }
                if (mode == "fullscreen") click("Закрити панель")
                clearSelectionByTappingCanvas()
                tap(1000, 950)
            }
            drag(1000,950,1300,750)
            if(mode!="details")compose.runOnIdle {
                assertEquals(f.line,m.state.value.selectedLineUuid,"Drag must retain LINE selection: ${m.state.value.error}")
                assertEquals(PointPositionMm(800,750),m.displayLines().single().start,"The injected gesture must move the selected LINE")
            }
            openFromCanvas(1300, 750)
            click("Кінець");if (mode == "fullscreen") click("Закрити панель")
            drag(1800,750,2400,750)
            tap(2800,1500)
            if (mode == "fullscreen") panel.assertDoesNotExist()
            drag(2800,1500,3100,1800)
            drag(3100,1800,4500,1800)
            val invalidDrag = "Фігура перетинає стіну або себе. Положення не змінено."
            if (mode != "details") {
                compose.onNodeWithTag("measurement-edit-status").performClick()
                compose.onNodeWithTag("measurement-edit-status-details").assertTextEquals(invalidDrag)
                compose.onNodeWithText("Закрити").performClick()
            } else compose.onNodeWithText(invalidDrag).assertExists()
            assertEquals(before,OverlayDragFixture.snapshot(driver))
            if (mode == "editor") {
                click("Вартість")
                compose.onNodeWithText("Розрахунок за останнім збереженим заміром").assertExists()
                compose.onNodeWithText("Переглянути збережений розрахунок у картці заміру").assertDoesNotExist()
                click("Елементи")
            }
            // Pinch cancels object ownership; empty-space pan affects only the viewport.
            canvas().performTouchInput {pinch(center-Offset(25f,0f),center+Offset(25f,0f),center-Offset(45f,0f),center+Offset(45f,0f))}
            click("Вписати креслення")
            val empty=position(3500,500);canvas().performTouchInput {swipe(empty,empty+Offset(35f,20f))};click("Вписати креслення")
            click(if(mode=="details")"Зберегти креслення" else "Зберегти замір")
            compose.waitUntil(15000){AreaFeatureRepository(db).forMeasurement(doc.draft.recordUuid).first {it.uuid==f.circle}.shape.positions.single().xMm==3100L}
            var saved=store.load(f.project,f.id)
            val line=saved.placements.lines.single();assertEquals(1600,line.lengthMm);assertEquals(PointPositionMm(800,750),line.start)
            assertEquals(PointPositionMm(2400,750),line.end);assertNull(line.startMounting.anchor);assertNull(line.endMounting.anchor)
            val area=saved.placements.areas.first {it.uuid==f.polygon}
            assertEquals(PointPositionMm(800,1200),area.shape.positions.first());assertNull(area.mountings.first().anchor)
            assertEquals(doc.placements.areas.first {it.uuid==f.polygon}.mountings.drop(1).map {it.anchor},area.mountings.drop(1).map {it.anchor})
            assertTrue(area.twiceAreaMm2!=doc.placements.areas.first {it.uuid==f.polygon}.twiceAreaMm2)
            val circle=saved.placements.areas.first {it.uuid==f.circle};assertEquals(AreaShape.Circle(PointPositionMm(3100,1800),200),circle.shape)
            assertEquals(PointPrecision.APPROXIMATE,circle.precision);assertEquals(doc.placements.points,saved.placements.points)
            assertEquals(doc.draft,saved.draft)
            var savedPricing = pricing(db)
            assertEquals(initialPricing.source.geometry, savedPricing.source.geometry)
            if (mode == "details") {
                val point = saved.placements.points.single()
                compose.waitUntil(15000) {
                    compose.onAllNodes(matcher).fetchSemanticsNodes().any {
                        it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled) == true
                    }
                }
                tap(point.xMm, point.yMm)
                assertEquals(point.uuid, canvas().fetchSemanticsNode().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint))
                drag(point.xMm, point.yMm, 3200, 2200)
                assertEquals(point, PointPlacementRepository(db).find(point.uuid))
                click("Зберегти креслення")
                compose.waitUntil(15000) { PointPlacementRepository(db).find(point.uuid)?.xMm == 3200L }
                saved = store.load(f.project, f.id)
                val pointPricing = pricing(db)
                assertNotEquals(savedPricing.source.signature, pointPricing.source.signature)
                assertEquals(savedPricing.buildPreview().potentialTotalCents, pointPricing.buildPreview().potentialTotalCents)
                savedPricing = pointPricing
                val calculator = hasText("Калькулятор заміру")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(calculator)
                compose.waitUntil(15000) {
                    compose.onAllNodesWithText("Погоджено:", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
            }
            compose.runOnIdle {mounted=false};compose.waitForIdle()
            runBlocking {details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()};driver.close()
            driver=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
            val reopened=MeasurementEditStore(PrototypeDatabase(driver)){300}.load(f.project,f.id)
            assertEquals(saved.placements,reopened.placements)
            assertEquals(savedPricing, pricing(PrototypeDatabase(driver)))
        } finally {
            compose.runOnIdle {mounted=false};compose.waitForIdle()
            runBlocking {details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()}
            driver.close();context.deleteDatabase(name);key.fill(0)
        }
    }
}
