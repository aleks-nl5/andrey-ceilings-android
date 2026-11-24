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
import androidx.compose.ui.semantics.getOrNull
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
import kotlin.math.abs
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
                    val status = compose.onNodeWithTag("measurement-edit-save-block-reason")
                    for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                        compose.runOnIdle { theme = mode }
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
                        compose.onNodeWithTag("measurement-live-area").assertIsDisplayed()
                            .assertContentDescriptionEquals("Площа недоступна: — м²")
                        compose.onNodeWithTag("measurement-live-perimeter").assertIsDisplayed()
                            .assertContentDescriptionEquals("Периметр недоступний: — м")
                        drawing.assertExists()
                        capture("validation")
                        status.performClick()
                        compose.onNodeWithText("Введіть відсутні розміри.").assertIsDisplayed().performClick()
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
                val geometry = assertNotNull(validateMeasurementCreate(draft).geometry)
                val vertices = geometry.vertices.map { projection.project(DrawingPoint(it.xMm.toDouble(), it.yMm.toDouble())) }
                val viewport = node.config[MeasurementDrawingSemantics.ViewportBounds]
                assertTrue(viewport.width > 0 && viewport.height > 0)
                assertTrue(vertices.all { it.x in viewport.left.toDouble()..viewport.right.toDouble() &&
                    it.y in viewport.top.toDouble()..viewport.bottom.toDouble() },
                    "Projected contour must fit the visible viewport")
                val tabs = compose.onAllNodesWithTag("measurement-edit-tab-row").fetchSemanticsNodes().singleOrNull()
                if (tabs != null) {
                    val bottom = vertices.maxOf { it.y } + node.boundsInRoot.top
                    assertTrue(bottom < tabs.boundsInRoot.top, "Contour must not overlap the context tabs")
                }
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
                compose.onNodeWithText("Деталі заміру").assertDoesNotExist()
                field("Назва заміру").performScrollTo()
            }
            try {
                enterEditor()
                compose.onNodeWithTag("measurement-shape-picker").performScrollTo().performClick()
                compose.onNodeWithTag("measurement-shape-dialog").assertIsDisplayed()
                compose.onNodeWithTag("measurement-shape-option-rectangle").assertExists()
                compose.onNodeWithTag("measurement-shape-option-l_shaped").assertExists()
                compose.onNodeWithTag("measurement-shape-option-polygon").assertExists()
                compose.onNodeWithText("Скасувати").performClick()
                compose.onNodeWithTag("measurement-shape-dialog").assertDoesNotExist()
                save.assertIsNotEnabled()
                compose.onNodeWithTag("measurement-edit-back").assertIsDisplayed()
                action("Скасувати дію").assertIsNotEnabled()
                action("Повторити").assertIsNotEnabled()
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
                    assertEquals(initialCanvas.config[MeasurementDrawingSemantics.Projection],
                        canvas().config[MeasurementDrawingSemantics.Projection])
                    if (mode != ThemeMode.SYSTEM && mode != theme) {
                        if (capture && !landscape) captureMeasurementWorkspaceScreenshot(
                            "$display-${route.name.lowercase()}-${mode.name.lowercase()}-portrait-normal-contour"
                        )
                    }
                }
                // Gestures in the separate bottom panel may scroll the panel,
                // but must never change the drawing camera.
                panel.performTouchInput {
                    swipe(Offset(centerX, centerY), Offset(centerX, centerY - 45f * density))
                }
                assertEquals(initialProjection, canvas().config[MeasurementDrawingSemantics.Projection])
                action("Повернути креслення на 90° за годинниковою стрілкою").assertIsEnabled()

                contextTab("cost").performClick()
                capture("normal-cost")
                contextTab("elements").performClick()
                capture("normal-elements")
                contextTab("cost").performClick()
                action("На весь екран").performClick()
                compose.onNodeWithTag("global-bottom-navigation").assertDoesNotExist()
                assertTrue(canvas().size.height > normal.height)
                compose.waitForIdle()
                assertDrawingFit()
                val fullscreenFitScale = scale()
                contextTab("cost").assertDoesNotExist()
                action("Повернути креслення на 90° за годинниковою стрілкою").assertIsEnabled()
                panel.assertDoesNotExist()
                capture("fullscreen")
                contextTab("contour").performClick()
                val fullscreenBounds = canvas().boundsInRoot
                capture("fullscreen-contour")
                showContourDetails()
                val compactHeight = panel.fetchSemanticsNode().size.height
                val handle = compose.onNodeWithTag("measurement-edit-sheet-handle")
                handle.performTouchInput { swipeUp(durationMillis = 220,
                    startY = centerY, endY = centerY - 85f * density) }
                assertTrue(panel.fetchSemanticsNode().size.height > compactHeight)
                assertEquals(fullscreenBounds, canvas().boundsInRoot)
                assertEquals(fullscreenFitScale, scale(), 0.0001)
                capture("fullscreen-expanded")
                contextTab("elements").performClick()
                capture("fullscreen-elements")
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                panel.assertDoesNotExist()
                action("Згорнути").assertExists()
                val beforePan = canvas().config[MeasurementDrawingSemantics.Viewport]
                val panStart = canvas().config[MeasurementDrawingSemantics.ViewportBounds].center
                compose.onNode(drawing).performTouchInput {
                    swipe(panStart, panStart - Offset(70f * density, 0f), durationMillis = 200)
                }
                assertNotEquals(beforePan, canvas().config[MeasurementDrawingSemantics.Viewport])
                panel.assertDoesNotExist()
                action("Збільшити масштаб").performClick()
                val zoomed = scale()
                capture("fullscreen-zoom")
                compose.onNodeWithTag("measurement-edit-back").performClick()
                action("На весь екран").assertExists()
                compose.waitForIdle()
                assertDrawingFit()
                assertTrue(kotlin.math.abs(zoomed - scale()) > 0.0001,
                    "Leaving fullscreen must refit after manual zoom")
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

                    // Emulator screenshot runs can retain hardware-keyboard mode
                    // even with show_ime_with_hard_keyboard enabled. The contract
                    // under test here is IME-aware layout/viewport, not whether
                    // the emulator auto-opens the IME on a synthetic click.
                    // Focus must be real, the keyboard must actually appear,
                    // and all bounds/Next-action assertions remain mandatory.
                    fun requireVisibleKeyboard(input: SemanticsNodeInteraction) {
                        input.assertIsFocused()
                        compose.runOnUiThread {
                            val window = compose.activity.window
                            WindowInsetsControllerCompat(window, window.decorView)
                                .show(WindowInsetsCompat.Type.ime())
                        }
                        compose.waitUntil(15000) { imeVisible() }
                    }

                    val length = field("AB, м")
                    length.performScrollTo().performClick()
                    requireVisibleKeyboard(length)
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
                    requireVisibleKeyboard(notes)
                    assertAboveKeyboard(notes)
                    capture("fullscreen-ime")
                    hideKeyboard()
                    action("Згорнути").performClick()
                }

                contextTab("contour").performClick()
                showContourDetails()
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
                showContourDetails()
                field("Назва заміру").assertTextContains("Кухня після збереження")
                field("Примітки").assertTextContains("Другий запис")
                field("Примітки").performScrollTo().performTextReplacement("Збережено перед виходом")
                compose.onNodeWithTag("measurement-edit-back").performClick()
                compose.onNodeWithText("Продовжити").performClick()
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
