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
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Production controls + production shared screen, with real Android pointer injection. */
class AreaFeatureTouchTest {
    @get:Rule val compose=createComposeRule()
    @Test fun lightNormalAndFullscreenAtomicCrud()=scenario(false)
    @Test fun darkNormalAndFullscreenAtomicCrud()=scenario(true)
    private fun scenario(dark:Boolean) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name="area-touch-$dark.db";context.deleteDatabase(name)
        val key=ByteArray(32){86}
        val driver=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
        val prices=PriceRepository(db)
        val price=prices.find(prices.createInGroup("area.touch","Область UI",PriceGroupRepository(db).all().first().uuid,1.0,6,"шт.",QuantityMode.MANUAL,PlacementKind.AREA))!!
        val element=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,price.uuid,ElementQuantity.Unknown,7, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val store=MeasurementEditStore(db){100}
        val model=MeasurementEditModel(store,store.load(f.project,f.id))
        compose.setContent {CeilingsTheme(dark) {MeasurementEditScreen(model,PaddingValues(0.dp),0.dp,{},null)}}
        val matcher=SemanticsMatcher("ceiling canvas") {n->n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        fun click(text: String) {
            val action = (hasText(text) or hasContentDescription(text)) and hasClickAction()
            if (compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(action).performScrollTo()
            }
            compose.onNode(action).performClick()
        }
        fun tap(x:Long,y:Long) {
            val canvas=compose.onNode(matcher)
            val projection = canvas.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
            val p = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
            canvas.performTouchInput {click(Offset(p.x.toFloat(),p.y.toFloat()))}
        }
        try {
            click("Елементи")
            click("Позначити перешкоду");tap(500,500);tap(1000,1000);click("Завершити фігуру в чернетці")
            compose.runOnIdle {assertEquals(1,model.displayAreas().size);assertEquals(0,AreaFeatureRepository(db).all().size)}
            click("Позначити отвір");compose.onNodeWithText("Діаметр, м (парні мм)").performScrollTo().performTextInput("0,2");tap(2000,1500);click("Завершити фігуру в чернетці")
            click("Розмістити область: ${element.label}");tap(1500,500);tap(2000,1000);click("Завершити фігуру в чернетці")
            compose.runOnIdle {assertEquals(3,model.displayAreas().size)}
            click("Скасувати дію")
            compose.runOnIdle {assertEquals(2,model.displayAreas().size)}
            click("Повторити")
            click("На весь екран")
            click("Вписати креслення")
            val canvas=compose.onNode(matcher)
            canvas.performTouchInput {pinch(center-Offset(30f,0f),center+Offset(30f,0f),center-Offset(50f,0f),center+Offset(50f,0f))}
            click("Вписати креслення")
            click("Згорнути")
            tap(2200,1500) // accessible halo selects the circle
            compose.runOnIdle {assertNotNull(model.state.value.area.selected)}
            click("Властивості фігури")
            compose.onNodeWithText("Примітка фігури").performTextInput("Тестова позначка")
            compose.onNodeWithText("Застосувати властивості").performClick()
            click("Видалити фігуру");compose.onNodeWithText("Видалити",useUnmergedTree=true).performClick()
            compose.runOnIdle {assertEquals(2,model.displayAreas().size)}
            click("Скасувати дію")
            val ids=model.displayAreas().map {it.uuid}.toSet()
            compose.onNodeWithTag("measurement-edit-save").performClick()
            compose.waitUntil(15000) {!model.state.value.dirty && !model.state.value.saving || model.state.value.error!=null}
            compose.runOnIdle {assertTrue(!model.state.value.dirty && !model.state.value.saving,model.state.value.error);assertEquals(ids,store.load(f.project,f.id).placements.areas.map {it.uuid}.toSet())}
        } finally {store.closePointReads();driver.close();context.deleteDatabase(name);key.fill(0)}
    }
}
