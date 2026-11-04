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

class PointEditingSceneTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun realSelectedDragMountingCoEditAndDurableReviewInBothThemes(): Unit = runBlocking {
        for (dark in listOf(false, true)) {
            val directory = java.nio.file.Files.createTempDirectory("point-edit-scene-").toFile()
            val driver = if (System.getProperty("os.name").startsWith("Windows", true))
                SqlCipherWindowsDriverFactory.open(directory.resolve("scene.db"), ByteArray(32) { 83 })
            else JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Scene", createdAt = 1)
            val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Кухня", "",
                MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5", "width" to "4")))
            val id = MeasurementCreateStore(db) { 2 }.create(draft)
            val original = MeasurementRepository(db).find(id)!!
            val elements = MeasurementElementRepository(db); val points = PointPlacementRepository(db)
            val item = PriceRepository(db).all().single { it.code == "light.spot" }
            val element = elements.createCatalogSelection(original.uuid, item.uuid, ElementQuantity.Exact(6000), 3)
            val p = points.addPoint(element.version, original.updatedAt, 1000, 1000, 4)
            val second = points.addPoint(elements.find(element.uuid)!!.version, original.updatedAt, 3000, 1000, 5)
            val calls = AtomicInteger()
            val details = MeasurementDetailsStore(db, { calls.incrementAndGet(); 10L }, Dispatchers.Unconfined)
            val model = details.elements.model(ElementRoute(project, id, original.uuid))
            val edit = MeasurementEditStore(db) { 30 }
            val guard = MeasurementCreateNavigationGuard()
            var nav = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).openMeasurement(project, id)
            var editing by mutableStateOf(false)
            withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
            withTimeout(10_000) { model.points.first { it.available && it.points.size == 2 } }
            val scene = ImageComposeScene(700, 1800) {
                CeilingsTheme(dark) {
                    if (editing) MeasurementEditScreen(project, id, edit, PaddingValues(), 0.dp, {
                        nav = guard.transition(nav, nav.back()); editing = nav.measurementEdit != null
                    }, { guard.deliverEditResult(nav, project, id) { nav = it; editing = false } }, guard)
                    else MeasurementDetailsScreen(project, id, details, PaddingValues(), 0.dp, {}, onEdit = {
                        nav = guard.transition(nav, nav.editMeasurement(project, id)); editing = nav.measurementEdit != null
                    }, navigationGuard = guard)
                }
            }
            suspend fun awaitPoint(x: Long, precision: PointPrecision? = null) {
                withTimeout(15_000) { model.points.first { state -> !state.saving && state.points.any {
                    it.uuid == p.uuid && it.xMm == x && (precision == null || it.precision == precision) } } }
                scene.frame()
            }
            var testFailure: Throwable? = null
            try {
                scene.awaitText("Розміщено: 2 з 6")
                scene.tapWorld(original, 1000, 1000)
                assertEquals(p.uuid, model.points.value.selectedPointUuid)
                val before = points.find(p.uuid)
                val start = scene.world(original, 1000, 1000); val end = scene.world(original, 1800, 1600)
                val inputType = if (dark) PointerType.Mouse else PointerType.Touch
                scene.sendPointerEvent(PointerEventType.Press, start, type = inputType)
                scene.sendPointerEvent(PointerEventType.Move, end, type = inputType)
                scene.frame()
                assertEquals(before, points.find(p.uuid)); assertEquals(0, calls.get())
                assertNotNull(model.points.value.drag, "Selected marker owns drag")
                scene.sendPointerEvent(PointerEventType.Release, end, type = inputType)
                awaitPoint(1800, PointPrecision.APPROXIMATE)
                assertEquals(1, calls.get(), "One release performs one save")
                assertEquals(second, points.find(second.uuid)); assertEquals(original, MeasurementRepository(db).find(id))
                assertEquals(2, points.forMeasurement(original.uuid).size)
                val saved = points.find(p.uuid)!!
                val pinch = scene.world(original, saved.xMm, saved.yMm)
                fun pointer(id: Long, offset: Offset, pressed: Boolean) = ComposeScenePointer(PointerId(id), offset, pressed, PointerType.Touch)
                val callsBeforePinch = calls.get()
                scene.sendPointerEvent(PointerEventType.Press, pointers = listOf(pointer(0, pinch, true)))
                scene.sendPointerEvent(PointerEventType.Press, pointers = listOf(pointer(0, pinch, true), pointer(1, pinch + Offset(80f, 0f), true)))
                scene.sendPointerEvent(PointerEventType.Move, pointers = listOf(pointer(0, pinch - Offset(20f, 0f), true), pointer(1, pinch + Offset(100f, 0f), true)))
                scene.sendPointerEvent(PointerEventType.Release, pointers = listOf(pointer(0, pinch - Offset(20f, 0f), false), pointer(1, pinch + Offset(100f, 0f), false)))
                scene.frame(); assertNull(model.points.value.drag); assertEquals(callsBeforePinch, calls.get())
                assertEquals(saved, points.find(p.uuid)); scene.click("Вписати креслення")
                val canceledStart = scene.world(original, saved.xMm, saved.yMm)
                val canceledEnd = scene.world(original, saved.xMm + 1000, saved.yMm + 500)
                scene.sendPointerEvent(PointerEventType.Press, canceledStart, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, canceledEnd, type = PointerType.Touch)
                scene.frame(); assertNotNull(model.points.value.drag)
                assertEquals(nav, guard.transition(nav, nav.back()))
                scene.sendPointerEvent(PointerEventType.Release, canceledEnd, type = PointerType.Touch)
                scene.frame(); assertEquals(saved, points.find(p.uuid)); assertEquals(callsBeforePinch, calls.get())
                scene.tapWorld(original, saved.xMm, saved.yMm)
                val outside = scene.world(original, -500, saved.yMm)
                val from = scene.world(original, saved.xMm, saved.yMm)
                scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, outside, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, outside, type = PointerType.Touch)
                scene.awaitText("Торкніться всередині контуру")
                assertEquals(saved, points.find(p.uuid))
                val empty = scene.world(original, 4000, 3000)
                scene.sendPointerEvent(PointerEventType.Press, empty, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, empty + Offset(50f, 30f), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, empty + Offset(50f, 30f), type = PointerType.Touch)
                scene.frame(); assertEquals(saved, points.find(p.uuid)); scene.click("Вписати креслення")
                scene.click("Задати розміри від стін")
                scene.awaitText("Позначки: 2")
                scene.tapWorld(original,saved.xMm,saved.yMm)
                scene.click("Задати розміри від стін")
                scene.setField("Від першої стіни, м","0,")
                assertEquals(saved,points.find(p.uuid))
                scene.setField("Від першої стіни, м","0,6");scene.setField("Від другої стіни, м","1.5")
                scene.click("Застосувати розміри")
                assertEquals(saved,points.find(p.uuid),"Mounting fields stage only, no separate point Save")
                scene.click("Скасувати дію");scene.click("Повторити")
                scene.click("На весь екран");scene.awaitText("Позначки: 2")
                assertTrue(scene.canvas().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("позначок: 2") })
                val fsStart=scene.world(original,1500,600);val fsEnd=scene.world(original,1700,900)
                scene.sendPointerEvent(PointerEventType.Press,fsStart,type=inputType)
                scene.sendPointerEvent(PointerEventType.Move,fsEnd,type=inputType)
                scene.sendPointerEvent(PointerEventType.Release,fsEnd,type=inputType)
                scene.frame();assertEquals(saved,points.find(p.uuid))
                scene.awaitText("Виміряних прив'язок немає")
                scene.click("Скасувати дію");scene.click("Згорнути")
                scene.click("Усі розміри");scene.setField("Сторона AB", "")
                scene.awaitText("Невалідна чернетка: показано збережений контур")
                assertTrue(scene.canvas().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("позначок: 2") })
                scene.setField("Сторона AB", "1");scene.setField("Сторона BC", "1")
                scene.awaitText("Попередній перерахунок")
                assertTrue(scene.canvas().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("позначок: 2") })
                scene.setField("Сторона BC", "4");scene.setField("Сторона AB", "6")
                assertEquals(original,MeasurementRepository(db).find(id));assertEquals(saved,points.find(p.uuid))
                scene.click("Зберегти");scene.awaitText("Контур стелі змінено. Збережені монтажні відстані")
                assertEquals(PointPositionMm(1500,600),points.find(p.uuid)!!.let{PointPositionMm(it.xMm,it.yMm)})
                assertTrue(points.forMeasurement(original.uuid).all {it.mounting.needsReview})
                val anchor=assertNotNull(points.find(p.uuid)!!.mounting.anchor)
                assertEquals(600L,anchor.firstMm);assertEquals(1500L,anchor.secondMm)
                assertNull(points.find(second.uuid)!!.mounting.anchor)
                scene.click("Пізніше")
                repeat(200) {if(editing){scene.frame();delay(25)}}
                assertFalse(editing);scene.awaitText("Перевірити (2)")
                assertTrue(points.forMeasurement(original.uuid).all {it.mounting.deferredAt!=null})
                scene.click("Перевірити (2)");scene.awaitText("Позначки: 2")
                scene.click("Перевірити (2)");scene.click("Розміщення перевірено")
                scene.awaitText("Перевірити (1)");scene.click("Розміщення перевірено")
                repeat(150) {scene.frame();if(points.forMeasurement(original.uuid).any{it.mounting.needsReview})delay(25)}
                assertTrue(points.forMeasurement(original.uuid).none {it.mounting.needsReview})
                scene.click("Зберегти")
                repeat(150) {if(editing){scene.frame();delay(25)}}
                assertFalse(editing)
                val beforeName=points.forMeasurement(original.uuid)
                val geometryBeforeName=MeasurementRepository(db).find(id)!!.drawingJson
                scene.click("Редагувати");scene.awaitText("Позначки: 2")
                scene.setField("Назва заміру","Нова назва");scene.click("Зберегти")
                repeat(150) {if(editing){scene.frame();delay(25)}}
                assertFalse(editing);assertEquals("Нова назва",MeasurementRepository(db).find(id)!!.name)
                assertEquals(geometryBeforeName,MeasurementRepository(db).find(id)!!.drawingJson)
                assertEquals(beforeName,points.forMeasurement(original.uuid))
                assertEquals(2000L,elements.find(element.uuid)!!.placedQuantityMilli)
            } catch (failure: Throwable) {
                testFailure = failure; throw failure
            } finally {
                try { scene.close() } catch (cleanup: Throwable) {
                    if (testFailure != null) testFailure.addSuppressed(cleanup) else throw cleanup
                } finally { edit.closePointReads(); details.elements.closeAndAwaitWrites(); driver.close(); directory.deleteRecursively() }
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
