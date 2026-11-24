package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Real Android Compose pointer injection, separately from JVM ImageComposeScene tests. */
class LinePlacementTouchTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fullLineWorkDisablesPlacementAndShortEndpointRemainsTouchableAtMinimumZoom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "line-capacity-grips.db"
        context.deleteDatabase(name)
        val key = ByteArray(32) { 48 }
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val db = PrototypeDatabase(driver)
        val fixture = PointPlacementProof.fixture(db)
        val store = MeasurementEditStore(db) { 100 }
        val prices = PriceRepository(db)
        val elements = MeasurementElementRepository(db)
        fun work(code: String, cap: Long): MeasurementElement {
            val priceId = prices.createInGroup(code, code, PriceGroupRepository(db).all().first().uuid,
                1.0, 6, "м.п.", QuantityMode.ENTERED_LENGTH, PlacementKind.LINE)
            return elements.createCatalogSelection(fixture.element.measurementUuid,
                checkNotNull(prices.find(priceId)).uuid, ElementQuantity.Exact(cap), 7,
                businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        }
        val full = work("line.full", 1000)
        val short = work("line.short", 2500)
        val model = MeasurementEditModel(store, store.load(fixture.project, fixture.id))
        fun seed(work: MeasurementElement, x: Long, y: Long, length: Long) {
            model.beginLine(work.uuid)
            model.lineTap(x, y)
            model.lineTap(x + length, y)
            model.acceptLineCreation()
        }
        seed(full, 2700, 2750, 1000)
        seed(short, 2100, 1500, 250)
        val original = model.displayLines().single { it.elementUuid == short.uuid }
        model.clearDrawingSelection()
        try {
            compose.setContent { CeilingsTheme(false) { MeasurementEditScreen(model, PaddingValues(0.dp), onBack = {}) } }
            val matcher = SemanticsMatcher("ceiling canvas") {
                it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null
            }
            compose.waitUntil(15_000) {
                compose.onAllNodes(matcher).fetchSemanticsNodes().any {
                    it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled) == true
                }
            }
            fun click(label: String) {
                val action = (hasText(label) or hasContentDescription(label)) and hasClickAction()
                if (compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty())
                    compose.onNode(action).performScrollTo()
                compose.onNode(action).assertIsEnabled().performClick()
            }
            click("Елементи")
            // A leading approval icon now takes the first slot. The work label
            // remains separately clickable with an 8dp text inset.
            val rowBounds = compose.onNodeWithTag("p5b-work-row-${short.uuid}",
                useUnmergedTree = true).performScrollTo().fetchSemanticsNode().boundsInRoot
            val clickableBounds = compose.onNodeWithTag("p5b-work-label-click-${short.uuid}",
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val textBounds = compose.onNodeWithTag("p5b-work-label-text-${short.uuid}",
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val approvalBounds = compose.onNodeWithTag("p5b-approval-${short.uuid}",
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(approvalBounds.left >= rowBounds.left,
                "Approval button should start in the leading slot")
            assertTrue(clickableBounds.left > rowBounds.left,
                "Work label must follow the approval button")
            assertTrue(textBounds.left >= clickableBounds.left + 7f * compose.density.density,
                "Title text must keep its 8dp left inset inside the clickable surface")
            val placedLinePencil = compose.onNodeWithTag("p5b-edit-work-${short.uuid}")
                .performScrollTo().assertIsEnabled()
            placedLinePencil.performClick()
            val inlineLength = compose.onNodeWithTag("p5b-work-quantity-${short.uuid}")
                .performScrollTo()
            inlineLength.assertTextContains("2,5")
            // Unsaved typing must survive moving temporarily between work
            // pencils; pressing cancel intentionally discards it.
            inlineLength.performTextReplacement("2,7")
            compose.onNodeWithTag("p5b-edit-work-${full.uuid}").performScrollTo().performClick()
            compose.onNodeWithTag("p5b-work-quantity-${full.uuid}")
                .performScrollTo().assertTextContains("1")
            compose.onNodeWithTag("p5b-edit-work-${short.uuid}").performScrollTo().performClick()
            inlineLength.performScrollTo().assertTextContains("2,7")
            compose.onNodeWithTag("p5b-work-quantity-cancel-${short.uuid}")
                .performScrollTo().performClick()
            compose.onNodeWithTag("p5b-edit-work-${short.uuid}")
                .performScrollTo().performClick()
            inlineLength.performScrollTo().assertTextContains("2,5")
            compose.onNodeWithTag("p5b-line-declared-length").assertDoesNotExist()
            assertNull(model.state.value.lineProperties,
                "Pencil must edit declared length inline without opening gear parameters")
            inlineLength.performTextReplacement("2,6")
            click("Застосувати кількість до чернетки")
            assertEquals(ElementQuantity.Exact(2600),
                model.state.value.additions.editedWorks[short.uuid]?.quantity)
            assertEquals(original, model.displayLines().single { it.uuid == original.uuid },
                "Manual length must not resize the drawn line")
            // Work-list pencil can be reopened after the first placed segment.
            compose.onNodeWithTag("p5b-edit-work-${short.uuid}")
                .performScrollTo().performClick()
            compose.onNodeWithTag("p5b-work-quantity-${short.uuid}")
                .performScrollTo().performTextReplacement("2,5")
            click("Застосувати кількість до чернетки")
            assertEquals(ElementQuantity.Exact(2500),
                model.state.value.additions.editedWorks[short.uuid]?.quantity)
            // Gear is deliberately different: it opens the placed-line
            // geometry editor, not the inline work-quantity field.
            compose.onNodeWithTag("p5b-open-geometry-${short.uuid}")
                .performScrollTo().performClick()
            assertEquals(original.uuid, model.state.value.lineProperties?.uuid)
            compose.onNodeWithTag("p5b-line-declared-length")
                .performScrollTo().assertTextContains("2,5")
            compose.onNodeWithTag("p5b-work-quantity-${short.uuid}").assertDoesNotExist()
            compose.runOnIdle { model.discardLineProperties(); model.clearDrawingSelection() }
            compose.onNodeWithTag("measurement-workspace-add").performClick()
            compose.onNodeWithTag("p5b-linked-${full.uuid}").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Розмістити ще").assertIsNotEnabled()
            compose.onNodeWithText("Досягнуто заявленої кількості.").assertExists()
            assertNull(model.state.value.lineElementUuid)
            val canvas = compose.onNode(matcher)
            fun project(x: Long, y: Long): Offset {
                val projection = canvas.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
                val point = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
                return Offset(point.x.toFloat(), point.y.toFloat())
            }
            click("Вписати креслення")
            repeat(8) { click("Зменшити масштаб") }
            assertEquals(0.25f, canvas.fetchSemanticsNode().config[MeasurementDrawingSemantics.Viewport].zoom)
            val endpoint = project(original.start.xMm, original.start.yMm)
            canvas.performTouchInput { click(endpoint) }
            assertEquals(original.uuid, model.state.value.selectedLineUuid)
            val offset = Offset(0f, -10f * context.resources.displayMetrics.density)
            val destination = project(1100, original.start.yMm) + offset
            canvas.performTouchInput { swipe(endpoint + offset, destination, 500) }
            val moved = model.displayLines().single { it.uuid == original.uuid }
            assertEquals(original.end, moved.end)
            assertTrue(moved.start.xMm < original.start.xMm - 700)
            click("Скасувати дію")
            click("Редагувати розміри")
            compose.onNodeWithTag("p5b-line-declared-length").performScrollTo().performTextReplacement("0,325")
            click("Застосувати зміни")
            val unchanged = model.displayLines().single { it.uuid == original.uuid }
            assertEquals(original.start, unchanged.start)
            assertEquals(original.end, unchanged.end)
            assertEquals(250L, unchanged.lengthMm)
            assertEquals(ElementQuantity.Exact(325),
                model.state.value.additions.editedWorks[short.uuid]?.quantity)
            assertEquals(ElementQuantity.Exact(2500), elements.find(short.uuid)?.quantity,
                "Draft claim must not hit the database before overall Save")
            click("Редагувати розміри")
            compose.onNodeWithTag("p5b-line-declared-length").assertTextContains("0,325")
            compose.onNodeWithTag("p5b-line-take-length").performClick()
            click("Оновити кошторис")
            assertEquals(ElementQuantity.Exact(250),
                model.state.value.additions.editedWorks[short.uuid]?.quantity)
            compose.onNodeWithTag("p5b-line-declared-length").assertTextContains("0,25")
            assertEquals(unchanged, model.displayLines().single { it.uuid == original.uuid })
        } finally {
            store.closePointReads()
            driver.close()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    @Test fun twoTapSelectionPanPinchCancelAndFullscreenKeepOneAtomicDraft() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name="line-touch-test.db";context.deleteDatabase(name)
        val key=ByteArray(32){81}
        val driver=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
        val prices=PriceRepository(db)
        val id=prices.createInGroup("line.touch","Лінія",PriceGroupRepository(db).all().first().uuid,1.0,5,"м.п.",QuantityMode.ENTERED_LENGTH,PlacementKind.LINE)
        val element=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,prices.find(id)!!.uuid,ElementQuantity.Exact(4500),6, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val existingPoint = PointPlacementRepository(db).addPoint(f.element.version, f.contourVersion, 800, 1100, 8)
        val store=MeasurementEditStore(db){100}
        var model=MeasurementEditModel(store,store.load(f.project,f.id))
        var dark by mutableStateOf(false)
        var shown by mutableStateOf(model)
        val matcher=SemanticsMatcher("ceiling canvas") {node ->node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        compose.setContent {
            CeilingsTheme(dark) {
                key(shown) {
                    MeasurementEditScreen(shown, PaddingValues(0.dp), onBack = {})
                }
            }
        }
        fun click(label: String) {
            val action = (hasText(label) or hasContentDescription(label)) and hasClickAction()
            if (compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(action).performScrollTo()
            }
            compose.onNode(action).performClick()
        }
        try {
            for (theme in listOf(false, true)) {
                val document = store.load(f.project, f.id)
                compose.runOnIdle {
                    dark = theme
                    model = MeasurementEditModel(store, document)
                    shown = model
                }
                if (theme) click("На весь екран")
                click("Елементи")
                compose.onNodeWithTag("measurement-workspace-add").assertIsDisplayed().assertIsEnabled().performClick()
                compose.onNodeWithTag("p5b-linked-${element.uuid}").performScrollTo().assertIsEnabled().performClick()
                assertNull(model.state.value.lineElementUuid)
                click("Розмістити ще")
                val canvas=compose.onNode(matcher)
                fun world(x:Long,y:Long):Offset {
                    val projection = canvas.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
                    val p = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
                    return Offset(p.x.toFloat(),p.y.toFloat())
                }
                fun tap(x:Long,y:Long) {val p=world(x,y);canvas.performTouchInput {click(p)}}
                // Pan and pinch without creating an accidental placement.
                click("Вписати креслення")
                canvas.performTouchInput {down(center);cancel()}
                compose.runOnIdle {assertNull(model.state.value.lineStart)}
                canvas.performTouchInput {pinch(center-Offset(40f,0f),center+Offset(40f,0f),center-Offset(70f,0f),center+Offset(70f,0f))}
                compose.runOnIdle {assertNull(model.state.value.lineStart)}
                click("Вписати креслення")
                // The active LINE tool owns this press even though it hits an existing POINT.
                compose.onNodeWithTag("measurement-edit-rotate").performClick()
                compose.onNodeWithTag("measurement-edit-rotate").performClick()
                val start = world(existingPoint.xMm, existingPoint.yMm)
                val end = world(3000, 1300)
                if (theme) {
                    click("Паралельно стіні")
                    canvas.performTouchInput { down(start); moveTo(end) }
                    compose.runOnIdle { assertTrue(model.displayLines().isEmpty()) }
                    canvas.performTouchInput { up() }
                } else {
                    tap(800, 1100)
                    compose.runOnIdle {
                        assertNotNull(model.state.value.lineStart)
                        assertTrue(model.displayLines().isEmpty())
                    }
                    tap(3000, 1300)
                }
                val placed = model.displayLines().single()
                assertEquals(existingPoint.uuid, model.displayPoints().single().uuid)
                assertEquals(existingPoint.xMm, model.displayPoints().single().xMm)
                assertEquals(existingPoint.yMm, model.displayPoints().single().yMm)
                val uuid = placed.uuid
                if (theme) assertTrue(placed.end.yMm - placed.start.yMm > 100)
                else assertEquals(placed.start.yMm, placed.end.yMm)
                val midpointY = (placed.start.yMm + placed.end.yMm) / 2
                if (theme) {
                    compose.onNodeWithTag("measurement-edit-context-cost").assertDoesNotExist()
                    click("Згорнути")
                }
                click("Вартість")
                compose.onNodeWithText("Розрахунок за останнім збереженим заміром").assertExists()
                if (theme) click("На весь екран")
                click("Елементи")
                compose.onNodeWithText("Зберегти лінію в чернетці").assertDoesNotExist()
                compose.runOnIdle {
                    assertNull(model.state.value.linePreview)
                    assertEquals(uuid, model.state.value.selectedLineUuid)
                    assertEquals(uuid,model.displayLines().single().uuid)
                    assertEquals(0L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM line_placements WHERE deletedAt IS NULL"))
                }
                if (theme) click("Закрити панель")
                tap(1800,midpointY)
                compose.runOnIdle {assertEquals(uuid,model.state.value.selectedLineUuid)}
                // A cancelled selected drag and pinch may not commit a move.
                val before=model.displayLines().single()
                val panel = compose.onNodeWithTag("measurement-edit-context-panel")
                if (theme) panel.assertDoesNotExist()
                val selectedPosition=world(1800,midpointY)
                canvas.performTouchInput {down(selectedPosition);moveTo(selectedPosition+Offset(60f,40f));cancel()}
                compose.runOnIdle {assertEquals(before,model.displayLines().single())}
                if (theme) {
                    panel.assertDoesNotExist()
                    canvas.performTouchInput { advanceEventTime(400); doubleClick(selectedPosition) }
                    panel.assertExists()
                    compose.onNodeWithContentDescription("Редагувати розміри").assertExists()
                    click("Закрити панель")
                }
                click("Скасувати дію")
                compose.runOnIdle { assertTrue(model.displayLines().isEmpty()) }
                click("Повторити")
                if(!theme) {
                    compose.waitUntil(15000) {
                        compose.onAllNodes(matcher).fetchSemanticsNodes().any {
                            it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled) == true
                        } && compose.onAllNodes(hasTestTag("measurement-edit-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("measurement-edit-save").performClick()
                    compose.waitUntil(15000) { !model.state.value.dirty && !model.state.value.saving || model.state.value.error != null }
                    assertTrue(!model.state.value.dirty && !model.state.value.saving, model.state.value.error)
                    assertEquals(uuid,LinePlacementRepository(db).forMeasurement(element.measurementUuid).single().uuid)
                    // Remove via atomic editor to run the other theme against the same empty ceiling.
                    val clean=MeasurementEditModel(store,store.load(f.project,f.id))
                    clean.selectLine(uuid);clean.deleteLine();runBlocking {clean.save()}
                }
            }
        } finally {store.closePointReads();driver.close();context.deleteDatabase(name);key.fill(0)}
    }
}
