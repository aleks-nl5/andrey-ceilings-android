package com.andreyceilings.desktop

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlin.test.*

class MixedDetailsSceneTest {
    @Test fun allMixedOrdersUseProductionDetailsAndOneSaveOrExplicitDiscard()=runBlocking {
        for((index,order) in listOf("point_line_point","line_point_area","area_point_line","area_point_line","unresolved").withIndex()) {
            val dir=java.nio.file.Files.createTempDirectory("mixed-details-").toFile();val file=dir.resolve("scene.db")
            val raw=open(file);PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=MixedOverlayProof.create(db,index==4)
            val base=OverlayDragFixture.snapshot(raw);val operations=operations(raw);val store=MeasurementEditStore(db){200}
            val details=MeasurementDetailsStore(db,{200},Dispatchers.Unconfined);val row=MeasurementRepository(db).find(f.overlay.id)!!
            val initial=if(index%2==0)AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(f.overlay.project).openMeasurement(f.overlay.project,f.overlay.id)
                else AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(f.overlay.project,f.overlay.id)
            var nav by mutableStateOf(initial);var transitions=0;val guard=MeasurementCreateNavigationGuard()
            fun apply(next:AppNavigationState){if(next!=nav){transitions++;nav=next}}
            fun navigate(next:AppNavigationState){val current=nav;val accepted=guard.transition(current,next,::apply);if(accepted!=current)apply(accepted)}
            val scene=ImageComposeScene(900,1500) {CeilingsTheme(index!=0 && index!=3) {
                if(nav.measurementDetails!=null)MeasurementDetailsScreen(f.overlay.project,f.overlay.id,details,PaddingValues(0.dp),0.dp,
                    onBack={navigate(nav.back())},navigationGuard=guard,editStore=store)
                else Text("Destination")
            }}
            var expected:MeasurementEditDocument?=null
            try {
                withTimeout(15000){while(scene.nodes().none {it.textTree()=="Сегмент 1: 1 м" && it.config.getOrNull(SemanticsProperties.Disabled)==null}){scene.frame();delay(20)}}
                val canvas=object:MixedOverlayProof.Canvas {
                    override fun tap(x:Long,y:Long){scene.tap(row,x,y)}
                    override fun drag(x:Long,y:Long,toX:Long,toY:Long){scene.drag(row,x,y,toX,toY)}
                    override fun click(label:String){scene.click(label)}
                    override fun points()=scene.canvas().config[MeasurementDrawingSemantics.Points]
                    override fun lines()=scene.canvas().config[MeasurementDrawingSemantics.Lines]
                    override fun areas()=scene.canvas().config[MeasurementDrawingSemantics.Areas]
                    override fun selectedPoint()=scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint)
                    override fun selectedLine()=scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedLine)
                    override fun selectedArea()=scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedArea)
                    override fun databaseUnchanged(){assertEquals(base,OverlayDragFixture.snapshot(raw))}
                }
                if(index==4)MixedOverlayProof.exerciseUnresolved(f,canvas) else MixedOverlayProof.exercise(f,canvas,order)
                if(index!=4)for(label in listOf("Видалити позначку","Задати розміри від стін")) {
                    assertNotNull(scene.nodes().first {it.textTree()==label && it.config.getOrNull(SemanticsActions.OnClick)!=null}.config.getOrNull(SemanticsProperties.Disabled),label)
                }
                val points=canvas.points();val lines=canvas.lines();val areas=canvas.areas()
                if(index>=3) {
                    if(index==4)assertNotNull(scene.nodes().first {it.textTree()=="Зберегти креслення" && it.config.getOrNull(SemanticsActions.OnClick)!=null}.config.getOrNull(SemanticsProperties.Disabled))
                    scene.click("До об'єкта");scene.awaitText("Незбережені зміни креслення");scene.click("Продовжити")
                    assertEquals(0,transitions);assertEquals(points,canvas.points());assertEquals(lines,canvas.lines());assertEquals(areas,canvas.areas())
                    scene.click("До об'єкта");scene.awaitText("Незбережені зміни креслення");scene.click("Відхилити зміни")
                    assertEquals(1,transitions);assertEquals(base,OverlayDragFixture.snapshot(raw));expected=f.original
                } else {
                    if(index==0){scene.click("До об'єкта");scene.awaitText("Незбережені зміни креслення")}
                    scene.click("Зберегти креслення")
                    withTimeout(15000){while(operations(raw)!=operations+1 || index==0 && transitions!=1){scene.frame();delay(20)}}
                    expected=store.load(f.overlay.project,f.overlay.id)
                    MixedOverlayProof.verifySaved(f,points,lines,areas,expected)
                    assertEquals(if(index==0)1 else 0,transitions);assertEquals(operations+1,operations(raw))
                    assertEquals(0L,scalar(raw,"SELECT count(*) FROM point_position_operations"),"No standalone POINT writer")
                }
            } finally {
                nav=initial.back();repeat(4){scene.frame();delay(15)}
                details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads();scene.close();raw.close()
            }
            open(file).use {cold->assertEquals(expected,MeasurementEditStore(PrototypeDatabase(cold)){300}.load(f.overlay.project,f.overlay.id))}
            dir.deleteRecursively()
        }
    }
    private fun open(file:java.io.File):SqlDriver=if(System.getProperty("os.name").startsWith("Windows",true))
        SqlCipherWindowsDriverFactory.open(file,ByteArray(32){97}) else JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    private fun scalar(d:SqlDriver,sql:String)=d.executeQuery(null,sql,{c->c.next();QueryResult.Value(c.getLong(0)!!)},0).value
    private fun operations(d:SqlDriver)=scalar(d,"SELECT count(*) FROM ceiling_operations")
    private fun ImageComposeScene.frame(){Snapshot.sendApplyNotifications();render(System.nanoTime()).close()}
    private fun ImageComposeScene.nodes():List<SemanticsNode>{
        fun all(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::all)
        return semanticsOwners.flatMap {all(it.rootSemanticsNode)}
    }
    private fun ImageComposeScene.canvas():SemanticsNode {frame();return nodes().first {it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}}
    private fun SemanticsNode.textTree():String=config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString {it.text}+children.joinToString {it.textTree()}
    private fun ImageComposeScene.awaitText(label:String){repeat(240){frame();if(nodes().any {it.textTree()==label})return;Thread.sleep(25)};fail("Missing $label")}
    private fun ImageComposeScene.click(label:String) {
        var top=false
        repeat(240){frame();nodes().filter {it.config.getOrNull(SemanticsActions.OnClick)!=null &&
            (it.textTree()==label || it.config.getOrNull(SemanticsActions.OnClick)?.label==label)}.lastOrNull()?.let {
                assertNull(it.config.getOrNull(SemanticsProperties.Disabled),label);assertTrue(it.config[SemanticsActions.OnClick].action!!.invoke());frame();return
            }
            nodes().firstOrNull {it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null}?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,if(!top)-10000f else 150f)
            top=true;Thread.sleep(25)
        };fail("Missing action $label")
    }
    private fun ImageComposeScene.bounds():Rect {
        repeat(15){val n=canvas();val b=Rect(n.positionInRoot,Size(n.size.width.toFloat(),n.size.height.toFloat()))
            val h=semanticsOwners.first().rootSemanticsNode.size.height.toFloat()
            val delta=if(b.bottom>h-100)b.bottom-h+150 else if(b.top<100)b.top-200 else 0f
            if(delta==0f)return@repeat
            nodes().firstOrNull {it.config.getOrNull(SemanticsActions.ScrollBy)!=null}?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,delta)
            repeat(5){frame();Thread.sleep(10)}
        }
        var last:Rect?=null;var stable=0
        repeat(150){val n=canvas();val b=Rect(n.positionInRoot,Size(n.size.width.toFloat(),n.size.height.toFloat()))
            stable=if(last==b)stable+1 else 0;if(stable>=3)return b;last=b;Thread.sleep(20)};fail("Unstable Canvas")
    }
    private fun ImageComposeScene.gesture(row:Room_measurements,x:Long,y:Long,toX:Long,toY:Long,move:Boolean) {
        val b=bounds();val contour=measurementDrawingContour(measurementGeometryForViewing(row.shape,row.drawingJson,row.lengthMeters,row.widthMeters))!!
        val projection=measurementDrawingProjection(contour,b.width,b.height)!!
        fun p(x:Long,y:Long):Offset {val p=projection.project(DrawingPoint(x.toDouble(),y.toDouble()));return Offset(b.left+p.x.toFloat(),b.top+p.y.toFloat())}
        val from=p(x,y);val to=p(toX,toY)
        sendPointerEvent(PointerEventType.Press,from,type=PointerType.Mouse)
        if(move){sendPointerEvent(PointerEventType.Move,to,type=PointerType.Mouse);frame()}
        sendPointerEvent(PointerEventType.Release,to,type=PointerType.Mouse);frame()
    }
    private fun ImageComposeScene.tap(row:Room_measurements,x:Long,y:Long)=gesture(row,x,y,x,y,false)
    private fun ImageComposeScene.drag(row:Room_measurements,x:Long,y:Long,toX:Long,toY:Long)=gesture(row,x,y,toX,toY,true)
}
