package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
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

class OverlayDragSceneTest {
    private var activeTouchSlop = 0f
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun realMouseDragDetailsAndEditorNormalFullscreenKeepOneAtomicDraft():Unit=runBlocking {
        for(mode in listOf("details","editor","fullscreen","phone")) {
            val dir=java.nio.file.Files.createTempDirectory("overlay-drag-").toFile()
            val driver=if(System.getProperty("os.name").startsWith("Windows",true))SqlCipherWindowsDriverFactory.open(dir.resolve("scene.db"),ByteArray(32){92}) else JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            PriceCatalogSchema.create(driver);val db=PrototypeDatabase(driver);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val doc=store.load(f.project,f.id);val model=MeasurementEditModel(store,doc)
            val row=MeasurementRepository(db).find(f.id)!!
            val before=OverlayDragFixture.snapshot(driver)
            val scene=ImageComposeScene(if(mode=="phone")420 else 900,if(mode=="phone")900 else 1600) {
                val platformConfiguration = LocalViewConfiguration.current
                // A compact canvas can project a real world move below the platform's
                // gesture threshold. Exercise that case on every CI host explicitly.
                val testedConfiguration = remember(platformConfiguration, mode) {
                    if (mode == "editor") object : ViewConfiguration by platformConfiguration {
                        override val touchSlop = maxOf(platformConfiguration.touchSlop, 64f)
                    } else platformConfiguration
                }
                activeTouchSlop = testedConfiguration.touchSlop
                CompositionLocalProvider(LocalViewConfiguration provides testedConfiguration) { CeilingsTheme(mode!="editor") {
                val s by model.state.collectAsState()
                if(mode=="details")MeasurementDetailsScreen(MeasurementDetailsState.Loaded(MeasurementDetailsData(row.id,row.projectId,row.name,"Drag","",row.shape,row.areaSquareMeters,row.perimeterMeters,row.notes,
                    measurementGeometryForViewing(row.shape,row.drawingJson,row.lengthMeters,row.widthMeters),row.uuid,row.updatedAt)),PaddingValues(0.dp),0.dp,{},drawingEditor=model,
                    areaFeatures=model.displayAreas(),selectedAreaUuid=s.area.selected,onSelectArea=model::selectArea,
                    linePlacements=model.displayLines(),selectedLineUuid=s.selectedLineUuid,onSelectLine=model::selectLine,
                    pointState=PointPlacementState(points=doc.placements.points,available=true))
                else MeasurementEditScreen(model,PaddingValues(0.dp),0.dp,{}, {},null)
            }}}
            try {
                scene.awaitText("Вписати креслення")
                if(mode=="fullscreen")scene.click("На весь екран")
                // Selected polygon owns the drag even inside an unrelated LINE's halo.
                scene.tapWorld(row,1000,1500);assertEquals(f.polygon,model.state.value.area.selected,"$mode selection; bounds=${scene.canvas().boundsInRoot}; size=${scene.canvas().size}; root=${scene.semanticsOwners.first().rootSemanticsNode.size}");scene.dragWorld(row,1000,1050,1300,1250)
                var polygon=model.displayAreas().first {it.uuid==f.polygon}
                assertEquals(PointPositionMm(800,1200),polygon.shape.positions.first(),"$mode bounds=${scene.canvas().boundsInRoot}; pos=${scene.canvas().positionInRoot}; size=${scene.canvas().size}; error=${model.state.value.error}")
                assertEquals(doc.placements.areas.first {it.uuid==f.polygon}.twiceAreaMm2,polygon.twiceAreaMm2)
                assertTrue(polygon.mountings.all {it.anchor==null});assertEquals(PointPrecision.APPROXIMATE,polygon.precision)
                assertEquals(before,OverlayDragFixture.snapshot(driver))
                scene.click("Скасувати дію");assertEquals(doc.placements.areas.first {it.uuid==f.polygon},model.displayAreas().first {it.uuid==f.polygon})
                scene.click("Повторити");assertEquals(polygon,model.displayAreas().first {it.uuid==f.polygon})
                scene.click("Сегмент 1: 1 м");assertEquals(f.line,model.state.value.selectedLineUuid,"$mode must select LINE before drag");scene.dragWorld(row,1000,950,1300,750)
                val line=model.displayLines().single();assertEquals(PointPositionMm(800,750),line.start);assertEquals(1000,line.lengthMm)
                assertNull(line.startMounting.anchor);assertNull(line.endMounting.anchor)
                scene.click("Кінець");assertEquals(2,model.state.value.lineMoveTarget);scene.dragWorld(row,1800,750,2400,750)
                assertEquals(1600,model.displayLines().single().lengthMm,"$mode: ${model.state.value.lineDrag} ${model.state.value.selectedLineUuid} ${model.state.value.error} ${model.displayLines().single()}")
                scene.tapWorld(row,2800,1500);scene.dragWorld(row,2800,1500,3100,1800)
                val circle=model.displayAreas().first {it.uuid==f.circle};assertEquals(AreaShape.Circle(PointPositionMm(3100,1800),200),circle.shape)
                assertEquals(doc.placements.areas.first {it.uuid==f.circle}.twiceAreaMm2,circle.twiceAreaMm2)
                // Invalid full transform leaves the previous valid draft and every DB row intact.
                scene.dragWorld(row,3100,1800,4500,1800)
                assertEquals(circle,model.displayAreas().first {it.uuid==f.circle});assertNotNull(model.state.value.error)
                assertEquals(before,OverlayDragFixture.snapshot(driver))
                val expectedLines=model.displayLines();val expectedAreas=model.displayAreas()
                scene.click(if(mode=="details")"Зберегти креслення" else "Зберегти")
                withTimeout(15000){while(model.state.value.saving || model.state.value.dirty){scene.frame();delay(20)}}
                assertNull(model.state.value.error)
                val reopened=store.load(f.project,f.id)
                assertEquals(expectedLines.map {it.start to it.end},reopened.placements.lines.map {it.start to it.end})
                assertEquals(expectedAreas.map {it.shape},reopened.placements.areas.map {it.shape})
                assertEquals(doc.placements.points,reopened.placements.points)
                assertEquals(doc.draft,reopened.draft)
            } finally {store.closeAndAwaitPointReads();repeat(5){scene.frame();delay(10)};scene.close();driver.close();dir.deleteRecursively()}
        }
    }
    private fun ImageComposeScene.dragWorld(row:Room_measurements,x:Long,y:Long,toX:Long,toY:Long) {
        val b=stableBounds()
        val contour=measurementDrawingContour(measurementGeometryForViewing(row.shape,row.drawingJson,row.lengthMeters,row.widthMeters))!!
        val projection=measurementDrawingProjection(contour,b.width,b.height)!!
        fun project(px:Long,py:Long):Offset {val p=projection.project(DrawingPoint(px.toDouble(),py.toDouble()));return Offset(b.left+p.x.toFloat(),b.top+p.y.toFloat())}
        val start=project(x,y);val end=project(toX,toY)
        val distance = (end - start).getDistance()
        check(distance > 0f)
        // Begin a genuine drag even when its precise final adjustment is sub-slop.
        // The excursion stays in the same gesture; release at the exact requested world point.
        val activation = if (distance <= activeTouchSlop)
            start + (end - start) * ((activeTouchSlop + 2f) / distance) else end
        sendPointerEvent(PointerEventType.Press,start,type=PointerType.Mouse);frame()
        sendPointerEvent(PointerEventType.Move,activation,type=PointerType.Mouse);frame()
        if (activation != end) { sendPointerEvent(PointerEventType.Move,end,type=PointerType.Mouse);frame() }
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
        repeat(240) { frame(); nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree() == label }?.let {
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
