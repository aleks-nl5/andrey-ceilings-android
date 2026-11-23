package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.toSize
import com.andreyceilings.shared.prices.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlin.test.*

class AreaFeatureSceneTest {
    private val sceneDispatcher = DesktopSceneDispatcher()

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
            var exited=false
            val scene=ImageComposeScene(900,1400,coroutineContext=sceneDispatcher) {CeilingsTheme(dark) {MeasurementEditScreen(model,PaddingValues(0.dp),0.dp,{exited=true},null)}}
            var testFailure:Throwable?=null
            try {
                withTimeout(15000) {
                    while (scene.nodes().none { it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled) == true }) {
                        scene.frame(); delay(20)
                    }
                }
                scene.click("Елементи")
                scene.click("Додати елемент стелі")
                scene.click("Позначка на кресленні")
                scene.click("Перешкода")
                scene.tapWorld(500,500)
                assertEquals(1,model.state.value.area.vertices.size,"First area corner must be delivered in $dark theme")
                scene.tapWorld(1000,1000)
                assertEquals(2,model.state.value.area.vertices.size,"Second area corner must be delivered in $dark theme")
                scene.click("Завершити фігуру в чернетці")
                assertEquals(1,model.displayAreas().size)
                val firstNoteId = model.displayAreas().single().uuid
                assertTrue(scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.TestTag) ==
                        "p5b-standalone-area-$firstNoteId"
                }, "An unlinked obstacle must appear in the Elements list")
                assertFalse(scene.text().contains("Ширина, м"),
                    "A selected obstacle must not automatically open geometry fields")
                scene.click("Відкрити параметри розміщення",
                    tag = "p5b-open-standalone-area-$firstNoteId")
                scene.setField("Ширина, м", "0,7")
                scene.setField("Висота, м", "0,6")
                scene.click("Оновити на кресленні (у чернетці)")
                assertEquals(AreaShape.rectangle(PointPositionMm(500, 500), PointPositionMm(1200, 1100)),
                    model.displayAreas().single().shape)
                scene.click("Додати елемент стелі")
                scene.click("Позначка на кресленні")
                scene.click("Позначка отвору")
                scene.setField("Діаметр, м (парні мм)", "0,2")
                scene.tapWorld(2500, 1500)
                assertEquals(1, model.state.value.area.vertices.size, "Circle center must be delivered in $dark theme")
                scene.click("Завершити фігуру в чернетці")
                scene.click("Додати елемент стелі")
                scene.click(element.label, tag = "p5b-linked-${element.uuid}")
                scene.click("Розмістити ще")
                scene.tapWorld(1500, 500)
                scene.tapWorld(2000, 1000)
                assertEquals(2, model.state.value.area.vertices.size, "Catalog area corners must be delivered in $dark theme")
                scene.click("Завершити фігуру в чернетці")
                assertEquals(3,model.displayAreas().size)
                assertEquals(element.uuid, model.displayAreas().single { it.role == AreaRole.GENERAL_AREA }.elementUuid)
                assertTrue(AreaFeatureRepository(db).all().isEmpty())
                scene.click("Скасувати дію");assertEquals(2,model.displayAreas().size);scene.click("Повторити")
                scene.click("На весь екран");scene.click("Вписати креслення")
                scene.click("Згорнути")
                scene.tapWorld(2500,1500)
                val noteId = model.displayAreas().first {it.uuid==model.state.value.area.selected}.uuid
                assertEquals(AreaRole.VOID_MARKER,model.displayAreas().first {it.uuid==noteId}.role)
                assertTrue(scene.nodes().any {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "p5b-standalone-area-$noteId"
                }, "A single tap must reveal/highlight the annotation row")
                scene.click("Відкрити параметри розміщення", tag = "p5b-open-standalone-area-$noteId")
                scene.setField("Діаметр, м (парні мм)", "")
                scene.setField("Діаметр, м (парні мм)", ".")
                assertEquals("0.", model.state.value.area.properties?.diameter)
                scene.setField("Діаметр, м (парні мм)", ".007")
                scene.click("Оновити на кресленні (у чернетці)")
                assertNotNull(model.state.value.area.properties, "Odd millimetres cannot commit")
                scene.setField("Діаметр, м (парні мм)", ".008")
                scene.setField("Примітка фігури","Вентиляція");scene.click("Оновити на кресленні (у чернетці)")
                assertEquals(4L, (model.displayAreas().first { it.role == AreaRole.VOID_MARKER }.shape as AreaShape.Circle).radiusMm)
                scene.click("Відкрити параметри розміщення", tag = "p5b-open-standalone-area-$noteId")
                scene.click("Видалити розміщення");scene.click("Видалити");assertEquals(2,model.displayAreas().size)
                scene.click("Скасувати дію");assertEquals(3,model.displayAreas().size)
                val ids=model.displayAreas().map {it.uuid}.toSet()
                scene.click("Зберегти замір")
                withTimeout(15000){while(model.state.value.dirty && model.state.value.error==null){scene.frame();delay(20)}}
                assertFalse(model.state.value.dirty, model.state.value.error)
                assertFalse(exited)
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
    private fun ImageComposeScene.world(x: Long, y: Long): Offset {
        val b = stableBounds()
        val p = canvas().config[MeasurementDrawingSemantics.Projection].project(DrawingPoint(x.toDouble(), y.toDouble()))
        return Offset(b.left + p.x.toFloat(), b.top + p.y.toFloat())
    }
    private fun ImageComposeScene.tapWorld(x: Long, y: Long) {
        val p = world(x, y)
        assertTrue(p.x in 0f..900f && p.y in 0f..1400f, "Tap must be inside the viewport: $p")
        sendPointerEvent(PointerEventType.Press, p, type = PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, p, type = PointerType.Touch)
        frame()
    }
    private fun ImageComposeScene.stableBounds(): Rect {
        fun bounds() = canvas().let { Rect(it.positionInRoot, it.size.toSize()) }
        var previous: Rect? = null
        var stable = 0
        repeat(150) {
            frame()
            val bounds = bounds()
            stable = if (bounds == previous) stable + 1 else 0
            if (stable >= 3) {
                assertTrue(bounds.top >= 0f && bounds.bottom <= 1400f, "Canvas must fit the workspace: $bounds")
                return bounds
            }
            previous = bounds
            Thread.sleep(20)
        }
        fail("Canvas did not settle inside the viewport")
    }
    private fun ImageComposeScene.canvas() = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Контур стелі") } }
    private fun ImageComposeScene.frame() = sceneDispatcher.render(this)
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.click(label: String, tag: String? = null) {
        repeat(240) { frame(); nodes().firstOrNull {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                (if (tag != null) it.config.getOrNull(SemanticsProperties.TestTag) == tag
                else it.textTree() == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        }?.let {
            assertTrue(it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            frame()
            return
        }; Thread.sleep(25) }
        fail("Missing enabled action '$label': ${text().take(2000)}")
    }
    private fun ImageComposeScene.setField(label: String, value: String) {
        repeat(240) { frame(); nodes().firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains(label) }?.let {
            assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true); frame(); return }; Thread.sleep(25) }
        fail("Missing field '$label': ${text().take(2000)}")
    }
}
