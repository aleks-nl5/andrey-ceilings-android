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
        val m=MeasurementEditModel(store,doc)
        var mounted by mutableStateOf(true)
        compose.setContent {CeilingsTheme(dark) {if(mounted) {
            if(mode=="details")MeasurementDetailsScreen(f.project,f.id,details,PaddingValues(0.dp),0.dp,{},editStore=store)
            else MeasurementEditScreen(m,PaddingValues(0.dp),0.dp,{}, {},null)
        }}}
        val matcher=SemanticsMatcher("ceiling canvas") {n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        fun click(label:String) {
            val node=compose.onNodeWithText(label,useUnmergedTree=true)
            if(!node.isDisplayed())node.performScrollTo()
            node.performClick()
        }
        fun canvas()=compose.onNode(matcher).also {if(mode!="fullscreen")it.performScrollTo()}
        fun position(x:Long,y:Long):Offset {
            val b=canvas().fetchSemanticsNode().boundsInRoot
            val contour=measurementDrawingContour(MeasurementGeometryView.Captured(validateMeasurementCreate(doc.draft).geometry!!))!!
            val p=measurementDrawingProjection(contour,b.width,b.height,compose.density.density)!!.project(DrawingPoint(x.toDouble(),y.toDouble()))
            return Offset(p.x.toFloat(),p.y.toFloat())
        }
        fun tap(x:Long,y:Long){val p=position(x,y);canvas().performTouchInput {click(p)}}
        fun drag(x:Long,y:Long,toX:Long,toY:Long) {
            val a=position(x,y);val b=position(toX,toY);canvas().performTouchInput {swipe(a,b,500)}
        }
        try {
            compose.waitUntil(15000){compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
            if(mode=="fullscreen")compose.onNodeWithText("На весь екран").performClick()
            val before=OverlayDragFixture.snapshot(driver)
            tap(1000,1500);drag(1000,1050,1300,1250) // selected AREA takes priority over LINE halo
            click("Скасувати дію");click("Повторити");click("Скасувати дію")
            click("Вершина 1");drag(500,1000,800,1200)
            click("Сегмент 1: 1 м");drag(1000,950,1300,750)
            click("Кінець");drag(1800,750,2400,750)
            tap(2800,1500);drag(2800,1500,3100,1800)
            drag(3100,1800,4500,1800)
            compose.onNodeWithText("Фігура перетинає стіну або себе. Положення не змінено.").assertExists()
            assertEquals(before,OverlayDragFixture.snapshot(driver))
            // Pinch cancels object ownership; empty-space pan affects only the viewport.
            canvas().performTouchInput {pinch(center-Offset(25f,0f),center+Offset(25f,0f),center-Offset(45f,0f),center+Offset(45f,0f))}
            click("Вписати креслення")
            val empty=position(3500,500);canvas().performTouchInput {swipe(empty,empty+Offset(35f,20f))};click("Вписати креслення")
            click(if(mode=="details")"Зберегти креслення" else "Зберегти")
            compose.waitUntil(15000){AreaFeatureRepository(db).forMeasurement(doc.draft.recordUuid).first {it.uuid==f.circle}.shape.positions.single().xMm==3100L}
            val saved=store.load(f.project,f.id)
            val line=saved.placements.lines.single();assertEquals(1600,line.lengthMm);assertEquals(PointPositionMm(800,750),line.start)
            assertEquals(PointPositionMm(2400,750),line.end);assertNull(line.startMounting.anchor);assertNull(line.endMounting.anchor)
            val area=saved.placements.areas.first {it.uuid==f.polygon}
            assertEquals(PointPositionMm(800,1200),area.shape.positions.first());assertNull(area.mountings.first().anchor)
            assertEquals(doc.placements.areas.first {it.uuid==f.polygon}.mountings.drop(1).map {it.anchor},area.mountings.drop(1).map {it.anchor})
            assertTrue(area.twiceAreaMm2!=doc.placements.areas.first {it.uuid==f.polygon}.twiceAreaMm2)
            val circle=saved.placements.areas.first {it.uuid==f.circle};assertEquals(AreaShape.Circle(PointPositionMm(3100,1800),200),circle.shape)
            assertEquals(PointPrecision.APPROXIMATE,circle.precision);assertEquals(doc.placements.points,saved.placements.points)
            assertEquals(doc.draft,saved.draft)
            compose.runOnIdle {mounted=false};compose.waitForIdle()
            runBlocking {details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()};driver.close()
            driver=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
            val reopened=MeasurementEditStore(PrototypeDatabase(driver)){300}.load(f.project,f.id)
            assertEquals(saved.placements,reopened.placements)
        } finally {
            compose.runOnIdle {mounted=false};compose.waitForIdle()
            runBlocking {details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()}
            driver.close();context.deleteDatabase(name);key.fill(0)
        }
    }
}
