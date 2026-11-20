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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
                assertTrue(maxOf(width / node.size.width, height / node.size.height) > 0.54,
                    "Contour ${width}×${height}px must use the settled ${node.size} canvas ($projection)")
                assertTrue(a.x >= 0 && a.y >= 0 && b.x <= node.size.width && b.y <= node.size.height,
                    "Projected contour $a–$b must fit ${node.size}")
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
                        compose.onAllNodesWithText("Завантаження збережених позначок…").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithTag("global-bottom-navigation").assertDoesNotExist()
            }
            fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))
            fun showAllContourDimensions() {
                if (compose.onAllNodesWithText("Усі розміри").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("Усі розміри").performScrollTo().performClick()
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
                capture("normal-contour")
                assertDrawingFit()
                val normal = canvas().boundsInRoot
                val initialCanvas = canvas()
                for (mode in listOf(ThemeMode.SYSTEM, ThemeMode.DARK, theme)) {
                    compose.runOnIdle { appliedTheme = mode }
                    assertEquals(initialCanvas.id, canvas().id)
                    assertEquals(normal, canvas().boundsInRoot)
                    assertEquals(initialCanvas.config[MeasurementDrawingSemantics.Projection],
                        canvas().config[MeasurementDrawingSemantics.Projection])
                }
                val normalPanel = panel.fetchSemanticsNode().boundsInRoot
                val panelHeight = compose.onNodeWithTag("measurement-edit-normal-panel").fetchSemanticsNode().size.height
                assertEquals(normal.height, panelHeight.toFloat(), 1f)
                compose.onNodeWithTag("ceilings-workspace-normal-gradient").assertExists()
                fun assertZoomTargets() {
                    val targets = compose.onAllNodesWithTag("measurement-edit-zoom-target", useUnmergedTree = true).fetchSemanticsNodes()
                    val discs = compose.onAllNodesWithTag("measurement-edit-zoom-disc", useUnmergedTree = true).fetchSemanticsNodes()
                    val density = compose.density.density
                    assertEquals(3, targets.size)
                    assertEquals(3, discs.size)
                    targets.forEach { assertEquals(48 * density, it.size.width.toFloat(), 1f) }
                    discs.forEach { assertEquals(36 * density, it.size.width.toFloat(), 1f) }
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
                showAllContourDimensions()
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
                    showAllContourDimensions()
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

                    val length = field("Сторона AB")
                    length.performScrollTo().performClick()
                    compose.waitUntil(15000) { imeVisible() }
                    length.assertIsFocused()
                    assertAboveKeyboard(length)
                    length.performImeAction()
                    val width = field("Сторона BC")
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
                    compose.waitUntil(15000) { compose.onAllNodesWithText("Збережено").fetchSemanticsNodes().isNotEmpty() }
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
