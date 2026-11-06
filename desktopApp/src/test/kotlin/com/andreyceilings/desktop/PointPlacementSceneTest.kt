package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class PointPlacementSceneTest {
    @Test fun ordinaryDetailsRealTapSelectDeleteBackFullscreenAndPanInBothThemes(): Unit = runBlocking {
        for (dark in listOf(false, true)) {
            val directory = java.nio.file.Files.createTempDirectory("point-scene-").toFile()
            val driver = if (System.getProperty("os.name").startsWith("Windows", true))
                SqlCipherWindowsDriverFactory.open(directory.resolve("scene.db"), ByteArray(32) { 79 })
            else JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Scene", createdAt = 1)
            val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Кухня", "",
                MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5", "width" to "4")))
            val measurement = MeasurementCreateStore(db) { 2 }.create(draft)
            val row = MeasurementRepository(db).find(measurement)!!
            val spot = PriceRepository(db).all().single { it.code == "light.spot" }
            val element = MeasurementElementRepository(db).createCatalogSelection(row.uuid, spot.uuid, ElementQuantity.Exact(6000), 3)
            val details = MeasurementDetailsStore(db, { 4 }, Dispatchers.Unconfined)
            val edit=MeasurementEditStore(db){20}
            val model = details.elements.model(ElementRoute(project, measurement, row.uuid))
            val guard = MeasurementCreateNavigationGuard()
            val nav = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).openMeasurement(project, measurement)
            withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } }
            withTimeout(10_000) { model.points.first { it.available } }
            val scene = ImageComposeScene(700, 1800) {
                CeilingsTheme(dark) {
                    MeasurementDetailsScreen(project, measurement, details, androidx.compose.foundation.layout.PaddingValues(),
                        androidx.compose.ui.unit.Dp(0f), {}, navigationGuard = guard,editStore=edit)
                }
            }
            suspend fun count(n: Int) {
                withTimeout(10_000) { model.points.first { !it.saving && it.points.size == n } }
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded && it.items.single().placedQuantityMilli == n * 1000L } }
                scene.frame()
                withTimeout(10000){while(scene.canvas().config.getOrNull(MeasurementDrawingSemantics.Points).orEmpty().size!=n){scene.frame();delay(20)}}
            }
            try {
                scene.awaitText("Розміщено: 0 з 6")
                withTimeout(15000){while(scene.canvas().config.getOrNull(MeasurementDrawingSemantics.EditingEnabled)!=true){scene.frame();delay(20)}}
                scene.click("Розмістити"); scene.awaitText("Приблизне розміщення")
                scene.tapWorld(row, 0, 1000)
                scene.awaitText("Торкніться всередині контуру")
                assertTrue(model.points.value.points.isEmpty())
                scene.tapWorld(row, 1000, 1000); count(1)
                val first = model.points.value.points.single()
                scene.tapWorld(row, 2000, 1000); count(2)
                scene.awaitText("Розміщено: 2 з 6")
                // Enlarged marker hit target selects the existing record; it cannot add a third.
                scene.tapWorld(row, first.xMm, first.yMm, dx = 12f)
                assertEquals(first.uuid,scene.canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedPoint))
                assertEquals(element.uuid,scene.canvas().config[MeasurementDrawingSemantics.Points].first {it.uuid==first.uuid}.elementUuid)
                assertEquals(2, model.points.value.points.size)
                scene.click("Видалити позначку"); scene.awaitText("Видалити позначку?")
                scene.click("Скасувати"); assertEquals(2, model.points.value.points.size)
                scene.click("Видалити позначку"); scene.frame()
                // Confirmation button has the same label; select its dialog semantics.
                val confirm = scene.nodes().last { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree() == "Видалити позначку" }
                assertTrue(confirm.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
                count(1)
                val canvas = scene.canvas().boundsInRoot
                val start = Offset(canvas.center.x, canvas.center.y)
                scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Move, start + Offset(70f, 50f), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, start + Offset(70f, 50f), type = PointerType.Touch)
                scene.frame(); delay(100); assertEquals(1, model.points.value.points.size, "Pan must not place")
                scene.click("Вписати креслення")
                assertEquals(nav.back(), guard.transition(nav, nav.back()))
                assertNull(model.points.value.modeElementUuid)
                scene.click("На весь екран"); scene.frame()
                assertTrue(guard.fullscreen.value)
                assertFalse(scene.text().contains("Розмістити"))
                assertEquals(1, model.points.value.points.size)
                scene.click("Згорнути"); scene.frame()
                scene.awaitText("Розміщено: 1 з 6")
                assertEquals(row.drawingJson, MeasurementRepository(db).find(measurement)!!.drawingJson)
                assertEquals(1, PointPlacementRepository(db).forMeasurement(row.uuid).size)
            } finally { scene.close(); details.elements.closeAndAwaitWrites();edit.closeAndAwaitPointReads(); driver.close(); directory.deleteRecursively() }
        }
    }

    private fun ImageComposeScene.tapWorld(row: Room_measurements, x: Long, y: Long, dx: Float = 0f) {
        val bounds = stableCanvasBounds()
        val contour = measurementDrawingContour(measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters))!!
        val p = measurementDrawingProjection(contour, bounds.width, bounds.height)!!.project(DrawingPoint(x.toDouble(), y.toDouble()))
        val pos = Offset(bounds.left + p.x.toFloat() + dx, bounds.top + p.y.toFloat())
        sendPointerEvent(PointerEventType.Press, pos, type = PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, pos, type = PointerType.Touch)
        frame()
    }
    private fun ImageComposeScene.stableCanvasBounds(): androidx.compose.ui.geometry.Rect {
        var previous: androidx.compose.ui.geometry.Rect? = null
        var stable = 0
        repeat(150) {
            frame()
            val current = canvas().boundsInRoot
            stable = if (current == previous) stable + 1 else 0
            if (stable >= 3) return current
            previous = current
            Thread.sleep(20)
        }
        fail("Canvas did not settle before pointer injection")
    }
    private fun ImageComposeScene.canvas() = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Контур стелі") } }
    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(System.nanoTime()).close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.awaitText(label: String) {
        repeat(200) { frame(); if (text().contains(label)) return; Thread.sleep(25) }
        fail("Missing '$label': ${text().take(1800)}")
    }
    private fun ImageComposeScene.click(label: String) {
        repeat(200) {
            frame()
            nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree() == label }?.let {
                assertNull(it.config.getOrNull(SemanticsProperties.Disabled), label)
                assertTrue(it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true); frame(); return
            }
            Thread.sleep(25)
        }
        fail("Missing action '$label': ${text().take(1800)}")
    }
}
