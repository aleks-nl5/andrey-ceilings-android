package com.andreyceilings.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.jetbrains.skia.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlin.test.*

class MeasurementEditSceneTest {
    private fun draft() = MeasurementCreateDraft(7, MeasurementPrecision.ONE_MILLIMETER, "Кухня", "",
        MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5", "width" to "4")))

    @Test fun readonlyFullscreenHasSelectionAndViewToolsWithoutEditableFields() {
        for (dark in listOf(false, true)) {
            val draft = draft(); val geometry = assertNotNull(validateMeasurementCreate(draft).geometry)
            val guard = MeasurementCreateNavigationGuard()
            var edits = 0
            val scene = ImageComposeScene(600, 900) {
                CeilingsTheme(dark) {
                    MeasurementDetailsScreen(MeasurementDetailsState.Loaded(MeasurementDetailsData(3, 7,
                        "Кухня", "Об'єкт", "Адреса", "rectangle", 20.0, 18.0, "", MeasurementGeometryView.Captured(geometry))),
                        onBack = {}, onEdit = { edits++ }, navigationGuard = guard)
                }
            }
            try {
                scene.render().close()
                assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null })
                scene.click("На весь екран"); scene.frame()
                assertTrue(guard.fullscreen.value)
                assertTrue(scene.text().contains("Вписати креслення"))
                assertFalse(scene.text().contains("Зберегти"))
                scene.click("BC:"); scene.click("+"); scene.click("Вписати креслення")
                scene.click("Редагувати"); assertEquals(1, edits)
                val route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7).openMeasurement(7, 3)
                assertEquals(route, guard.transition(route, route.back()))
                scene.frame(); assertFalse(guard.fullscreen.value)
                assertEquals(geometry, validateMeasurementCreate(draft).geometry)
            } finally { scene.close() }
        }
    }
    @Test fun editFieldsHistoryDirtyDialogAndFullscreenAreRealActions() {
        for (dark in listOf(false, true)) {
            val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
            val guard = MeasurementCreateNavigationGuard()
            var backs = 0
            val scene = ImageComposeScene(600, 1000) {
                CeilingsTheme(dark) { MeasurementEditScreen(model, onBack = { backs++ }, onSaved = {}, navigationGuard = guard) }
            }
            try {
                scene.render().close()
                val name = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(name.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("Нова кухня")) == true)
                model.commitInput(); scene.frame()
                assertTrue(model.state.value.dirty)
                scene.click("Скасувати дію"); scene.frame(); assertEquals("Кухня", model.state.value.draft.name)
                scene.click("Повторити"); scene.frame(); assertEquals("Нова кухня", model.state.value.draft.name)
                scene.click("На весь екран"); scene.frame(); assertTrue(guard.fullscreen.value)
                assertFalse(scene.text().contains("Назва заміру"))
                scene.click("Згорнути"); scene.frame()
                scene.click("Відхилити"); scene.frame()
                assertTrue(scene.text().contains("Незбережені зміни")); assertEquals(0, backs)
                scene.click("Продовжити"); scene.frame(); assertTrue(model.state.value.dirty)
                scene.click("Відхилити"); scene.frame(); scene.click("Відхилити зміни")
                assertEquals(1, backs); assertFalse(model.state.value.dirty)
            } finally { scene.close() }
        }
    }
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun mouseWheelPointerSelectionAndKeyboardChangeOnlyTheViewport() {
        val geometry = assertNotNull(validateMeasurementCreate(draft()).geometry)
        val wire = MeasurementGeometryCodec.write(geometry)
        var selected by mutableStateOf<Int?>(null)
        val scene = ImageComposeScene(600, 600) {
            CeilingsTheme(false) { MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
                Modifier.fillMaxSize(), interactive = true, selectedSide = selected,
                onSelectSide = { selected = it }, fullscreen = true) }
        }
        fun checksum(): Long {
            val image = scene.render(System.nanoTime())
            val bitmap = Bitmap.makeFromImage(image)
            try {
                var result = 0L
                for (y in 0 until 600 step 4) for (x in 0 until 600 step 4) result = result * 31 + bitmap.getColor(x, y)
                return result
            } finally { bitmap.close(); image.close() }
        }
        try {
            scene.render().close()
            val canvas = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text -> text.startsWith("Контур стелі") } }
            val bounds = canvas.boundsInRoot
            val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
            val layout = assertNotNull(layoutMeasurementDrawing(contour, bounds.width, bounds.height, emptyList()))
            val x = ((layout.points[0].x + layout.points[1].x) / 2).toFloat() + bounds.left
            val y = layout.points[0].y.toFloat() + bounds.top
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Mouse)
            scene.frame(); assertEquals(0, selected)
            val before = checksum()
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, -2f))
            scene.frame(); assertNotEquals(before, checksum())
            assertTrue(scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(
                androidx.compose.ui.input.key.Key.MoveHome, androidx.compose.ui.input.key.KeyEventType.KeyDown)))
            scene.frame()
            scene.click("Вписати креслення")
            assertEquals(wire, MeasurementGeometryCodec.write(geometry))
        } finally { scene.close() }
    }

    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(System.nanoTime()).close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree().contains(label) }
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }
}
