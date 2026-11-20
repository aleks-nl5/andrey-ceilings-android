package com.andreyceilings.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
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
            var route = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7).openMeasurement(7, 3).editMeasurement(7, 3)
            val scene = ImageComposeScene(600, 1000) {
                CeilingsTheme(dark) { MeasurementEditScreen(model, onBack = {
                    val next = guard.transition(route, route.back())
                    if (next != route) backs++
                    route = next
                }, onSaved = {}, navigationGuard = guard) }
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
                assertTrue(scene.text().contains("Назва заміру"))
                scene.click("Відхилити"); scene.frame()
                assertTrue(scene.text().contains("Незбережені зміни")); assertEquals(0, backs)
                scene.click("Продовжити"); scene.frame(); assertTrue(model.state.value.dirty)
                scene.click("Відхилити"); scene.frame(); scene.click("Відхилити зміни")
                assertEquals(1, backs); assertFalse(model.state.value.dirty)
                assertEquals(MeasurementDetails(7, 3), route.measurementDetails)
            } finally { scene.close() }
        }
    }
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun mouseWheelPointerSelectionAndKeyboardChangeOnlyTheViewport() {
        val geometry = assertNotNull(validateMeasurementCreate(draft()).geometry)
        val wire = MeasurementGeometryCodec.write(geometry)
        var selected by mutableStateOf<Int?>(null)
        val scene = ImageComposeScene(600, 600) {
            CeilingsTheme(false) {
                val focus = androidx.compose.ui.platform.LocalFocusManager.current
                MeasurementDrawing(MeasurementGeometryView.Captured(geometry),
                    Modifier.fillMaxSize(), interactive = true, selectedSide = selected,
                    onSelectSide = { focus.clearFocus(); selected = it }, fullscreen = true)
            }
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

    @Test fun workspaceRetainsTheDrawingAndDraftAcrossContextsAndPanelSizes() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(720, 420) {
            CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}, onSaved = {}) }
        }
        fun drawing() = scene.projectedDrawing()
        fun panel() = scene.nodes().single {
            it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-context-panel"
        }
        try {
            scene.frame()
            val initial = drawing()
            val bounds = initial.boundsInRoot
            val geometry = assertNotNull(validateMeasurementCreate(draft()).geometry)
            val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
            val projection = initial.config[MeasurementDrawingSemantics.Projection]
            val a = projection.project(contour.points[0])
            val b = projection.project(contour.points[1])
            val wall = Offset(bounds.left + ((a.x + b.x) / 2).toFloat(), bounds.top + ((a.y + b.y) / 2).toFloat())
            scene.sendPointerEvent(PointerEventType.Press, wall, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, wall, type = PointerType.Mouse)
            scene.frame()
            assertEquals(0, drawing().config[MeasurementDrawingSemantics.SelectedWall])
            scene.sendPointerEvent(PointerEventType.Scroll, wall, scrollDelta = Offset(0f, -2f))
            scene.frame()
            val zoomed = drawing().config[MeasurementDrawingSemantics.Viewport]
            assertTrue(zoomed.zoom > 1f)
            val field = scene.nodes().single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "5" }
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("5,25")) == true)
            scene.frame()
            scene.click("Елементи")
            scene.frame()
            scene.click("Вартість")
            scene.frame()
            assertTrue(scene.text().contains("Розрахунок за останнім збереженим заміром"))
            assertFalse(scene.text().contains("Відкрити замір із калькулятором"))
            scene.click("Контур")
            scene.frame()
            val panelBefore = panel().boundsInRoot.height
            val toggle = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-panel" }
            assertTrue(toggle.config[SemanticsActions.OnClick].action?.invoke() == true)
            scene.frame()
            assertTrue(panel().boundsInRoot.height < panelBefore)
            scene.click("На весь екран")
            scene.frame()
            assertTrue(drawing().boundsInRoot.height > 0)
            assertEquals(initial.id, drawing().id)
            assertEquals(zoomed, drawing().config[MeasurementDrawingSemantics.Viewport])
            assertEquals(0, drawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "5,25" })
            val scrollAxis = panel().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(scrollAxis.maxValue() > 0)
            val panelBounds = panel().boundsInRoot
            scene.sendPointerEvent(PointerEventType.Scroll, panelBounds.center, scrollDelta = Offset(0f, 2f))
            repeat(12) { scene.frame(); Thread.sleep(10) }
            assertTrue(scrollAxis.value() > 0f)
            assertEquals(zoomed, drawing().config[MeasurementDrawingSemantics.Viewport])
            scene.click("Вписати креслення")
            scene.frame()
            assertEquals(MeasurementViewport(), drawing().config[MeasurementDrawingSemantics.Viewport])
        } finally { scene.close() }
    }

    @Test fun workspaceKeepsWorldCenterAndPixelScaleAcrossResizeAndFullscreen() {
        for ((width, height) in listOf(360 to 760, 760 to 430)) {
            var keyboardInset by mutableIntStateOf(0)
            val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
            val scene = ImageComposeScene(width, height) {
                CeilingsTheme(false) {
                    MeasurementEditScreen(model, onBack = {}, onSaved = {},
                        imeInsets = WindowInsets(0, 0, 0, keyboardInset))
                }
            }
            fun drawing() = scene.projectedDrawing()
            fun togglePanel() {
                val toggle = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-panel" }
                assertTrue(toggle.config[SemanticsActions.OnClick].action?.invoke() == true)
                scene.frame()
            }
            val first = DrawingPoint(1000.0, 1500.0)
            val second = DrawingPoint(4000.0, 1500.0)
            fun pixelDistance(node: SemanticsNode): Double {
                val projection = node.config[MeasurementDrawingSemantics.Projection]
                return projection.project(second).x - projection.project(first).x
            }
            fun worldCenter(node: SemanticsNode): DrawingPoint {
                val projection = node.config[MeasurementDrawingSemantics.Projection]
                return projection.inverse(DrawingPoint(node.size.width / 2.0, node.size.height / 2.0))
            }
            try {
                scene.frame()
                val normalExpandedHeight = drawing().size.height
                val originalCenter = worldCenter(drawing())
                val center = drawing().boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Move, center + Offset(32f, 0f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, center + Offset(32f, 0f), type = PointerType.Mouse)
                scene.frame()
                scene.click("+")
                scene.frame()
                val initial = drawing()
                val expectedDistance = pixelDistance(initial)
                val expectedCenter = worldCenter(initial)
                assertTrue(kotlin.math.abs(originalCenter.x - expectedCenter.x) > 1.0, "The pointer gesture must pan the drawing")
                fun assertCamera() {
                    val current = drawing()
                    assertEquals(initial.id, current.id)
                    assertEquals(expectedDistance, pixelDistance(current), 0.01)
                    assertEquals(expectedCenter.x, worldCenter(current).x, 0.01)
                    assertEquals(expectedCenter.y, worldCenter(current).y, 0.01)
                }
                togglePanel()
                assertTrue(drawing().size.height > normalExpandedHeight)
                assertCamera()
                togglePanel()
                assertCamera()
                scene.click("На весь екран")
                scene.frame()
                val fullscreenHeight = drawing().size.height
                assertTrue(fullscreenHeight > normalExpandedHeight * 1.3, "Fullscreen should give the drawing substantially more room")
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Розгорнути панель") })
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-save" })
                assertCamera()
                keyboardInset = if (height > width) 208 else 72
                scene.frame()
                assertTrue(drawing().size.height < fullscreenHeight, "IME must change the drawing bounds")
                assertCamera()
                keyboardInset = 0
                scene.frame()
                togglePanel()
                assertTrue(drawing().size.height < fullscreenHeight)
                assertCamera()
                scene.click("Згорнути")
                scene.frame()
                assertEquals(normalExpandedHeight, drawing().size.height)
                assertCamera()
                scene.click("Вписати креслення")
                scene.frame()
                assertTrue(kotlin.math.abs(expectedDistance - pixelDistance(drawing())) > 1.0)
            } finally { scene.close() }
        }
    }

    @Test fun narrowWorkspaceShowsTheCompleteValidationErrorWithoutResizingTheCanvas() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(300, 580) {
            CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}, onSaved = {}) }
        }
        fun canvas() = scene.projectedDrawing()
        try {
            scene.frame()
            val originalBounds = canvas().boundsInRoot
            scene.click("Усі розміри")
            scene.frame()
            val field = scene.nodes().single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "5" }
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("")) == true)
            scene.frame()
            assertEquals(originalBounds, canvas().boundsInRoot)
            val details = scene.nodes().single {
                "Показати повне повідомлення" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
            assertTrue(details.config[SemanticsActions.OnClick].action?.invoke() == true)
            scene.frame()
            val fullMessage = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-status-details"
            }
            val error = assertNotNull(validateMeasurementCreate(model.state.value.draft).geometryError)
            assertEquals("Невалідна чернетка: показано збережений контур і позначки. $error", fullMessage.textTree())
            assertTrue(fullMessage.boundsInRoot.height > 48)
            scene.click("Закрити")
            scene.frame()
            assertEquals(originalBounds, canvas().boundsInRoot)
        } finally { scene.close() }
    }

    @Test fun explicitDecimalSeparatorWorksWhenTheImeHasNoCommaKey() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val image = ImageComposeScene(600, 900) {
            CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}, onSaved = {}) }
        }
        try {
            image.render().close()
            image.reveal {it.config.getOrNull(SemanticsActions.OnClick)!=null && it.textTree().contains("Усі розміри")}
            image.click("Усі розміри"); image.frame()
            image.reveal {it.config.getOrNull(SemanticsProperties.EditableText)?.text=="5"}
            val field = image.nodes().first {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "5"
            }
            assertTrue(field.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("2")) == true)
            image.frame()
            val button = image.nodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .contains("Вставити десяткову кому")
            }
            assertTrue(button.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            image.frame()
            assertEquals("2,", (model.state.value.draft.outline as MeasurementOutlineDraft.Template).fields["length"])
            val nextField = image.nodes().first {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "2,"
            }
            assertTrue(nextField.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("2,5")) == true)
            image.frame()
            val metres = (model.state.value.draft.outline as MeasurementOutlineDraft.Template).fields.getValue("length")
            assertEquals(2500L, measurementLengthMm(metres, model.state.value.draft.precision))
        } finally { image.close() }
    }

    private fun ImageComposeScene.projectedDrawing(): SemanticsNode {
        // Layout reports a new Canvas size during placement. Publish its projection on the next frame.
        frame()
        return nodes().single { it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null }
    }

    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(System.nanoTime()).close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.reveal(predicate:(SemanticsNode)->Boolean) {
        repeat(30) {
            frame()
            if(nodes().any(predicate))return
            val scroll=nodes().firstOrNull {it.config.getOrNull(SemanticsActions.ScrollBy)!=null}
            assertTrue(scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f,160f)==true,"Editor must remain scrollable")
            repeat(4){frame();Thread.sleep(10)}
        }
        fail("Editor control did not become available by scrolling")
    }
    private fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree().contains(label) }
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }
}
