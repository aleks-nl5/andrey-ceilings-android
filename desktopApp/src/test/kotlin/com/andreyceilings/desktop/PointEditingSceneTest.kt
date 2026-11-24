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
import com.andreyceilings.shared.livepricing.LivePricingReader
import com.andreyceilings.shared.livepricing.PricingReadResult
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
            else JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("scene.db").absolutePath}")
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Scene", createdAt = 1)
            val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Кухня", "",
                MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5", "width" to "4")))
            val id = MeasurementCreateStore(db) { 2 }.create(draft)
            val original = MeasurementRepository(db).find(id)!!
            val elements = MeasurementElementRepository(db); val points = PointPlacementRepository(db)
            val item = PriceRepository(db).all().single { it.code == "light.spot" }
            val element = elements.createCatalogSelection(original.uuid, item.uuid, ElementQuantity.Exact(6000), 3, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
            val p = points.addPoint(element.version, original.updatedAt, 1000, 1000, 4)
            val legacy = points.addPoint(elements.find(element.uuid)!!.version, original.updatedAt, 3000, 1000, 5)
            val second = points.movePoint(elements.find(element.uuid)!!.version,legacy,original.updatedAt,3000,1000,PointPrecision.EXACT,6).point
            val calls = AtomicInteger()
            val details = MeasurementDetailsStore(db, { calls.incrementAndGet(); 10L }, Dispatchers.Unconfined)
            val pricing = LivePricingReader(db)
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
                    }, guard)
                    else MeasurementDetailsScreen(project, id, details, PaddingValues(), 0.dp, {}, onEdit = {
                        nav = guard.transition(nav, nav.editMeasurement(project, id)); editing = nav.measurementEdit != null
                    }, navigationGuard = guard,editStore=edit)
                }
            }
            suspend fun awaitPoint(x: Long, precision: PointPrecision? = null) {
                withTimeout(15_000) { model.points.first { state -> !state.saving && state.points.any {
                    it.uuid == p.uuid && it.xMm == x && (precision == null || it.precision == precision) } } }
                scene.frame()
            }
            val pointIds = setOf(p.uuid, second.uuid)
            var testFailure: Throwable? = null
            try {
                scene.awaitText("Розміщено: 2 з 6")
                scene.tapWorld(second.xMm,second.yMm)
                scene.awaitText("Ця позначка: точне числове положення без виміряних стінних прив'язок")
                scene.tapWorld(1000, 1000)
                scene.awaitText("Ця позначка: приблизне положення")
                assertEquals(p.uuid, scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint))
                val before = points.find(p.uuid)
                val priorPricing = assertIs<PricingReadResult.Loaded>(pricing.read(project, original.uuid))
                val start = scene.world(1000, 1000); val end = scene.world(1800, 1600)
                val inputType = if (dark) PointerType.Mouse else PointerType.Touch
                scene.sendPointerEvent(PointerEventType.Press, start, type = inputType)
                scene.sendPointerEvent(PointerEventType.Move, end, type = inputType)
                scene.frame()
                assertEquals(before, points.find(p.uuid)); assertEquals(0, calls.get())
                assertNotNull(scene.canvas().config.getOrNull(MeasurementDrawingSemantics.PointDrag), "Selected marker owns shared drag")
                scene.sendPointerEvent(PointerEventType.Release, end, type = inputType)
                withTimeout(15000) {while(scene.canvas().config.getOrNull(MeasurementDrawingSemantics.Points).orEmpty().none {it.uuid==p.uuid && it.xMm==1800L}) {scene.frame();delay(20)}}
                assertEquals(before,points.find(p.uuid),"POINT release must stage, with no instant SQL writer")
                assertEquals(0,calls.get())
                scene.click("Зберегти креслення");awaitPoint(1800,PointPrecision.APPROXIMATE)
                val savedPricing = assertIs<PricingReadResult.Loaded>(pricing.read(project, original.uuid))
                assertNotEquals(priorPricing.source.signature, savedPricing.source.signature)
                assertEquals(priorPricing.buildPreview().potentialTotalCents, savedPricing.buildPreview().potentialTotalCents)
                assertEquals(0,calls.get(),"Only the common ceiling writer may save this drag")
                assertEquals(second, points.find(second.uuid)); assertEquals(original.drawingJson, MeasurementRepository(db).find(id)!!.drawingJson)
                assertEquals(2, points.forMeasurement(original.uuid).size)
                val saved = points.find(p.uuid)!!
                val afterPointSave=MeasurementRepository(db).find(id)
                val pinch = scene.world(saved.xMm, saved.yMm)
                fun pointer(id: Long, offset: Offset, pressed: Boolean) = ComposeScenePointer(PointerId(id), offset, pressed, PointerType.Touch)
                val callsBeforePinch = calls.get()
                scene.sendPointerEvent(PointerEventType.Press, pointers = listOf(pointer(0, pinch, true)))
                scene.sendPointerEvent(PointerEventType.Press, pointers = listOf(pointer(0, pinch, true), pointer(1, pinch + Offset(80f, 0f), true)))
                scene.sendPointerEvent(PointerEventType.Move, pointers = listOf(pointer(0, pinch - Offset(20f, 0f), true), pointer(1, pinch + Offset(100f, 0f), true)))
                scene.sendPointerEvent(PointerEventType.Release, pointers = listOf(pointer(0, pinch - Offset(20f, 0f), false), pointer(1, pinch + Offset(100f, 0f), false)))
                scene.frame(); assertNull(scene.canvas().config.getOrNull(MeasurementDrawingSemantics.PointDrag)); assertEquals(callsBeforePinch, calls.get())
                assertEquals(saved, points.find(p.uuid)); scene.click("Вписати креслення")
                val canceledStart = scene.world(saved.xMm, saved.yMm)
                val canceledEnd = scene.world(saved.xMm + 1000, saved.yMm + 500)
                scene.sendPointerEvent(PointerEventType.Press, canceledStart, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, canceledEnd, type = PointerType.Touch)
                scene.frame(); assertNotNull(scene.canvas().config.getOrNull(MeasurementDrawingSemantics.PointDrag))
                assertEquals(nav, guard.transition(nav, nav.back()))
                scene.sendPointerEvent(PointerEventType.Release, canceledEnd, type = PointerType.Touch)
                scene.frame(); assertEquals(saved, points.find(p.uuid)); assertEquals(callsBeforePinch, calls.get())
                scene.tapWorld(saved.xMm, saved.yMm)
                val outside = scene.world(-500, saved.yMm)
                val from = scene.world(saved.xMm, saved.yMm)
                scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, outside, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, outside, type = PointerType.Touch)
                scene.awaitText("Позначка має бути строго всередині контуру.")
                assertEquals(saved, points.find(p.uuid))
                val empty = scene.world(4000, 3000)
                scene.sendPointerEvent(PointerEventType.Press, empty, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, empty + Offset(50f, 30f), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, empty + Offset(50f, 30f), type = PointerType.Touch)
                scene.frame(); assertEquals(saved, points.find(p.uuid)); scene.click("Вписати креслення")
                scene.click("Задати розміри від стін")
                scene.awaitDrawingPoints(pointIds)
                scene.tapWorld(saved.xMm,saved.yMm)
                // A single tap selects the work row; its explicit gear opens mounting fields.
                scene.click("Параметри розміщення", tag = "p5b-open-geometry-${element.uuid}")
                scene.setField("Відстань, м", "0,", tag = "p5b-first-wall-distance")
                assertEquals(saved,points.find(p.uuid))
                scene.setField("Відстань, м", "0,6", tag = "p5b-first-wall-distance")
                scene.setField("Відстань, м", "1.5", tag = "p5b-second-wall-distance")
                scene.click("Застосувати розміри")
                assertEquals(saved,points.find(p.uuid),"Mounting fields stage only, no separate point Save")
                scene.click("Скасувати дію");scene.click("Повторити")
                scene.click("На весь екран")
                scene.awaitDrawingPoints(pointIds)
                scene.click("Елементи")
                scene.awaitText(element.label)
                scene.click("Закрити панель")
                val fsStart=scene.world(1500,600);val fsEnd=scene.world(1700,900)
                scene.sendPointerEvent(PointerEventType.Press,fsStart,type=inputType)
                scene.sendPointerEvent(PointerEventType.Move,fsEnd,type=inputType)
                scene.sendPointerEvent(PointerEventType.Release,fsEnd,type=inputType)
                scene.frame();assertEquals(saved,points.find(p.uuid))
                scene.click("Елементи")
                val dragged = scene.canvas().config[MeasurementDrawingSemantics.Points].single { it.uuid == p.uuid }
                assertEquals(PointPrecision.APPROXIMATE, dragged.precision)
                assertNull(dragged.mounting.anchor)
                scene.click("Скасувати дію");scene.click("Згорнути")
                scene.click("Контур")
                scene.setField("AB, м", "")
                scene.click("Показати повне повідомлення")
                scene.awaitText("Невалідна чернетка: показано збережений контур")
                scene.click("Закрити")
                assertTrue(scene.canvas().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("позначок: 2") })
                scene.setField("AB, м", "1");scene.setField("BC, м", "1")
                scene.click("Елементи")
                scene.awaitDrawingPoints(pointIds)
                scene.click("Контур")
                scene.setField("BC, м", "4");scene.setField("AB, м", "6")
                assertEquals(afterPointSave,MeasurementRepository(db).find(id));assertEquals(saved,points.find(p.uuid))
                scene.click("Зберегти замір");scene.awaitText("Контур стелі змінено. Збережені монтажні відстані")
                assertEquals(PointPositionMm(1500,600),points.find(p.uuid)!!.let{PointPositionMm(it.xMm,it.yMm)})
                assertTrue(points.forMeasurement(original.uuid).all {it.mounting.needsReview})
                val anchor=assertNotNull(points.find(p.uuid)!!.mounting.anchor)
                assertEquals(600L,anchor.firstMm);assertEquals(1500L,anchor.secondMm)
                assertNull(points.find(second.uuid)!!.mounting.anchor)
                scene.click("Пізніше")
                withTimeout(15000) {
                    while (points.forMeasurement(original.uuid).any { it.mounting.deferredAt == null }) {
                        scene.frame(); delay(20)
                    }
                }
                assertTrue(editing)
                // Workspace Save preserves the selected placement intentionally.
                // Back first clears that selection, then the next Back navigates.
                // Do not change production navigation simply to satisfy a stale
                // one-click expectation (and do not skip review/DB assertions).
                assertNotNull(
                    scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint),
                    "Review-later must preserve the selected point until explicitly deselected"
                )
                scene.click("До заміру")
                assertTrue(editing, "First Back should clear selection without leaving the editor")
                assertNull(
                    scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint),
                    "Back must clear the selected point before navigating"
                )
                scene.click("До заміру")
                withTimeout(15_000) {
                    while (editing) { scene.frame(); delay(20) }
                }
                assertFalse(editing)
                scene.awaitText("Перевірити (2)")
                scene.tapWorld(1500,600)
                scene.awaitText("Ця позначка: положення за виміряними відстанями")
                scene.click("Перевірити (2)")
                scene.awaitDrawingPoints(pointIds)
                scene.click("Елементи")
                scene.click("Вписати креслення")
                val markers = scene.canvas().config[MeasurementDrawingSemantics.Points]
                assertEquals(pointIds, markers.filter { it.mounting.needsReview }.map { it.uuid }.toSet())
                for ((index, marker) in markers.withIndex()) {
                    scene.tapWorld(marker.xMm, marker.yMm)
                    assertEquals(marker.uuid, scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint),
                        "Review marker ${index + 1} at ${marker.xMm}, ${marker.yMm}")
                    scene.awaitText("На перевірці")
                    assertFalse(scene.nodes().any {
                        it.config.getOrNull(SemanticsActions.OnClick) != null &&
                            it.textTree() == "Підтвердити розміщення"
                    }, "Canvas tap selects the marker without opening its geometry editor")
                    scene.click("Параметри розміщення", tag = "p5b-open-geometry-${marker.elementUuid}")
                    assertEquals(marker.uuid, scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint),
                        "Opening geometry must preserve the point selected on the drawing")
                    scene.click("Підтвердити розміщення")
                    withTimeout(5000) {
                        while (scene.text().contains("Підтвердити розміщення") ||
                            scene.canvas().config[MeasurementDrawingSemantics.Points].any {
                                it.uuid == marker.uuid && it.mounting.needsReview
                            }) {
                            scene.frame()
                            delay(20)
                        }
                    }
                    scene.click("Зняти виділення")
                }
                assertTrue(points.forMeasurement(original.uuid).all { it.mounting.needsReview })
                scene.click("Зберегти замір")
                withTimeout(15000) {
                    while (points.forMeasurement(original.uuid).any { it.mounting.needsReview }) {
                        scene.frame()
                        delay(25)
                    }
                }
                assertTrue(points.forMeasurement(original.uuid).none {it.mounting.needsReview})
                val beforeName=points.forMeasurement(original.uuid)
                val geometryBeforeName=MeasurementRepository(db).find(id)!!.drawingJson
                scene.click("Контур")
                scene.frame()
                scene.setField("Назва заміру","Нова назва");scene.click("Зберегти замір")
                withTimeout(15000) {
                    while (MeasurementRepository(db).find(id)?.name != "Нова назва") { scene.frame(); delay(20) }
                }
                scene.frame()
                assertTrue(editing)
                scene.setField("Примітки", "Другий запис")
                scene.click("До заміру")
                scene.click("Зберегти й вийти")
                withTimeout(15000) { while (editing) { scene.frame(); delay(20) } }
                assertEquals("Нова назва",MeasurementRepository(db).find(id)!!.name)
                assertEquals("Другий запис",MeasurementRepository(db).find(id)!!.notes)
                assertEquals(geometryBeforeName,MeasurementRepository(db).find(id)!!.drawingJson)
                assertEquals(beforeName,points.forMeasurement(original.uuid))
                assertEquals(2000L,elements.find(element.uuid)!!.placedQuantityMilli)
            } catch (failure: Throwable) {
                testFailure = failure; throw failure
            } finally {
                try { scene.close() } catch (cleanup: Throwable) {
                    if (testFailure != null) testFailure.addSuppressed(cleanup) else throw cleanup
                } finally { edit.closeAndAwaitPointReads(); details.elements.closeAndAwaitWrites(); driver.close(); directory.deleteRecursively() }
            }
        }
    }
    private fun ImageComposeScene.world(x: Long, y: Long): Offset {
        val b = stableBounds()
        val p = canvas().config[MeasurementDrawingSemantics.Projection].project(DrawingPoint(x.toDouble(), y.toDouble()))
        return Offset(b.left + p.x.toFloat(), b.top + p.y.toFloat())
    }
    private fun ImageComposeScene.tapWorld(x: Long, y: Long) {
        val deadline=System.nanoTime()+15_000_000_000L
        while(canvas().config.getOrNull(MeasurementDrawingSemantics.EditingEnabled)!=true && System.nanoTime()<deadline) {
            frame();Thread.sleep(20)
        }
        assertEquals(true,canvas().config.getOrNull(MeasurementDrawingSemantics.EditingEnabled),"Wait for the actual drawing editor")
        val p = world(x, y)
        sendPointerEvent(PointerEventType.Press, p, type = PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, p, type = PointerType.Touch); frame()
    }
    private fun ImageComposeScene.stableBounds(): Rect {
        var previous: Rect? = null; var stable = 0
        repeat(150) { frame(); val b = canvas().boundsInRoot; stable = if (b == previous) stable + 1 else 0
            if (stable >= 3) return b; previous = b; Thread.sleep(20) }
        fail("Canvas did not settle")
    }
    private fun ImageComposeScene.awaitDrawingPoints(expectedIds: Set<String>) {
        repeat(240) {
            frame()
            val actualIds = nodes().firstNotNullOfOrNull { it.config.getOrNull(MeasurementDrawingSemantics.Points) }
                ?.map { it.uuid }?.toSet()
            if (actualIds == expectedIds) return
            Thread.sleep(25)
        }
        fail("The drawing did not show the expected points: ${text().take(2000)}")
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
    private fun ImageComposeScene.click(label: String, tag: String? = null) {
        repeat(240) { frame(); nodes().firstOrNull {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Disabled) == null &&
                (if (tag != null) it.config.getOrNull(SemanticsProperties.TestTag) == tag
                else it.textTree() == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        }?.let {
            assertNull(it.config.getOrNull(SemanticsProperties.Disabled), label)
            assertTrue(it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true); frame(); return }; Thread.sleep(25) }
        fail("Missing action '$label': ${text().take(2000)}")
    }
    private fun ImageComposeScene.setField(label: String, value: String, tag: String? = null) {
        repeat(240) { frame(); nodes().firstOrNull {
            it.config.getOrNull(SemanticsActions.SetText) != null &&
                (if (tag != null) it.config.getOrNull(SemanticsProperties.TestTag) == tag
                    else it.textTree().contains(label))
        }?.let {
            assertTrue(it.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true); frame(); return }; Thread.sleep(25) }
        fail("Missing field '$label': ${text().take(2000)}")
    }
}
