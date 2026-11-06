package com.andreyceilings.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.QueryResult
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MixedDetailsTouchTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun pointLinePointObjectsLight()=scenario("point_line_point",false,false)
    @Test fun linePointAreaMeasurementsDark()=scenario("line_point_area",true,false)
    @Test fun linePointAreaMeasurementsLight()=scenario("line_point_area",false,false)
    @Test fun areaPointLineObjectsDark()=scenario("area_point_line",true,false)
    @Test fun mixedBackContinueDiscardLight()=scenario("area_point_line",false,true)
    @Test fun unresolvedPointCannotSaveMixedDraftWithoutRemeasurement()=scenario("unresolved",true,true)
    private fun scenario(order:String,dark:Boolean,discard:Boolean) {
        val context=ApplicationProvider.getApplicationContext<Context>();System.loadLibrary("sqlcipher")
        val name="mixed-$order-$dark-$discard.db";context.deleteDatabase(name);val key=ByteArray(32){97}
        val raw=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        val db=PrototypeDatabase(raw);val f=MixedOverlayProof.create(db,order=="unresolved");val base=OverlayDragFixture.snapshot(raw)
        fun operations()=raw.executeQuery(null,"SELECT count(*) FROM ceiling_operations",{c->c.next();QueryResult.Value(c.getLong(0)!!)},0).value
        val operations=operations();val store=MeasurementEditStore(db){200};val details=MeasurementDetailsStore(db)
        val initial=if(order=="line_point_area")AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(f.overlay.project,f.overlay.id)
            else AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(f.overlay.project).openMeasurement(f.overlay.project,f.overlay.id)
        val guard=MeasurementCreateNavigationGuard();var nav by mutableStateOf(initial);val transitions=AtomicInteger()
        fun apply(next:AppNavigationState){if(next!=nav){transitions.incrementAndGet();nav=next}}
        fun navigate(next:AppNavigationState){val current=nav;val accepted=guard.transition(current,next,::apply);if(accepted!=current)apply(accepted)}
        compose.setContent {CeilingsTheme(dark) {
            BackHandler(nav.measurementDetails!=null){navigate(nav.back())}
            if(nav.measurementDetails!=null)MeasurementDetailsScreen(f.overlay.project,f.overlay.id,details,PaddingValues(0.dp),0.dp,
                onBack={navigate(nav.back())},navigationGuard=guard,editStore=store)
            else Text("Destination")
        }}
        val matcher=SemanticsMatcher("Canvas"){it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        fun canvas()=compose.onNode(matcher).performScrollTo().also {compose.waitForIdle()}
        fun p(x:Long,y:Long):Offset {
            val size=canvas().fetchSemanticsNode().size
            val contour=measurementDrawingContour(MeasurementGeometryView.Captured(validateMeasurementCreate(f.original.draft).geometry!!))!!
            val p=measurementDrawingProjection(contour,size.width.toFloat(),size.height.toFloat(),compose.density.density)!!.project(DrawingPoint(x.toDouble(),y.toDouble()))
            return Offset(p.x.toFloat(),p.y.toFloat())
        }
        fun dialog(label:String)=compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
        fun back(){compose.runOnUiThread {compose.activity.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
        fun awaitDialog()=compose.waitUntil(15000){compose.onAllNodesWithText("Незбережені зміни креслення").fetchSemanticsNodes().isNotEmpty()}
        var expected:MeasurementEditDocument?=null
        try {
            compose.waitUntil(15000){compose.onAllNodes(matcher).fetchSemanticsNodes().any {it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled)==true}}
            val ui=object:MixedOverlayProof.Canvas {
                override fun tap(x:Long,y:Long){val at=p(x,y);canvas().performTouchInput {click(at)}}
                override fun drag(x:Long,y:Long,toX:Long,toY:Long){val a=p(x,y);val b=p(toX,toY);canvas().performTouchInput {swipe(a,b,500)}}
                override fun click(label:String){
                    val action=hasText(label) and hasClickAction()
                    if(compose.onAllNodes(action).fetchSemanticsNodes().isEmpty())compose.onNode(hasScrollToIndexAction()).performScrollToNode(action)
                    val node=compose.onNode(action)
                    if(compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty())node.performScrollTo()
                    node.assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick){assertTrue(it())}
                }
                override fun points()=canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Points]
                override fun lines()=canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Lines]
                override fun areas()=canvas().fetchSemanticsNode().config[MeasurementDrawingSemantics.Areas]
                override fun selectedPoint()=canvas().fetchSemanticsNode().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint)
                override fun selectedLine()=canvas().fetchSemanticsNode().config.getOrNull(MeasurementDrawingSemantics.SelectedLine)
                override fun selectedArea()=canvas().fetchSemanticsNode().config.getOrNull(MeasurementDrawingSemantics.SelectedArea)
                override fun databaseUnchanged(){assertEquals(base,OverlayDragFixture.snapshot(raw))}
            }
            if(order=="unresolved")MixedOverlayProof.exerciseUnresolved(f,ui) else MixedOverlayProof.exercise(f,ui,order)
            if(order!="unresolved") {
                compose.onNode(hasText("Видалити позначку") and hasClickAction()).assertIsNotEnabled()
                compose.onNode(hasText("Задати розміри від стін") and hasClickAction()).assertIsNotEnabled()
            }
            val points=ui.points();val lines=ui.lines();val areas=ui.areas()
            // Pinch cancels object ownership without modifying any of the mixed staged edits.
            val marker=points.single();val pivot=p(marker.xMm,marker.yMm)
            canvas().performTouchInput {pinch(pivot-Offset(5f,0f),pivot+Offset(5f,0f),pivot-Offset(45f,0f),pivot+Offset(45f,0f))}
            ui.click("Вписати креслення");assertEquals(points,ui.points());assertEquals(lines,ui.lines());assertEquals(areas,ui.areas())
            ui.databaseUnchanged()
            if(discard) {
                if(order=="unresolved")compose.onNode(hasText("Зберегти креслення") and hasClickAction()).assertIsNotEnabled()
                back();awaitDialog();dialog("Продовжити").performClick();assertEquals(0,transitions.get())
                assertEquals(points,ui.points());assertEquals(lines,ui.lines());assertEquals(areas,ui.areas())
                back();awaitDialog();dialog("Відхилити зміни").performClick()
                compose.waitUntil(15000){transitions.get()==1};assertEquals(base,OverlayDragFixture.snapshot(raw));expected=f.original
            } else {
                if(order=="point_line_point") {back();awaitDialog();dialog("Зберегти креслення").performClick()}
                else ui.click("Зберегти креслення")
                compose.waitUntil(15000){operations()==operations+1 && (order!="point_line_point" || transitions.get()==1)}
                expected=store.load(f.overlay.project,f.overlay.id)
                MixedOverlayProof.verifySaved(f,points,lines,areas,expected)
                assertEquals(if(order=="point_line_point")1 else 0,transitions.get());assertEquals(operations+1,operations())
                assertEquals(0L,raw.executeQuery(null,"SELECT count(*) FROM point_position_operations",{c->c.next();QueryResult.Value(c.getLong(0)!!)},0).value)
            }
        } finally {
            compose.runOnIdle {nav=initial.back()};compose.waitForIdle()
            runBlocking {details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()};raw.close()
        }
        val cold=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        try {assertEquals(expected,MeasurementEditStore(PrototypeDatabase(cold)){300}.load(f.overlay.project,f.overlay.id))}
        finally {cold.close();context.deleteDatabase(name);key.fill(0)}
    }
}
