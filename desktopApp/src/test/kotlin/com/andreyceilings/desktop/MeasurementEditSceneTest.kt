package com.andreyceilings.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.jetbrains.skia.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import com.andreyceilings.shared.theme.ceilingsColorScheme
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

    @Test fun shortCanvasKeepsThreeFullSizedZoomTargets() {
        val geometry = assertNotNull(validateMeasurementCreate(draft()).geometry)
        val scene = ImageComposeScene(640, 120) {
            CeilingsTheme(false) {
                MeasurementDrawing(MeasurementGeometryView.Captured(geometry), Modifier.fillMaxSize(),
                    interactive = true, fillAvailableHeight = true, showSideLegend = false,
                    retainCameraOnResize = true, verticalViewControls = true)
            }
        }
        try {
            repeat(4) { scene.frame() }
            val targets = scene.nodes(unmerged = true).filter {
                it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-zoom-target"
            }
            assertEquals(3, targets.size)
            assertTrue(targets.all { it.size.width == 48 && it.size.height == 48 })
            assertEquals(1, targets.map { it.positionInRoot.y }.toSet().size)
            assertEquals(640f, targets.maxOf { it.boundsInRoot.right }, 1f)
        } finally { scene.close() }
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
                }, navigationGuard = guard) }
            }
            try {
                scene.render().close()
                scene.click("Деталі заміру")
                scene.frame()
                val name = scene.nodes().single {
                    it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains("Назва заміру")
                }
                assertTrue(name.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("Нова кухня")) == true)
                model.commitInput(); scene.frame()
                assertTrue(model.state.value.dirty)
                scene.click("Скасувати дію"); scene.frame(); assertEquals("Кухня", model.state.value.draft.name)
                scene.click("Повторити"); scene.frame(); assertEquals("Нова кухня", model.state.value.draft.name)
                scene.click("На весь екран"); scene.frame(); assertTrue(guard.fullscreen.value)
                scene.click("Контур"); scene.frame()
                scene.click("Деталі заміру"); scene.frame()
                assertTrue(scene.text().contains("Назва заміру"))
                scene.click("До заміру")
                scene.finishSheetMotion()
                assertTrue(guard.fullscreen.value)
                assertFalse(scene.text().contains("Назва заміру"))
                scene.click("До заміру"); scene.frame()
                assertFalse(guard.fullscreen.value)
                scene.click("До заміру"); scene.frame()
                assertTrue(scene.text().contains("Незбережені зміни")); assertEquals(0, backs)
                scene.click("Продовжити редагування"); scene.frame(); assertTrue(model.state.value.dirty)
                scene.click("До заміру"); scene.frame(); scene.click("Відхилити зміни")
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
            CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) }
        }
        fun drawing() = scene.projectedDrawing()
        fun panel() = scene.nodes().single {
            it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-context-panel"
        }
        try {
            repeat(6) { scene.frame() }
            scene.click("Вписати креслення")
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
            val panelBefore = panel().boundsInRoot
            scene.click("Елементи")
            scene.frame()
            assertEquals(panelBefore, panel().boundsInRoot)
            scene.click("Вартість")
            scene.frame()
            assertEquals(panelBefore, panel().boundsInRoot)
            assertTrue(scene.text().contains("Розрахунок за останнім збереженим заміром"))
            assertFalse(scene.text().contains("Переглянути збережений розрахунок у картці заміру"))
            scene.click("На весь екран")
            scene.frame()
            assertTrue(drawing().boundsInRoot.height > 0)
            assertEquals(initial.id, drawing().id)
            assertEquals(zoomed, drawing().config[MeasurementDrawingSemantics.Viewport])
            assertEquals(0, drawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertFalse(scene.text().contains("Вартість"))
            scene.click("Контур")
            scene.finishSheetMotion()
            assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "5,25" })
            scene.click("Деталі заміру")
            scene.finishSheetMotion()
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
                    MeasurementEditScreen(model, onBack = {},
                        imeInsets = WindowInsets(0, 0, 0, keyboardInset))
                }
            }
            fun drawing() = scene.projectedDrawing()
            val first = DrawingPoint(1000.0, 1500.0)
            val second = DrawingPoint(4000.0, 1500.0)
            fun pixelDistance(node: SemanticsNode): Double {
                val projection = node.config[MeasurementDrawingSemantics.Projection]
                return projection.project(second).x - projection.project(first).x
            }
            fun worldCenter(node: SemanticsNode): DrawingPoint {
                val projection = node.config[MeasurementDrawingSemantics.Projection]
                val center = node.config[MeasurementDrawingSemantics.ViewportBounds].center
                return projection.inverse(DrawingPoint(center.x.toDouble(), center.y.toDouble()))
            }
            try {
                scene.frame()
                val normalExpandedHeight = drawing().size.height
                val normalViewportHeight = drawing().config[MeasurementDrawingSemantics.ViewportBounds].height
                val originalCenter = worldCenter(drawing())
                val center = drawing().boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Move, center + Offset(32f, 0f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, center + Offset(32f, 0f), type = PointerType.Mouse)
                scene.frame()
                scene.click("Збільшити масштаб")
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
                scene.click("Елементи")
                scene.frame()
                assertEquals(normalExpandedHeight, drawing().size.height)
                assertCamera()
                scene.click("Вартість")
                scene.frame()
                assertEquals(normalExpandedHeight, drawing().size.height)
                assertCamera()
                scene.click("На весь екран")
                scene.frame()
                val fullscreenHeight = drawing().size.height
                assertTrue(fullscreenHeight > normalViewportHeight * 1.3, "Fullscreen should give the drawing substantially more room")
                assertFalse(scene.text().contains("Вартість"))
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-save" })
                assertCamera()
                keyboardInset = if (height > width) 208 else 72
                scene.frame()
                assertTrue(drawing().size.height < fullscreenHeight, "IME must change the drawing bounds")
                assertCamera()
                keyboardInset = 0
                scene.frame()
                scene.click("Елементи")
                scene.frame()
                assertEquals(fullscreenHeight, drawing().size.height)
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

    @Test fun drawingGridUsesTheWorldProjectionAndLeavesTheContourUnchanged() {
        val geometry = assertNotNull(validateMeasurementCreate(draft()).geometry)
        val wire = MeasurementGeometryCodec.write(geometry)
        var grid by mutableStateOf(false)
        val scene = ImageComposeScene(640, 480) {
            CeilingsTheme(true) {
                MeasurementDrawing(MeasurementGeometryView.Captured(geometry), Modifier.fillMaxSize(),
                    interactive = true, fillAvailableHeight = true, showSideLegend = false,
                    retainCameraOnResize = true, verticalViewControls = true, showWorkspaceGrid = grid)
            }
        }
        fun pixel(x: Float, y: Float): Int {
            val rendered = scene.render(System.nanoTime())
            val bitmap = Bitmap.makeFromImage(rendered)
            try { return bitmap.getColor(x.toInt(), y.toInt()) }
            finally { bitmap.close(); rendered.close() }
        }
        try {
            repeat(4) { scene.frame() }
            val initial = scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection]
            val origin = initial.project(DrawingPoint(0.0, 0.0))
            val blank = pixel(origin.x.toFloat(), 10f)
            grid = true
            scene.frame()
            assertNotEquals(blank, pixel(origin.x.toFloat(), 10f))
            assertEquals(initial, scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection])
            scene.click("Збільшити масштаб")
            val zoomed = scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection]
            assertTrue(zoomed.scale > initial.scale)
            assertEquals(wire, MeasurementGeometryCodec.write(geometry))
        } finally { scene.close() }
    }

    @Test fun workspaceCompositesTheSharedFadeAndReadableForegroundInBothThemes() {
        var dark by mutableStateOf(false)
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(360, 740) {
            CeilingsTheme(dark) { MeasurementEditScreen(model, PaddingValues(12.dp, 20.dp, 12.dp, 24.dp), onBack = {}) }
        }
        fun contrast(first: Color, second: Color): Float {
            val low = minOf(first.luminance(), second.luminance())
            val high = maxOf(first.luminance(), second.luminance())
            return (high + 0.05f) / (low + 0.05f)
        }
        fun sample(x: Int, y: Int, radius: Int = 0): Color {
            val rendered = scene.render(System.nanoTime())
            val bitmap = Bitmap.makeFromImage(rendered)
            try {
                val pixels = (y - radius..y + radius).flatMap { row ->
                    (x - radius..x + radius).map { column -> Color(bitmap.getColor(column, row)) }
                }.sortedBy { it.luminance() }
                return pixels[pixels.size / 2]
            }
            finally { bitmap.close(); rendered.close() }
        }
        fun closeColor(actual: Color, expected: Color) {
            assertEquals(expected.red, actual.red, 0.045f)
            assertEquals(expected.green, actual.green, 0.045f)
            assertEquals(expected.blue, actual.blue, 0.045f)
        }
        try {
            repeat(5) { scene.frame() }
            val initial = scene.projectedDrawing()
            for (theme in listOf(false, true)) {
                dark = theme
                repeat(5) { scene.frame() }
                val colors = ceilingsColorScheme(theme)
                val clear = sample(14, 730)
                val normalFade = sample(14, 22)
                closeColor(sample(2, 22), normalFade)
                closeColor(clear, colors.background)
                closeColor(normalFade, (if (theme) colors.surfaceBright else colors.surfaceDim).copy(alpha = 0.50f).compositeOver(clear))
                assertNotEquals(normalFade, clear)
                assertNotEquals(normalFade, if (theme) colors.surfaceBright else colors.surfaceDim)
                val title = scene.nodes().single { it.textTree() == "Кухня" && it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
                val layout = mutableListOf<TextLayoutResult>()
                assertTrue(title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout) == true)
                assertTrue(contrast(layout.single().layoutInput.style.color, normalFade) >= 4.5f)

                val fit = scene.nodes().single { "Вписати креслення" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                val center = fit.boundsInRoot.center
                assertTrue(contrast(sample(center.x.toInt(), center.y.toInt()), colors.surface) >= 3f)
                scene.click("На весь екран")
                scene.finishSheetMotion()
                // A small region excludes an individual major grid stroke under the fullscreen fade.
                closeColor(sample(19, 25, 3), colors.surface.copy(alpha = 0.50f).compositeOver(clear))
                scene.click("Контур")
                scene.finishSheetMotion()
                assertTrue(contrast(colors.onBackground, colors.background) >= 4.5f)
                assertTrue(contrast(colors.onSurfaceVariant, colors.background) >= 4.5f)
                assertTrue(contrast(colors.error, colors.background) >= 4.5f)
                scene.click("Згорнути")
                scene.finishSheetMotion()
                val current = scene.projectedDrawing()
                assertEquals(initial.id, current.id)
                assertEquals(initial.config[MeasurementDrawingSemantics.Projection], current.config[MeasurementDrawingSemantics.Projection])
            }
        } finally { scene.close() }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun fullscreenCanvasOpensWallParametersWithDoubleClickOrEnter() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(720, 680) { CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) } }
        fun panelOpen() = scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-context-panel" }
        fun wall(world: DrawingPoint): Offset {
            val node = scene.projectedDrawing()
            val point = node.config[MeasurementDrawingSemantics.Projection].project(world)
            return node.positionInRoot + Offset(point.x.toFloat(), point.y.toFloat())
        }
        fun mouseTap(point: Offset, time: Long) {
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time + 10, type = PointerType.Mouse)
            scene.frame()
        }
        try {
            repeat(6) { scene.frame() }
            scene.click("На весь екран")
            scene.frame()
            scene.click("Вписати креслення")
            val top = wall(DrawingPoint(2500.0, 0.0))
            val side = wall(DrawingPoint(0.0, 2000.0))
            mouseTap(top, 1000)
            assertEquals(0, scene.projectedDrawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertFalse(panelOpen())
            mouseTap(side, 1100)
            assertEquals(3, scene.projectedDrawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertFalse(panelOpen())
            mouseTap(side, 1600)
            assertFalse(panelOpen())
            mouseTap(side, 1700)
            scene.finishSheetMotion()
            assertTrue(panelOpen())
            scene.click("Закрити панель")
            scene.finishSheetMotion()
            mouseTap(top, 2200)
            assertFalse(panelOpen())
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
            scene.finishSheetMotion()
            assertTrue(panelOpen())
            // An empty tap dismisses the opened panel and clears the wall selection.
            val bounds = scene.projectedDrawing().boundsInRoot
            mouseTap(Offset(bounds.left + 6f, bounds.top + bounds.height * 0.35f), 2900)
            scene.finishSheetMotion()
            assertFalse(panelOpen())
            assertNull(scene.projectedDrawing().config.getOrNull(MeasurementDrawingSemantics.SelectedWall))
        } finally { scene.close() }
    }

    @Test fun fullscreenFabMovesThroughIntermediateBoundsAndTheHandleOwnsOnlyItsTarget() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(600, 740) { CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) } }
        var time = System.nanoTime()
        fun tick(frames: Int = 1) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                time += 16_000_000
                scene.render(time).close()
            }
        }
        fun tagged(tag: String) = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun y() = tagged("measurement-edit-context-contour").positionInRoot.y
        fun drag(start: Offset, distance: Float) {
            val end = start + Offset(0f, distance)
            scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, end, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, end, type = PointerType.Mouse)
        }
        fun assertBetween(a: Float, mid: Float, b: Float) {
            assertTrue(mid > minOf(a, b) + 2 && mid < maxOf(a, b) - 2, "Expected movement through $a → $mid → $b")
        }
        try {
            tick(5)
            scene.click("На весь екран")
            tick(25)
            val initial = scene.nodes().single { it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null }
            val closed = y()
            val fab = tagged("measurement-edit-context-contour")
            assertNotNull(fab.config.getOrNull(SemanticsActions.CustomActions))

            scene.click("Контур")
            tick(8)
            val entering = y()
            tick(22)
            assertBetween(closed, entering, y())
            scene.click("Деталі заміру")
            tick(24)
            val compact = y()

            val handle = tagged("measurement-edit-sheet-handle")
            assertEquals(48, handle.size.width)
            assertEquals(48, handle.size.height)
            assertEquals(300f, handle.boundsInRoot.center.x, 1f)
            fun viewport() = scene.nodes().single { it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null }
                .config[MeasurementDrawingSemantics.Viewport]
            val beforePan = viewport()
            val outside = handle.boundsInRoot.center + Offset(85f, 0f)
            drag(outside, -90f)
            tick(22)
            assertEquals(compact, y(), 1f)
            assertEquals(handle.id, tagged("measurement-edit-sheet-handle").id)
            assertNotEquals(beforePan, viewport())

            drag(handle.boundsInRoot.center, -92f)
            tick(8)
            val expanding = y()
            tick(22)
            val expanded = y()
            assertBetween(compact, expanding, expanded)
            assertEquals(fab.id, tagged("measurement-edit-context-contour").id)

            drag(tagged("measurement-edit-sheet-handle").boundsInRoot.center, 92f)
            tick(8)
            val leaving = y()
            tick(22)
            assertBetween(expanded, leaving, y())
            assertEquals(closed, y(), 1f)
            assertFalse(scene.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-context-panel" })
            drag(tagged("measurement-edit-context-contour").boundsInRoot.center, -92f)
            tick(24)
            assertEquals(expanded, y(), 1f)
            val current = scene.nodes().single { it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null }
            assertEquals(initial.id, current.id)
            assertEquals(initial.boundsInRoot, current.boundsInRoot)
            assertEquals(initial.config[MeasurementDrawingSemantics.Projection].scale, current.config[MeasurementDrawingSemantics.Projection].scale)
        } finally { scene.close() }
    }

    @Test fun firstFitUsesTheSettledLayoutAfterTheLoadingRowDisappears() {
        val source = draft().copy(outline = MeasurementOutlineDraft.Template("rectangle",
            mapOf("length" to "2", "width" to "3")))
        val geometry = assertNotNull(validateMeasurementCreate(source).geometry)
        var headerHeight by mutableIntStateOf(70)
        var ready by mutableStateOf(false)
        val scene = ImageComposeScene(680, 200) {
            CeilingsTheme(false) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(headerHeight.dp))
                    MeasurementDrawing(MeasurementGeometryView.Captured(geometry), Modifier.weight(1f),
                        interactive = true, showSideLegend = false, fillAvailableHeight = true,
                        retainCameraOnResize = true, verticalViewControls = true, cameraFitReady = ready)
                }
            }
        }
        try {
            repeat(5) { scene.frame() }
            val loadingScale = scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection].scale
            headerHeight = 20
            ready = true
            repeat(5) { scene.frame() }
            val fitted = scene.projectedDrawing()
            val settledScale = fitted.config[MeasurementDrawingSemantics.Projection].scale
            assertTrue(settledScale > loadingScale)
            assertTrue(settledScale * 3000 / fitted.size.height > 0.54)

            headerHeight = 40
            repeat(5) { scene.frame() }
            assertEquals(settledScale, scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection].scale, 0.0001)
        } finally { scene.close() }
    }

    @Test fun compactWorkspaceFitsARealTwoByThreeContourAndKeepsThePanelBoundary() {
        val source = draft().copy(outline = MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "2", "width" to "3")))
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, source))
        val scene = ImageComposeScene(360, 580) {
            CeilingsTheme(true) {
                MeasurementEditScreen(model, PaddingValues(12.dp, 24.dp, 12.dp, 24.dp), 24.dp,
                    onBack = {})
            }
        }
        fun tagged(tag: String) = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun fittedHeight(): Double {
            val canvas = scene.projectedDrawing()
            val projection = canvas.config[MeasurementDrawingSemantics.Projection]
            val a = projection.project(DrawingPoint(0.0, 0.0))
            val b = projection.project(DrawingPoint(2000.0, 3000.0))
            assertTrue(a.x >= 0 && a.y >= 0 && b.x <= canvas.size.width && b.y <= canvas.size.height)
            assertEquals(1.5, (b.y - a.y) / (b.x - a.x), 0.001)
            assertEquals(canvas.boundsInRoot.right, tagged("measurement-edit-zoom-rail").boundsInRoot.right, 1f)
            return b.y - a.y
        }
        try {
            repeat(4) { scene.frame() }
            val header = tagged("measurement-edit-header")
            assertEquals(24f, header.boundsInRoot.top)
            assertEquals(56, tagged("measurement-edit-back").size.height)
            assertEquals(tagged("measurement-edit-back").size, tagged("measurement-edit-save").size)
            assertNotNull(tagged("measurement-edit-save").config.getOrNull(SemanticsProperties.Disabled))
            assertNull(tagged("measurement-edit-save").config.getOrNull(SemanticsActions.OnClick))
            val contourHeight = fittedHeight()
            val canvasHeight = scene.projectedDrawing().size.height
            val normalPanel = tagged("measurement-edit-normal-panel")
            val viewportHeight = scene.projectedDrawing().config[MeasurementDrawingSemantics.ViewportBounds].height
            assertEquals(viewportHeight, normalPanel.size.height.toFloat(), 1f)
            assertEquals(tagged("measurement-edit-tab-row").boundsInRoot.bottom, scene.projectedDrawing().boundsInRoot.bottom)
            val fade = tagged("ceilings-workspace-normal-gradient").boundsInRoot
            assertTrue(fade.top < scene.projectedDrawing().boundsInRoot.top && fade.bottom > scene.projectedDrawing().boundsInRoot.top)
            val discs = scene.nodes(unmerged = true).filter { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-zoom-disc" }
            assertEquals(3, discs.size)
            assertTrue(discs.all { it.size.width == 36 && it.size.height == 36 })
            val targets = scene.nodes().filter { it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-zoom-target" }
            assertEquals(3, targets.size)
            assertTrue(targets.all { it.size.width == 48 && it.size.height == 48 && it.config.getOrNull(SemanticsActions.OnClick) != null })
            assertTrue(contourHeight > viewportHeight * 0.54, "The fitted contour should occupy most of the available height")
            scene.click("Вартість")
            repeat(4) { scene.frame() }
            assertEquals(canvasHeight, scene.projectedDrawing().size.height)
            scene.click("Контур")
            scene.frame()
            scene.click("На весь екран")
            repeat(4) { scene.frame() }
            assertTrue(scene.projectedDrawing().size.height > canvasHeight + 35)
            assertEquals(contourHeight, fittedHeight(), 0.1)
            scene.click("До заміру")
            repeat(4) { scene.frame() }
            assertEquals(contourHeight, fittedHeight(), 0.1)
        } finally { scene.close() }
    }

    @Test fun keyboardNextRevealsTheLastEditorFieldAndPreservesTheCamera() {
        var keyboardInset by mutableIntStateOf(0)
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(360, 640) {
            CeilingsTheme(false) {
                MeasurementEditScreen(model, onBack = {},
                    imeInsets = WindowInsets(0, 0, 0, keyboardInset))
            }
        }
        fun field(label: String) = scene.nodes().single {
            it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains(label)
        }
        fun assertFocusedAboveKeyboard(label: String) {
            repeat(60) {
                scene.frame()
                val input = field(label)
                val panel = scene.nodes().single {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "measurement-edit-context-panel"
                }
                val bounds = input.boundsInRoot
                if (input.config.getOrNull(SemanticsProperties.Focused) == true &&
                    bounds.height >= 48 && bounds.top >= panel.boundsInRoot.top - 1 &&
                    bounds.bottom <= 640 - keyboardInset + 1) return
                Thread.sleep(10)
            }
            fail("The focused field '$label' must scroll above the keyboard")
        }
        try {
            repeat(4) { scene.frame() }
            val scale = scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection].scale
            scene.click("Деталі заміру")
            scene.frame()
            assertTrue(field("Назва заміру").config[SemanticsActions.RequestFocus].action?.invoke() == true)
            keyboardInset = 228
            assertFocusedAboveKeyboard("Назва заміру")
            assertTrue(field("Назва заміру").config[SemanticsActions.OnImeAction].action?.invoke() == true)
            assertFocusedAboveKeyboard("Примітки")
            val notes = field("Примітки")
            assertTrue(notes.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Чернетка")) == true)
            scene.frame()
            assertEquals("Чернетка", model.state.value.draft.notes)
            assertEquals(scale, scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection].scale, 0.0001)
            keyboardInset = 0
            assertFocusedAboveKeyboard("Примітки")
        } finally { scene.close() }
    }

    @Test fun shortLandscapeKeyboardLeavesSpaceForTheFocusedFieldAndBothFabs() {
        var keyboardInset by mutableIntStateOf(0)
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(640, 320) {
            CeilingsTheme(false) {
                MeasurementEditScreen(model, PaddingValues(0.dp), onBack = {},
                    imeInsets = WindowInsets(0, 0, 0, keyboardInset))
            }
        }
        fun tagged(tag: String) = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun notes() = scene.nodes().single {
            it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains("Примітки")
        }
        try {
            repeat(4) { scene.frame() }
            val scale = scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection].scale
            scene.click("Деталі заміру")
            scene.frame()
            assertTrue(notes().config[SemanticsActions.RequestFocus].action?.invoke() == true)
            keyboardInset = 220
            repeat(25) { scene.frame(); Thread.sleep(10) }
            val field = notes()
            assertTrue(field.config.getOrNull(SemanticsProperties.Focused) == true)
            assertTrue(field.boundsInRoot.height >= 48f && field.boundsInRoot.bottom <= 101f)
            assertTrue(tagged("measurement-edit-back").boundsInRoot.right < field.boundsInRoot.left)
            assertTrue(tagged("measurement-edit-save").boundsInRoot.left > field.boundsInRoot.right)
            keyboardInset = 0
            repeat(4) { scene.frame() }
            assertEquals(scale, scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection].scale, 0.0001)
        } finally { scene.close() }
    }

    @Test fun narrowWorkspaceShowsTheCompleteValidationErrorWithoutResizingTheCanvasWhileReading() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val scene = ImageComposeScene(300, 580) {
            CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) }
        }
        fun canvas() = scene.projectedDrawing()
        try {
            scene.frame()
            val field = scene.nodes().single { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "5" }
            assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("")) == true)
            scene.frame()
            val boundsWithWarning = canvas().boundsInRoot
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
            assertEquals(boundsWithWarning, canvas().boundsInRoot)
        } finally { scene.close() }
    }

    @Test fun contourAcceptsBothKeyboardSeparatorsWithoutAShortcut() {
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, draft()))
        val image = ImageComposeScene(600, 900) {
            CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) }
        }
        try {
            image.frame()
            assertFalse(image.nodes().any { "Вставити десяткову кому" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            fun field() = image.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains("AB, м") }
            for ((typed, shown) in listOf(".5" to "0.5", ",5" to "0,5", "0.5" to "0.5", "0,5" to "0,5")) {
                assertTrue(field().config[SemanticsActions.SetText].action?.invoke(AnnotatedString(typed)) == true)
                image.frame()
                val value = (model.state.value.draft.outline as MeasurementOutlineDraft.Template).fields.getValue("length")
                assertEquals(shown, value)
                assertEquals(500L, measurementLengthMm(value, model.state.value.draft.precision))
            }
        } finally { image.close() }
    }

    @Test fun lShapeFieldsKeepTwoColumnsAndFocusSelectsTheMappedWall() {
        val source = draft().copy(outline = MeasurementOutlineDraft.Template("l_shaped",
            mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2")))
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, source))
        val scene = ImageComposeScene(361, 740) { CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) } }
        fun field(label: String) = scene.nodes().single {
            it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains(label)
        }
        fun tap(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse)
            scene.frame()
        }
        try {
            repeat(6) { scene.frame() }
            scene.click("Вписати креслення")
            val canvas = scene.projectedDrawing()
            val project = canvas.config[MeasurementDrawingSemantics.Projection]
            val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(
                assertNotNull(validateMeasurementCreate(source).geometry))))
            val wallStart = project.project(contour.points[0])
            val wallEnd = project.project(contour.points[1])
            val bounds = listOf("AB, м", "BC+DE, м", "EF, м", "BC, м").map { field(it).boundsInRoot }
            assertEquals(bounds[0].top, bounds[1].top, 1f)
            assertEquals(bounds[2].top, bounds[3].top, 1f)
            assertTrue(bounds[0].right < bounds[1].left)
            tap(Offset(canvas.boundsInRoot.left + ((wallStart.x + wallEnd.x) / 2).toFloat(),
                canvas.boundsInRoot.top + ((wallStart.y + wallEnd.y) / 2).toFloat()))
            assertEquals(0, scene.projectedDrawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertFalse(field("AB, м").config[SemanticsProperties.Focused])
            assertTrue(field("EF, м").config[SemanticsActions.RequestFocus].action?.invoke() == true)
            scene.frame()
            assertEquals(4, scene.projectedDrawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertTrue(field("EF, м").config[SemanticsProperties.Focused])
            assertEquals("2", field("EF, м").config[SemanticsProperties.EditableText].text)
            assertTrue(field("BC+DE, м").config[SemanticsActions.RequestFocus].action?.invoke() == true)
            scene.frame()
            assertEquals(5, scene.projectedDrawing().config[MeasurementDrawingSemantics.SelectedWall])
            tap(Offset(canvas.boundsInRoot.left + 4f, canvas.boundsInRoot.center.y))
            assertNull(scene.projectedDrawing().config.getOrNull(MeasurementDrawingSemantics.SelectedWall))
            assertEquals(project, scene.projectedDrawing().config[MeasurementDrawingSemantics.Projection])
            assertFalse(model.state.value.dirty)
        } finally { scene.close() }
    }

    @Test fun orthogonalTurnsStayAvailableUnderAdvancedAndUseHistory() {
        val source = draft().copy(outline = MeasurementOutlineDraft.Orthogonal(
            listOf("5", "4", "5", "4").map { MeasurementSideInput(it) }))
        val model = MeasurementEditModel(null, MeasurementEditDocument(3, 1, source))
        val scene = ImageComposeScene(600, 840) { CeilingsTheme(false) { MeasurementEditScreen(model, onBack = {}) } }
        try {
            scene.frame()
            assertFalse(scene.text().contains("Додати сторону"))
            val field = scene.nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null && it.textTree().contains("CD, м") }
            assertTrue(field.config[SemanticsActions.RequestFocus].action?.invoke() == true)
            assertEquals(2, scene.projectedDrawing().config[MeasurementDrawingSemantics.SelectedWall])
            assertFalse(model.state.value.dirty)
            scene.click("Додатково")
            scene.frame()
            assertTrue(scene.text().contains("Додати сторону"))
            val initial = (source.outline as MeasurementOutlineDraft.Orthogonal).sides.first().turn
            val replacement = if (initial == OrthogonalTurn.LEFT) OrthogonalTurn.RIGHT else OrthogonalTurn.LEFT
            scene.click(if (replacement == OrthogonalTurn.LEFT) "↶ 90°" else "↷ 90°")
            scene.frame()
            fun turn() = (model.state.value.draft.outline as MeasurementOutlineDraft.Orthogonal).sides.first().turn
            assertEquals(replacement, turn())
            scene.click("Скасувати дію")
            assertEquals(initial, turn())
            scene.frame()
            scene.click("Повторити")
            assertEquals(replacement, turn())
        } finally { scene.close() }
    }

    private fun ImageComposeScene.projectedDrawing(): SemanticsNode {
        // Layout reports a new Canvas size during placement. Publish its projection on the next frame.
        frame()
        return nodes().single { it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null }
    }

    private fun ImageComposeScene.finishSheetMotion() { repeat(22) { frame(); Thread.sleep(16) } }

    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(System.nanoTime()).close() }
    private fun ImageComposeScene.nodes(unmerged: Boolean = false): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.click(label: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && (it.textTree().contains(label) || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) }
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }
}
