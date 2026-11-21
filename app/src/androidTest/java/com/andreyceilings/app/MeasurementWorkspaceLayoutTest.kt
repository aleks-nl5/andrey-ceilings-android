package com.andreyceilings.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.app.ui.theme.ThemeMode
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.theme.ceilingsColorScheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.*

class MeasurementWorkspaceLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun measurementsPortraitLight() = exercise(AppRoute.MEASUREMENTS, ThemeMode.LIGHT, false)
    @Test fun objectsPortraitDark() = exercise(AppRoute.OBJECTS, ThemeMode.DARK, false)
    @Test fun measurementsLandscapeDark() = exercise(AppRoute.MEASUREMENTS, ThemeMode.DARK, true)
    @Test fun objectsLandscapeLight() = exercise(AppRoute.OBJECTS, ThemeMode.LIGHT, true)

    @Test fun lShapeGridAndErrorLeaveTheHeaderAndCameraStable() {
        val args = InstrumentationRegistry.getArguments()
        val display = args.getString("p5aDisplay", "default")
        val screenshots = args.getString("p5aScreenshots") == "true"
        val context = compose.activity.applicationContext
        val databaseName = "sqldelight_test_p5a_l_shape.db"
        val key = ByteArray(32) { 49 }
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(databaseName)
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        try {
            CeilingsDatabase.openIsolatedForTesting(context, databaseName, key).use { database ->
                val project = ProjectRepository(database.database).createReturningId(null, "Квартира", createdAt = 1)
                val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "L-подібна стеля", "",
                    MeasurementOutlineDraft.Template("l_shaped", mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2")))
                val id = MeasurementCreateStore(database.database) { 2 }.create(draft)
                val start = AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(project, id).editMeasurement(project, id)
                var theme by mutableStateOf(ThemeMode.LIGHT)
                var mounted by mutableStateOf(true)
                compose.setContent {
                    if (mounted) CeilingsTheme(theme) {
                        CeilingsApp("Готово", database, {}, theme, {}, initialNavigation = start)
                    }
                }
                fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
                fun capture(stage: String) {
                    if (screenshots) {
                        compose.waitForIdle()
                        captureMeasurementWorkspaceScreenshot("$display-measurements-${theme.name.lowercase()}-portrait-l-shape-$stage")
                    }
                }
                try {
                    compose.waitUntil(15000) { compose.onAllNodes(hasSetTextAction() and hasText("AB, м")).fetchSemanticsNodes().isNotEmpty() }
                    val drawing = compose.onNode(SemanticsMatcher("drawing projection") {
                        it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null
                    })
                    val fields = listOf("AB, м", "BC+DE, м", "EF, м", "BC, м").map(::field)
                    val status = compose.onNodeWithTag("measurement-edit-status")
                    for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                        compose.runOnIdle { theme = mode }
                        val bounds = fields.map { it.fetchSemanticsNode().boundsInRoot }
                        assertEquals(bounds[0].top, bounds[1].top, 1f)
                        assertEquals(bounds[2].top, bounds[3].top, 1f)
                        assertTrue(bounds[0].right < bounds[1].left)
                        val canvas = drawing.fetchSemanticsNode().boundsInRoot
                        val header = compose.onNodeWithTag("measurement-edit-header").fetchSemanticsNode().boundsInRoot
                        assertRenderedChrome(drawing.fetchSemanticsNode(), mode)
                        capture("normal")
                        val projection = drawing.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
                        val wall = projection.project(DrawingPoint(2500.0, 0.0))
                        drawing.performTouchInput { click(Offset(wall.x.toFloat(), wall.y.toFloat())) }
                        assertEquals(0, drawing.fetchSemanticsNode().config[MeasurementDrawingSemantics.SelectedWall])
                        fields.first().assertIsNotFocused()
                        capture("canvas-wall-selected")
                        drawing.performTouchInput { click(Offset(width * 0.03f, centerY)) }
                        assertNull(drawing.fetchSemanticsNode().config.getOrNull(MeasurementDrawingSemantics.SelectedWall))
                        fields.first().performSemanticsAction(SemanticsActions.SetText) { set -> assertTrue(set(AnnotatedString(""))) }
                        status.assertExists()
                        assertEquals(header, compose.onNodeWithTag("measurement-edit-header").fetchSemanticsNode().boundsInRoot)
                        assertEquals(canvas, drawing.fetchSemanticsNode().boundsInRoot)
                        assertRenderedChrome(drawing.fetchSemanticsNode(), mode)
                        capture("validation")
                        status.performClick()
                        compose.onNodeWithTag("measurement-edit-status-details").assertExists()
                        compose.onNodeWithText("Закрити").performClick()
                        fields.first().performSemanticsAction(SemanticsActions.SetText) { set -> assertTrue(set(AnnotatedString("5"))) }
                    }
                    val projection = drawing.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
                    val wall = projection.project(DrawingPoint(2500.0, 0.0))
                    drawing.performTouchInput { click(Offset(wall.x.toFloat(), wall.y.toFloat())) }
                    assertEquals(0, drawing.fetchSemanticsNode().config[MeasurementDrawingSemantics.SelectedWall])
                    fields[2].performScrollTo().performClick().assertIsFocused()
                    assertEquals(4, drawing.fetchSemanticsNode().config[MeasurementDrawingSemantics.SelectedWall])
                    fields[2].assertIsFocused()
                    assertEquals("2", fields[2].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
                } finally {
                    compose.runOnIdle { mounted = false }
                    compose.waitForIdle()
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
            key.fill(0)
        }
    }

    private fun exercise(route: AppRoute, theme: ThemeMode, landscape: Boolean) {
        val configuration = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.runOnUiThread {
            compose.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == configuration }
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val context = compose.activity.applicationContext
        val name = "sqldelight_test_p5a_${route.name.lowercase()}_${configuration}.db"
        val key = ByteArray(32) { 44 }
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(name)
        val arguments = InstrumentationRegistry.getArguments()
        val capture = arguments.getString("p5aScreenshots") == "true"
        val display = arguments.getString("p5aDisplay", "default")
        val prefix = "$display-${route.name.lowercase()}-${theme.name.lowercase()}-${if (landscape) "landscape" else "portrait"}"
        fun capture(stage: String) {
            if (capture) {
                compose.waitForIdle()
                captureMeasurementWorkspaceScreenshot("$prefix-$stage")
            }
        }

        CeilingsDatabase.openIsolatedForTesting(context, name, key).use { database ->
            val project = ProjectRepository(database.database).createReturningId(null, "Квартира", createdAt = 1)
            val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Кухня 2×3", "",
                MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "2", "width" to "3")))
            val id = MeasurementCreateStore(database.database) { 2 }.create(draft)
            val start = AppNavigationState().selectTab(route).let {
                if (route == AppRoute.OBJECTS) it.openProject(project) else it
            }.openMeasurement(project, id)
            var mounted by mutableStateOf(true)
            var appliedTheme by mutableStateOf(theme)
            compose.setContent {
                if (mounted) CeilingsTheme(appliedTheme) {
                    CeilingsApp("Готово", database, {}, appliedTheme, {}, initialNavigation = start)
                }
            }
            val drawing = SemanticsMatcher("laid-out measurement drawing") {
                it.config.getOrNull(MeasurementDrawingSemantics.Projection) != null
            }
            val save = compose.onNodeWithTag("measurement-edit-save")
            fun canvas() = compose.onNode(drawing).fetchSemanticsNode()
            fun action(label: String) = compose.onNodeWithContentDescription(label)
            fun assertDrawingFit() {
                val node = canvas()
                val projection = node.config[MeasurementDrawingSemantics.Projection]
                val a = projection.project(DrawingPoint(0.0, 0.0))
                val b = projection.project(DrawingPoint(2000.0, 3000.0))
                val width = b.x - a.x
                val height = b.y - a.y
                assertEquals(1.5, height / width, 0.001)
                val viewport = node.config[MeasurementDrawingSemantics.ViewportBounds]
                assertTrue(maxOf(width / viewport.width, height / viewport.height) > 0.54,
                    "Contour ${width}×${height}px must use the settled $viewport viewport ($projection)")
                assertTrue(a.x >= 0 && a.y >= viewport.top && b.x <= viewport.right && b.y <= viewport.bottom,
                    "Projected contour $a–$b must fit $viewport")
            }
            fun contextTab(name: String) = compose.onNodeWithTag("measurement-edit-context-$name")
            val panel = compose.onNodeWithTag("measurement-edit-context-panel")
            fun scale() = canvas().config[MeasurementDrawingSemantics.Projection].scale
            fun enterEditor() {
                val edit = hasText("Редагувати") and hasClickAction()
                compose.waitUntil(15000) { compose.onAllNodes(edit).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("global-bottom-navigation").assertExists()
                compose.onNode(edit).performClick()
                compose.waitUntil(15000) {
                    compose.onAllNodes(drawing).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                            "Завантаження збережених позначок…")).fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithTag("global-bottom-navigation").assertDoesNotExist()
            }
            fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))
            fun showContourDetails() {
                if (compose.onAllNodes(hasSetTextAction() and hasText("Назва заміру", substring = true)).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithText("Деталі заміру").performScrollTo().performClick()
                }
            }
            try {
                enterEditor()
                save.assertIsNotEnabled()
                val header = compose.onNodeWithTag("measurement-edit-header").fetchSemanticsNode()
                val statusBar = compose.runOnIdle {
                    ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
                        .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
                }
                assertEquals(statusBar.toFloat(), header.boundsInRoot.top, 2f)
                val back = compose.onNodeWithTag("measurement-edit-back").fetchSemanticsNode()
                val saveNode = save.fetchSemanticsNode()
                assertEquals(back.size, saveNode.size)
                val touchSize = with(compose.density) { 56f * density }
                assertEquals(touchSize, back.size.width.toFloat(), 1f)
                assertTrue(back.positionInRoot.x < saveNode.positionInRoot.x)
                action("Скасувати дію").assertIsNotEnabled()
                action("Повторити").assertIsNotEnabled()
                assertRenderedChrome(canvas(), theme)
                capture("normal-contour")
                assertDrawingFit()
                val initialProjection = canvas().config[MeasurementDrawingSemantics.Projection]
                val wall = initialProjection.project(DrawingPoint(1000.0, 0.0))
                val drawingInput = compose.onNode(drawing)
                drawingInput.performTouchInput { click(Offset(wall.x.toFloat(), wall.y.toFloat())) }
                assertEquals(0, canvas().config[MeasurementDrawingSemantics.SelectedWall])
                drawingInput.performTouchInput { click(Offset(width * 0.05f, centerY)) }
                assertNull(canvas().config.getOrNull(MeasurementDrawingSemantics.SelectedWall))
                assertEquals(initialProjection, canvas().config[MeasurementDrawingSemantics.Projection])
                val normal = canvas().boundsInRoot
                val initialCanvas = canvas()
                for (mode in listOf(ThemeMode.SYSTEM, if (theme == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT, theme)) {
                    compose.runOnIdle { appliedTheme = mode }
                    assertEquals(initialCanvas.id, canvas().id)
                    assertEquals(normal, canvas().boundsInRoot)
                    assertEquals(initialCanvas.config[MeasurementDrawingSemantics.Projection],
                        canvas().config[MeasurementDrawingSemantics.Projection])
                    if (mode != ThemeMode.SYSTEM && mode != theme) {
                        assertRenderedChrome(canvas(), mode)
                        if (capture && !landscape) captureMeasurementWorkspaceScreenshot(
                            "$display-${route.name.lowercase()}-${mode.name.lowercase()}-portrait-normal-contour"
                        )
                    }
                }
                val normalPanel = panel.fetchSemanticsNode().boundsInRoot
                val panelHeight = compose.onNodeWithTag("measurement-edit-normal-panel").fetchSemanticsNode().size.height
                val normalViewport = canvas().config[MeasurementDrawingSemantics.ViewportBounds]
                assertEquals(normalViewport.height, panelHeight.toFloat(), 1f)
                val tabs = compose.onNodeWithTag("measurement-edit-tab-row").fetchSemanticsNode().boundsInRoot
                assertEquals(normal.bottom, tabs.bottom, 1f)
                drawingInput.performTouchInput {
                    val y = normalViewport.bottom + (height - normalViewport.bottom) / 2f
                    swipe(Offset(1f, y), Offset(1f, y - 45f * density))
                }
                assertEquals(initialProjection, canvas().config[MeasurementDrawingSemantics.Projection])
                compose.onNodeWithTag("ceilings-workspace-normal-gradient").assertExists()
                fun assertZoomTargets() {
                    val targets = compose.onAllNodesWithTag("measurement-edit-zoom-target", useUnmergedTree = true).fetchSemanticsNodes()
                    val discs = compose.onAllNodesWithTag("measurement-edit-zoom-disc", useUnmergedTree = true).fetchSemanticsNodes()
                    val density = compose.density.density
                    assertEquals(3, targets.size)
                    assertEquals(3, discs.size)
                    targets.forEach {
                        assertEquals(48 * density, it.size.width.toFloat(), 1f)
                        assertEquals(48 * density, it.size.height.toFloat(), 1f)
                    }
                    discs.forEach {
                        assertEquals(36 * density, it.size.width.toFloat(), 1f)
                        assertEquals(36 * density, it.size.height.toFloat(), 1f)
                    }
                }
                assertZoomTargets()
                val rail = compose.onNodeWithTag("measurement-edit-zoom-rail").fetchSemanticsNode()
                assertEquals(canvas().boundsInRoot.right, rail.boundsInRoot.right, 1f)

                contextTab("cost").performClick()
                assertEquals(normalPanel, panel.fetchSemanticsNode().boundsInRoot)
                assertEquals(normal, canvas().boundsInRoot)
                capture("normal-cost")
                contextTab("elements").performClick()
                assertEquals(normalPanel, panel.fetchSemanticsNode().boundsInRoot)
                capture("normal-elements")
                contextTab("cost").performClick()
                val unzoomed = scale()
                action("На весь екран").performClick()
                compose.onNodeWithTag("global-bottom-navigation").assertDoesNotExist()
                assertTrue(canvas().size.height > normal.height)
                assertEquals(unzoomed, scale(), 0.0001)
                contextTab("cost").assertDoesNotExist()
                compose.onNodeWithTag("ceilings-workspace-normal-gradient").assertDoesNotExist()
                assertZoomTargets()
                panel.assertDoesNotExist()
                capture("fullscreen")
                contextTab("contour").performClick()
                val fullscreenBounds = canvas().boundsInRoot
                capture("fullscreen-contour")
                showContourDetails()
                val compactHeight = panel.fetchSemanticsNode().size.height
                val handle = compose.onNodeWithTag("measurement-edit-sheet-handle")
                val handleBounds = handle.fetchSemanticsNode().boundsInRoot
                assertEquals(48 * compose.density.density, handleBounds.width, 1f)
                assertEquals(fullscreenBounds.center.x, handleBounds.center.x, 2f)
                handle.performTouchInput { swipeUp(durationMillis = 220,
                    startY = centerY, endY = centerY - 85f * density) }
                assertTrue(panel.fetchSemanticsNode().size.height > compactHeight)
                assertEquals(fullscreenBounds, canvas().boundsInRoot)
                assertEquals(unzoomed, scale(), 0.0001)
                capture("fullscreen-expanded")
                contextTab("elements").performClick()
                capture("fullscreen-elements")
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                panel.assertDoesNotExist()
                action("Згорнути").assertExists()
                val beforePan = canvas().config[MeasurementDrawingSemantics.Viewport]
                compose.onNode(drawing).performTouchInput {
                    val x = right - 8f * density
                    val y = bottom - 18f * density
                    swipe(Offset(x, y), Offset(x - 70f * density, y), durationMillis = 200)
                }
                assertNotEquals(beforePan, canvas().config[MeasurementDrawingSemantics.Viewport])
                panel.assertDoesNotExist()
                action("Збільшити масштаб").performClick()
                val zoomed = scale()
                capture("fullscreen-zoom")
                compose.onNodeWithTag("measurement-edit-back").performClick()
                action("На весь екран").assertExists()
                assertEquals(zoomed, scale(), 0.0001)
                compose.onNodeWithText("Розрахунок за останнім збереженим заміром").assertExists()
                action("Зменшити масштаб").performClick()
                action("Вписати креслення").performClick()

                if (capture && !landscape) {
                    val scaleBeforeKeyboard = scale()
                    compose.onNodeWithTag("measurement-edit-context-contour").performClick()
                    showContourDetails()
                    fun imeVisible() = compose.runOnIdle {
                        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    fun assertAboveKeyboard(input: SemanticsNodeInteraction) {
                        val bounds = input.fetchSemanticsNode().boundsInRoot
                        val panel = compose.onNodeWithTag("measurement-edit-context-panel").fetchSemanticsNode().boundsInRoot
                        val keyboardTop = compose.runOnIdle {
                            val decor = compose.activity.window.decorView
                            val ime = ViewCompat.getRootWindowInsets(decor)!!
                                .getInsets(WindowInsetsCompat.Type.ime()).bottom
                            decor.height - ime
                        }
                        assertTrue(bounds.height >= 48 * compose.density.density,
                            "The focused field must have room to show its input")
                        assertTrue(bounds.top >= panel.top - 2 && bounds.bottom <= minOf(panel.bottom, keyboardTop.toFloat()) + 2)
                    }

                    val length = field("AB, м")
                    length.performScrollTo().performClick()
                    compose.waitUntil(15000) { imeVisible() }
                    length.assertIsFocused()
                    assertAboveKeyboard(length)
                    length.performImeAction()
                    val width = field("BC, м")
                    width.assertIsFocused()
                    assertAboveKeyboard(width)
                    capture("normal-ime-next")

                    val nameField = field("Назва заміру")
                    nameField.performScrollTo().performClick().performImeAction()
                    val notes = field("Примітки")
                    notes.assertIsFocused()
                    notes.performTextInput("Перевірка клавіатури")
                    notes.assertTextContains("Перевірка клавіатури")
                    assertAboveKeyboard(notes)
                    assertEquals(scaleBeforeKeyboard, scale(), 0.0001)
                    save.assertIsDisplayed()
                    capture("normal-ime")

                    fun hideKeyboard() {
                        compose.runOnIdle {
                            val window = compose.activity.window
                            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                        }
                        compose.waitUntil(15000) { !imeVisible() }
                    }
                    hideKeyboard()
                    notes.assertIsFocused()
                    assertEquals(scaleBeforeKeyboard, scale(), 0.0001)

                    action("На весь екран").performClick()
                    contextTab("contour").performClick()
                    notes.performScrollTo().performClick()
                    compose.waitUntil(15000) { imeVisible() }
                    assertAboveKeyboard(notes)
                    capture("fullscreen-ime")
                    hideKeyboard()
                    action("Згорнути").performClick()
                }

                contextTab("contour").performClick()
                if (compose.onAllNodes(hasSetTextAction() and hasText("Назва заміру", substring = true)).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithText("Деталі заміру").performScrollTo().performClick()
                }
                val nameField = field("Назва заміру")
                val notes = field("Примітки")
                fun saveInPlace() {
                    save.assertIsEnabled().performClick()
                    compose.waitUntil(15000) {
                        compose.onAllNodes(hasTestTag("measurement-edit-save") and
                            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Збережено")).fetchSemanticsNodes().isNotEmpty()
                    }
                    action("На весь екран").assertExists()
                    action("Скасувати дію").assertIsNotEnabled()
                    save.assertIsNotEnabled()
                }
                val scaleBeforeSave = scale()
                nameField.performScrollTo().performTextReplacement("Кухня після збереження")
                saveInPlace()
                assertEquals(scaleBeforeSave, scale(), 0.0001)
                capture("normal-save")
                notes.performScrollTo().performTextReplacement("Другий запис")
                saveInPlace()
                val saved = MeasurementRepository(database.database).find(id)!!
                assertEquals("Кухня після збереження", saved.name)
                assertEquals("Другий запис", saved.notes)
                contextTab("cost").performClick()
                compose.onNodeWithText("Переглянути збережений розрахунок у картці заміру").performScrollTo().performClick()
                enterEditor()
                compose.onNodeWithText("Деталі заміру").performScrollTo().performClick()
                field("Назва заміру").assertTextContains("Кухня після збереження")
                field("Примітки").assertTextContains("Другий запис")
                field("Примітки").performScrollTo().performTextReplacement("Збережено перед виходом")
                compose.onNodeWithTag("measurement-edit-back").performClick()
                compose.onNodeWithText("Продовжити редагування").performClick()
                action("На весь екран").assertExists()
                compose.onNodeWithTag("measurement-edit-back").performClick()
                compose.onNodeWithText("Зберегти й вийти").performClick()
                compose.waitUntil(15000) {
                    compose.onAllNodes(hasText("Редагувати") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                }
                save.assertDoesNotExist()
                assertEquals("Збережено перед виходом", MeasurementRepository(database.database).find(id)!!.notes)
            } finally {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            }
        }
        context.deleteDatabase(name)
        key.fill(0)
    }

    private fun assertRenderedChrome(canvas: SemanticsNode, theme: ThemeMode) {
        // Production Android window pixels include shared chrome, the actual drawing and the app theme.
        compose.waitForIdle()
        val header = compose.onNodeWithTag("measurement-edit-header").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("measurement-edit-tab-row").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("measurement-edit-panel-surface").fetchSemanticsNode().boundsInRoot
        val gradient = compose.onNodeWithTag("ceilings-workspace-normal-gradient").fetchSemanticsNode().boundsInRoot
        val drawing = canvas.boundsInRoot
        val projection = canvas.config[MeasurementDrawingSemantics.Projection]
        val density = compose.density.density
        val dark = theme == ThemeMode.DARK
        val colors = ceilingsColorScheme(dark)
        var step = 100.0 * projection.scale
        while (step < 16f * density) step *= 2
        val x0 = drawing.left + projection.offsetX
        val y0 = drawing.top + projection.offsetY
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        fun difference(a: Int, b: Int) = maxOf(
            abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
        )
        fun assertGrid(region: Rect) {
            // Compare pixels on a projected grid line with adjacent clear pixels at the
            // SAME y. The rendered top fade is theme- and layout-dependent: assuming
            // an exact composited background RGB produced false failures in dark
            // landscape despite a visible grid. Two distinct lines must remain visible.
            val inset = 3f * density
            val xMin = region.left + region.width * 0.29f
            val xMax = region.right - region.width * 0.29f
            val lines = generateSequence(ceil((xMin - x0) / step)) { it + 1 }
                .map { x0 + it * step }.takeWhile { it + inset < xMax }.toList()
            val matched = (ceil(region.top).toInt() until region.bottom.toInt()).any { y ->
                val gy = (y - y0) / step
                if (abs(gy - kotlin.math.round(gy)) * step <= inset / 2) false else {
                    lines.count { x ->
                        val gap = bitmap.getPixel((x + inset).roundToInt(), y)
                        (-1..1).any { dx ->
                            difference(bitmap.getPixel(x.roundToInt() + dx, y), gap) >= 3
                        }
                    } >= 2
                }
            }
            if (!matched) {
                // Preserve the actual rendered pixels for a failed visual check.
                // A black-box assertion alone cannot distinguish an opaque overlay,
                // a projected-coordinate mismatch and a low-contrast dark grid.
                val diagnostic = "grid-failure-${theme.name.lowercase()}-y${region.top.roundToInt()}"
                val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "p5a-workspace")
                check(directory.isDirectory || directory.mkdirs())
                File(directory, "$diagnostic.png").outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            }
            assertTrue(matched, "Expected at least two visible composited grid lines in $region with $theme")
        }
        try {
            assertGrid(Rect(drawing.left, header.bottom + 2 * density,
                drawing.right, header.bottom + 16 * density))
            assertGrid(Rect(tabs.left, tabs.top + density,
                tabs.right, tabs.top + 5 * density))
            val y = y0 + ceil((panel.top + 24 * density - y0) / step) * step
            val x = (panel.left + 5 * density).roundToInt()
            assertTrue(y < panel.bottom)
            assertTrue(difference(colors.background.toArgb(), bitmap.getPixel(x, y.roundToInt())) <= 3,
                "The content panel must paint its opaque background")
        } finally {
            bitmap.recycle()
        }
    }

}

internal fun captureMeasurementWorkspaceScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Unable to capture $name" }
    try {
        val directory = File(instrumentation.targetContext.cacheDir, "p5a-workspace")
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create $directory" }
        File(directory, "$name.png").outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode $name.png" }
        }
    } finally {
        bitmap.recycle()
    }
}
