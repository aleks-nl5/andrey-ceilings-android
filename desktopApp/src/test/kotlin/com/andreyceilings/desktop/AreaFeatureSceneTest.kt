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

class AreaFeatureSceneTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun productionControlsMouseSelectionAndAtomicSaveInBothThemes():Unit=runBlocking {
        for(dark in listOf(false,true)) {
            val dir=java.nio.file.Files.createTempDirectory("area-scene-").toFile()
            val driver=if(System.getProperty("os.name").startsWith("Windows",true))SqlCipherWindowsDriverFactory.open(dir.resolve("scene.db"),ByteArray(32){88}) else JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            PriceCatalogSchema.create(driver)
            val db=PrototypeDatabase(driver)
            val project=ProjectRepository(db).createReturningId(null,"Areas",createdAt=1)
            val draft=MeasurementCreateDraft(project,MeasurementPrecision.ONE_MILLIMETER,"Kitchen","",MeasurementOutlineDraft.Template("rectangle",mapOf("length" to "4","width" to "3")))
            val id=MeasurementCreateStore(db){2}.create(draft)
            val row=MeasurementRepository(db).find(id)!!
            val prices=PriceRepository(db)
            val item=prices.find(prices.createInGroup("area.scene","Область UI",PriceGroupRepository(db).all().first().uuid,1.0,6,"шт.",QuantityMode.MANUAL,PlacementKind.AREA))!!
            val element=MeasurementElementRepository(db).createCatalogSelection(row.uuid,item.uuid,ElementQuantity.Unknown,7, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
            val store=MeasurementEditStore(db){100};val model=MeasurementEditModel(store,store.load(project,id))
            var delivered=false
            val scene=ImageComposeScene(900,1400) {CeilingsTheme(dark) {MeasurementEditScreen(model,PaddingValues(0.dp),0.dp,{}, {delivered=true},null)}}
            var testFailure:Throwable?=null
            try {
                scene.click("Позначити перешкоду")
                scene.tapWorld(row,500,500);scene.tapWorld(row,1000,1000)
                scene.click("Завершити фігуру в чернетці")
                assertEquals(1,model.displayAreas().size)
                scene.click("Позначити отвір");scene.setField("Діаметр, м (парні мм)","0,2");scene.tapWorld(row,2500,1500);scene.click("Завершити фігуру в чернетці")
                scene.click("Розмістити область: ${element.label}");scene.tapWorld(row,1500,500);scene.tapWorld(row,2000,1000);scene.click("Завершити фігуру в чернетці")
                assertEquals(3,model.displayAreas().size)
                assertTrue(AreaFeatureRepository(db).all().isEmpty())
                scene.click("Скасувати дію");assertEquals(2,model.displayAreas().size);scene.click("Повторити")
                scene.click("На весь екран");scene.click("Вписати креслення")
                scene.click("Згорнути")
                scene.tapWorld(row,2500,1500)
                assertEquals(AreaRole.VOID_MARKER,model.displayAreas().first {it.uuid==model.state.value.area.selected}.role)
                scene.click("Властивості фігури")
                scene.setField("Виміряний діаметр", "")
                scene.setField("Виміряний діаметр", ".")
                assertEquals("0.", model.state.value.area.properties?.diameter)
                scene.setField("Виміряний діаметр", ".007")
                model.applyAreaProperties()
                assertNotNull(model.state.value.area.properties, "Odd millimetres cannot commit")
                scene.setField("Виміряний діаметр", ".008")
                scene.setField("Примітка фігури","Вентиляція");scene.click("Застосувати властивості")
                assertEquals(4L, (model.displayAreas().first { it.role == AreaRole.VOID_MARKER }.shape as AreaShape.Circle).radiusMm)
                scene.click("Видалити фігуру");scene.click("Видалити");assertEquals(2,model.displayAreas().size)
                scene.click("Скасувати дію");assertEquals(3,model.displayAreas().size)
                val ids=model.displayAreas().map {it.uuid}.toSet()
                scene.click("Зберегти")
                withTimeout(15000){while(!delivered && model.state.value.error==null){scene.frame();delay(20)}}
                assertTrue(delivered,model.state.value.error)
                assertEquals(ids,store.load(project,id).placements.areas.map {it.uuid}.toSet())
            } catch(t:Throwable) {testFailure=t;throw t}
            finally {
                try {
                    store.closeAndAwaitPointReads()
                    repeat(6){scene.frame();delay(20)}
                    scene.close()
                } catch(close:Throwable) {
                    if(testFailure!=null)testFailure.addSuppressed(close) else throw close
                } finally {driver.close();dir.deleteRecursively()}
            }
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
        repeat(15) {
            frame()
            val b=canvas().boundsInRoot
            val delta=if(b.bottom>1300)b.bottom-1200 else if(b.top<100)b.top-200 else 0f
            if(delta==0f)return@repeat
            nodes().firstOrNull {it.config.getOrNull(SemanticsActions.ScrollBy)!=null}?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,delta)
            repeat(5){frame();Thread.sleep(10)}
        }
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
