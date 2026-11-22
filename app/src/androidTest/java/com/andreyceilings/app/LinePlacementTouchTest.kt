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
                compose.onNodeWithTag("measurement-workspace-add").performScrollTo().assertIsEnabled().performClick()
                compose.onNodeWithTag("p5b-linked-${element.uuid}").performScrollTo().assertIsEnabled().performClick()
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
                val start = world(500, 220)
                val end = world(3000, 420)
                if (theme) {
                    click("Вирівнювати паралельно стіні")
                    canvas.performTouchInput { down(start); moveTo(end) }
                    compose.runOnIdle { assertTrue(model.displayLines().isEmpty()) }
                    canvas.performTouchInput { up() }
                } else {
                    tap(500, 220)
                    compose.runOnIdle {
                        assertNotNull(model.state.value.lineStart)
                        assertTrue(model.displayLines().isEmpty())
                    }
                    tap(3000, 420)
                }
                val placed = model.displayLines().single()
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
                    compose.onNodeWithText("Параметри лінії").assertExists()
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
