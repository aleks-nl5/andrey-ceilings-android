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

class LinePlacementSceneTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun realMouseTouchLineCreationSelectionDragPinchAndAtomicSaveBothThemes():Unit=runBlocking {
        for(dark in listOf(false,true)) {
            val directory=java.nio.file.Files.createTempDirectory("line-scene-").toFile()
            val driver=if(System.getProperty("os.name").startsWith("Windows",true))SqlCipherWindowsDriverFactory.open(directory.resolve("scene.db"),ByteArray(32){87})
                else JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            PriceCatalogSchema.create(driver)
            val db=PrototypeDatabase(driver)
            val project=ProjectRepository(db).createReturningId(null,"Lines",createdAt=1)
            val draft=MeasurementCreateDraft(project,MeasurementPrecision.ONE_MILLIMETER,"Kitchen","",MeasurementOutlineDraft.Template("rectangle",mapOf("length" to "4","width" to "3")))
            val id=MeasurementCreateStore(db){2}.create(draft)
            val row=MeasurementRepository(db).find(id)!!
            val prices=PriceRepository(db)
            val price=prices.find(prices.createInGroup("line.scene","Лінія",PriceGroupRepository(db).all().first().uuid,1.0,3,"м.п.",com.andreyceilings.shared.prices.QuantityMode.ENTERED_LENGTH,com.andreyceilings.shared.prices.PlacementKind.LINE))!!
            val element=MeasurementElementRepository(db).createCatalogSelection(row.uuid,price.uuid,ElementQuantity.Exact(4500),4)
            val store=MeasurementEditStore(db){100}
            val model=MeasurementEditModel(store,store.load(project,id))
            var fullscreen by mutableStateOf(false)
            var saved=false
            val scene=ImageComposeScene(700,1000) {
                CeilingsTheme(dark) {
                    val state by model.state.collectAsState()
                    val scope=rememberCoroutineScope()
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.TextButton(onClick={fullscreen=!fullscreen}) {androidx.compose.material3.Text("Режим")}
                        androidx.compose.material3.TextButton(onClick={model.beginLine(element.uuid)}) {androidx.compose.material3.Text("Додати лінію")}
                        androidx.compose.material3.TextButton(onClick=model::acceptLineCreation) {androidx.compose.material3.Text("У чернетку")}
                        androidx.compose.material3.TextButton(onClick=model::undo) {androidx.compose.material3.Text("Undo")}
                        androidx.compose.material3.TextButton(onClick=model::redo) {androidx.compose.material3.Text("Redo")}
                        androidx.compose.material3.TextButton(onClick={scope.launch {model.save();saved=model.state.value.saved}}) {androidx.compose.material3.Text("Save")}
                        MeasurementDrawing(MeasurementGeometryView.Captured(validateMeasurementCreate(state.draft).geometry!!),
                            androidx.compose.ui.Modifier,interactive=true,fullscreen=fullscreen,
                            linePlacements=model.displayLines()+listOfNotNull(state.linePreview),selectedLineUuid=state.selectedLineUuid,
                            linePlacementMode=state.lineElementUuid!=null,onLineTap=model::lineTap,onSelectLine=model::selectLine,
                            lineEditingEnabled=true,lineDrag=state.lineDrag,onBeginLineDrag=model::beginLineDrag,onPreviewLineDrag=model::previewLineDrag,
                            onFinishLineDrag=model::finishLineDrag,onCancelLineDrag=model::cancelLineDrag)
                    }
                }
            }
            try {
                scene.click("Додати лінію")
                // Pan and pinch in creation mode cannot create either endpoint.
                val centre=scene.world(row,2000,1500)
                scene.sendPointerEvent(PointerEventType.Press,centre,type=PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move,centre+Offset(60f,40f),type=PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release,centre+Offset(60f,40f),type=PointerType.Touch);scene.frame()
                assertNull(model.state.value.lineStart)
                scene.click("Вписати креслення")
                scene.tapWorld(row,500,600);scene.tapWorld(row,3000,600)
                val uuid=model.state.value.linePreview!!.uuid
                scene.click("У чернетку")
                assertEquals(uuid,model.displayLines().single().uuid)
                assertTrue(LinePlacementRepository(db).forMeasurement(row.uuid).isEmpty())
                scene.click("Undo");assertTrue(model.displayLines().isEmpty());scene.click("Redo")
                scene.tapWorld(row,1800,600);assertEquals(uuid,model.state.value.selectedLineUuid)
                scene.click("Режим")
                val start=scene.world(row,1800,600);val end=start+Offset(20f,40f)
                val type=if(dark)PointerType.Mouse else PointerType.Touch
                scene.sendPointerEvent(PointerEventType.Press,start,type=type)
                scene.sendPointerEvent(PointerEventType.Move,end,type=type)
                scene.sendPointerEvent(PointerEventType.Release,end,type=type);scene.frame()
                assertNotEquals(PointPositionMm(500,600),model.displayLines().single().start)
                assertTrue(LinePlacementRepository(db).forMeasurement(row.uuid).isEmpty())
                scene.click("Undo")
                val point=scene.world(row,1800,600)
                fun pointer(id:Long,p:Offset,pressed:Boolean)=ComposeScenePointer(PointerId(id),p,pressed,PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Press,pointers=listOf(pointer(0,point,true)))
                scene.sendPointerEvent(PointerEventType.Press,pointers=listOf(pointer(0,point,true),pointer(1,point+Offset(80f,0f),true)))
                scene.sendPointerEvent(PointerEventType.Move,pointers=listOf(pointer(0,point-Offset(20f,0f),true),pointer(1,point+Offset(100f,0f),true)))
                scene.sendPointerEvent(PointerEventType.Release,pointers=listOf(pointer(0,point-Offset(20f,0f),false),pointer(1,point+Offset(100f,0f),false)));scene.frame()
                assertEquals(PointPositionMm(500,600),model.displayLines().single().start)
                scene.click("Save")
                withTimeout(10000){while(!saved){scene.frame();delay(20)}}
                val line=LinePlacementRepository(db).forMeasurement(row.uuid).single()
                assertEquals(uuid,line.uuid);assertEquals(2500,line.lengthMm)
                assertEquals(ElementQuantity.Exact(4500),MeasurementElementRepository(db).find(element.uuid)!!.quantity)
            } finally {scene.close();store.closePointReads();driver.close();directory.deleteRecursively()}
        }
    }
    private fun ImageComposeScene.world(row: Room_measurements, x: Long, y: Long): Offset {
        val b = stableBounds()
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
        repeat(150) { frame(); val b = canvas().boundsInRoot; stable = if (b == previous) stable + 1 else 0
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
        repeat(240) { frame(); nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree() == label }?.let {
            assertNull(it.config.getOrNull(SemanticsProperties.Disabled), label)
            assertTrue(it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true); frame(); return }; Thread.sleep(25) }
        fail("Missing action '$label': ${text().take(2000)}")
    }
    private fun ImageComposeScene.setField(label: String, value: String) {
        repeat(240) { frame(); nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains(label) }?.let {
            assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true); frame(); return }; Thread.sleep(25) }
        fail("Missing field '$label': ${text().take(2000)}")
    }
}
