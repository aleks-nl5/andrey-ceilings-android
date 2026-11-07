package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.text.AnnotatedString
import com.andreyceilings.shared.prices.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DetailsSaveExitSceneTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun realDetailsDialogSavesThenNavigatesAndUncertainRetryKeepsIntent():Unit=runBlocking {
        for(mode in listOf("back","edit","uncertain")) {
            val dir=java.nio.file.Files.createTempDirectory("details-exit-scene-").toFile()
            val raw=if(System.getProperty("os.name").startsWith("Windows",true))SqlCipherWindowsDriverFactory.open(dir.resolve("scene.db"),ByteArray(32){94}) else JdbcSqliteDriver("jdbc:sqlite:${dir.resolve("scene.db").absolutePath}")
            PriceCatalogSchema.create(raw);val f=OverlayDragFixture.create(PrototypeDatabase(raw));val fault=DetailsExitFaultDriver(raw)
            val db=PrototypeDatabase(fault);val store=MeasurementEditStore(db){200};val details=MeasurementDetailsStore(db,{200},Dispatchers.Unconfined)
            val row=MeasurementRepository(db).find(f.id)!!;val guard=MeasurementCreateNavigationGuard()
            var destination by mutableStateOf<String?>(null);var navigations=0
            fun navigate(target:String) {
                assertEquals(PointPositionMm(800,1200),store.load(f.project,f.id).placements.areas.first {it.uuid==f.polygon}.shape.positions.first())
                navigations++;destination=target
            }
            val scene=ImageComposeScene(900,1400) {CeilingsTheme(mode!="back") {
                if(destination==null)MeasurementDetailsScreen(f.project,f.id,details,PaddingValues(0.dp),0.dp,
                    onBack={navigate("back")},onEdit={navigate("edit")},navigationGuard=guard,editStore=store)
                else androidx.compose.material3.Text("Destination $destination")
            }}
            try {
                withTimeout(15000) {
                    var ready=false
                    while(!ready) {
                        scene.frame()
                        ready=scene.nodes().any {it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled)==true}
                        if(!ready)delay(20)
                    }
                }
                scene.tapWorld(row,1000,1500);scene.dragWorld(row,1000,1500,1300,1700)
                assertEquals(1L,operationCount(raw))
                if(mode=="uncertain")fault.failNext(DetailsExitFaultDriver.Fault.LOST_RESPONSE)
                scene.click(if(mode=="edit")"Редагувати" else "До об'єкта")
                scene.awaitText("Незбережені зміни креслення")
                scene.click("Зберегти креслення")
                if(mode=="uncertain") {
                    scene.awaitText("Повторити ту саму спробу")
                    assertEquals(0,navigations);assertNull(destination)
                    val committed=OverlayDragFixture.snapshot(raw)
                    scene.click("Повторити ту саму спробу")
                    withTimeout(15000){while(destination==null){scene.frame();delay(20)}}
                    assertEquals(committed,OverlayDragFixture.snapshot(raw))
                } else withTimeout(15000){while(destination==null){scene.frame();delay(20)}}
                repeat(5){scene.frame();delay(10)}
                assertEquals(if(mode=="edit")"edit" else "back",destination)
                assertEquals(1,navigations)
                assertEquals(2L,operationCount(raw))
            } finally {
                details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()
                repeat(6){scene.frame();delay(20)};scene.close();raw.close();dir.deleteRecursively()
            }
        }
    }
    private fun ImageComposeScene.dragWorld(row:Room_measurements,x:Long,y:Long,toX:Long,toY:Long) {
        val b=stableBounds()
        val contour=measurementDrawingContour(measurementGeometryForViewing(row.shape,row.drawingJson,row.lengthMeters,row.widthMeters))!!
        val projection=measurementDrawingProjection(contour,b.width,b.height)!!
        fun project(px:Long,py:Long):Offset {val p=projection.project(DrawingPoint(px.toDouble(),py.toDouble()));return Offset(b.left+p.x.toFloat(),b.top+p.y.toFloat())}
        val start=project(x,y);val end=project(toX,toY)
        sendPointerEvent(PointerEventType.Press,start,type=PointerType.Mouse)
        sendPointerEvent(PointerEventType.Move,end,type=PointerType.Mouse)
        frame()
        sendPointerEvent(PointerEventType.Release,end,type=PointerType.Mouse);frame()
    }
    private fun ImageComposeScene.world(row: Room_measurements, x: Long, y: Long): Offset {
        stableBounds()
        val node=canvas()
        val b=Rect(node.positionInRoot,androidx.compose.ui.geometry.Size(node.size.width.toFloat(),node.size.height.toFloat()))
        val contour = measurementDrawingContour(measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters))!!
        val p = measurementDrawingProjection(contour, b.width, b.height)!!.project(DrawingPoint(x.toDouble(), y.toDouble()))
        return Offset(b.left + p.x.toFloat(), b.top + p.y.toFloat())
    }
    private fun ImageComposeScene.tapWorld(row: Room_measurements, x: Long, y: Long) {
        val p = world(row, x, y)
        sendPointerEvent(PointerEventType.Press, p, type = PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, p, type = PointerType.Touch); frame()
    }
    private fun ImageComposeScene.stableBounds(): Rect {
        var previous: Rect? = null; var stable = 0
        repeat(15) {
            frame()
            val c=canvas();val b=Rect(c.positionInRoot,androidx.compose.ui.geometry.Size(c.size.width.toFloat(),c.size.height.toFloat()))
            val rootHeight=semanticsOwners.first().rootSemanticsNode.size.height.toFloat()
            val delta=if(b.bottom>rootHeight-100)b.bottom-(rootHeight-150) else if(b.top<100)b.top-200 else 0f
            if(delta==0f)return@repeat
            nodes().firstOrNull {it.config.getOrNull(SemanticsActions.ScrollBy)!=null}?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,delta)
            repeat(5){frame();Thread.sleep(10)}
        }
        repeat(150) { frame(); val c=canvas();val b=Rect(c.positionInRoot,androidx.compose.ui.geometry.Size(c.size.width.toFloat(),c.size.height.toFloat())); stable = if (b == previous) stable + 1 else 0
            if (stable >= 3) return b; previous = b; Thread.sleep(20) }
        fail("Canvas did not settle")
    }
    private fun ImageComposeScene.canvas() = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Контур стелі") } }
    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(System.nanoTime()).close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.awaitText(label: String) { repeat(240) { frame(); if (text().contains(label)) return; Thread.sleep(25) }; fail("Missing '$label': ${text().take(2000)}") }
    private fun ImageComposeScene.click(label: String) {
        var searchedTop=false
        repeat(240) { frame(); nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null && (it.textTree() == label || it.config.getOrNull(SemanticsActions.OnClick)?.label==label) }.let {matches -> if(label=="Зберегти креслення" || label=="Повторити ту саму спробу")matches.lastOrNull() else matches.firstOrNull()}?.let {
            assertNull(it.config.getOrNull(SemanticsProperties.Disabled), label)
            assertTrue(it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true); frame(); return }
            nodes().firstOrNull {it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null}?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,if(!searchedTop)-10000f else 150f)
            searchedTop=true;Thread.sleep(25) }
        fail("Missing action '$label': ${text().take(2000)}")
    }
    private fun ImageComposeScene.setField(label: String, value: String) {
        repeat(240) { frame(); nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains(label) }?.let {
            assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true); frame(); return }; Thread.sleep(25) }
        fail("Missing field '$label': ${text().take(2000)}")
    }
}

private fun operationCount(d:app.cash.sqldelight.db.SqlDriver)=d.executeQuery(null,"SELECT count(*) FROM ceiling_operations",{c ->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!)},0).value
