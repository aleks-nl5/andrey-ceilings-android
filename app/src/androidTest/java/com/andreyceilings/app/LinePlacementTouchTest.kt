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
                    MeasurementEditScreen(shown, PaddingValues(0.dp), onBack = {}, onSaved = {})
                }
            }
        }
        fun click(label: String) {
            val action = hasText(label) and hasClickAction()
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
                click("Розмістити лінію")
                val canvas=compose.onNode(matcher)
                fun world(x:Long,y:Long):Offset {
                    val projection = canvas.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
                    val p = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
                    return Offset(p.x.toFloat(),p.y.toFloat())
                }
                fun tap(x:Long,y:Long) {val p=world(x,y);canvas.performTouchInput {click(p)}}
                canvas.performTouchInput {down(center);moveTo(center+Offset(80f,30f));up()}
                compose.runOnIdle {assertNull(model.state.value.lineStart);assertTrue(model.displayLines().isEmpty())}
                // Fit removes the pan, using the real UI action.
                compose.onNodeWithText("Вписати креслення").performClick()
                canvas.performTouchInput {down(center);cancel()}
                compose.runOnIdle {assertNull(model.state.value.lineStart)}
                canvas.performTouchInput {pinch(center-Offset(40f,0f),center+Offset(40f,0f),center-Offset(70f,0f),center+Offset(70f,0f))}
                compose.runOnIdle {assertNull(model.state.value.lineStart)}
                compose.onNodeWithText("Вписати креслення").performClick()
                tap(500,600);tap(3000,600)
                val uuid = model.state.value.linePreview!!.uuid
                click("Вартість")
                compose.onNodeWithText("Розрахунок за останнім збереженим заміром").assertExists()
                click("Елементи")
                click("Зберегти лінію в чернетці")
                compose.runOnIdle {
                    assertEquals(uuid,model.displayLines().single().uuid)
                    assertEquals(0L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM line_placements WHERE deletedAt IS NULL"))
                }
                tap(1800,600)
                compose.runOnIdle {assertEquals(uuid,model.state.value.selectedLineUuid)}
                // A cancelled selected drag and pinch may not commit a move.
                val before=model.displayLines().single()
                val selectedPosition=world(1800,600)
                canvas.performTouchInput {down(selectedPosition);moveTo(selectedPosition+Offset(60f,40f));cancel()}
                compose.runOnIdle {assertEquals(before,model.displayLines().single())}
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
                    compose.waitUntil(15000) { model.state.value.saved || model.state.value.error != null }
                    assertTrue(model.state.value.saved, model.state.value.error)
                    assertEquals(uuid,LinePlacementRepository(db).forMeasurement(element.measurementUuid).single().uuid)
                    // Remove via atomic editor to run the other theme against the same empty ceiling.
                    val clean=MeasurementEditModel(store,store.load(f.project,f.id))
                    clean.selectLine(uuid);clean.deleteLine();runBlocking {clean.save()}
                }
            }
        } finally {store.closePointReads();driver.close();context.deleteDatabase(name);key.fill(0)}
    }
}
