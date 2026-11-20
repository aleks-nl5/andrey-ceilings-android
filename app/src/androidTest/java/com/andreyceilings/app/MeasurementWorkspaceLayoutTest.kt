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
        val capture = InstrumentationRegistry.getArguments().getString("p5aScreenshots") == "true"
        val prefix = "${route.name.lowercase()}-${theme.name.lowercase()}-${if (landscape) "landscape" else "portrait"}"
        fun capture(stage: String) { if (capture) screenshot("$prefix-$stage") }

        CeilingsDatabase.openIsolatedForTesting(context, name, key).use { database ->
            val project = ProjectRepository(database.database).createReturningId(null, "Квартира", createdAt = 1)
            val draft = MeasurementCreateDraft(project, MeasurementPrecision.ONE_MILLIMETER, "Кухня 2×3", "",
                MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "2", "width" to "3")))
            val id = MeasurementCreateStore(database.database) { 2 }.create(draft)
            val start = AppNavigationState().selectTab(route).let {
                if (route == AppRoute.OBJECTS) it.openProject(project) else it
            }.openMeasurement(project, id)
            var mounted by mutableStateOf(true)
            compose.setContent {
                if (mounted) CeilingsTheme(theme) {
                    CeilingsApp("Готово", database, {}, theme, {}, initialNavigation = start)
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
            try {
                compose.waitUntil(15000) { compose.onAllNodes(hasText("Редагувати") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("global-bottom-navigation").assertExists()
                compose.onNode(hasText("Редагувати") and hasClickAction()).performClick()
                compose.waitUntil(15000) {
                    compose.onAllNodes(hasTestTag("measurement-edit-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(drawing).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("global-bottom-navigation").assertDoesNotExist()
                save.assertHasClickAction()
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
                val normal = canvas().size.height
                val rail = compose.onNodeWithTag("measurement-edit-zoom-rail").fetchSemanticsNode()
                assertTrue(canvas().boundsInRoot.right <= rail.boundsInRoot.left + 1)

                compose.onNodeWithTag("measurement-edit-context-cost").performClick()
                val cost = compose.onNodeWithTag("measurement-edit-context-panel").fetchSemanticsNode()
                assertTrue(cost.size.height <= 120 * compose.density.density)
                assertTrue(canvas().size.height > normal)
                capture("normal-cost")
                compose.onNodeWithTag("measurement-edit-context-elements").performClick()
                capture("normal-elements")
                compose.onNodeWithTag("measurement-edit-context-contour").performClick()
                action("На весь екран").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("global-bottom-navigation").assertDoesNotExist()
                assertTrue(canvas().size.height > normal)
                assertDrawingFit()
                capture("fullscreen-contour")
                compose.onNodeWithTag("measurement-edit-context-cost").performClick()
                capture("fullscreen-cost")
                compose.onNodeWithTag("measurement-edit-context-elements").performClick()
                capture("fullscreen-elements")
                compose.onNodeWithTag("measurement-edit-back").performClick()
                action("На весь екран").assertExists()

                action("Збільшити масштаб").performClick()
                val zoomed = canvas().config[MeasurementDrawingSemantics.Projection].scale
                action("На весь екран").performClick()
                assertEquals(zoomed, canvas().config[MeasurementDrawingSemantics.Projection].scale, 0.0001)
                action("Згорнути").performClick()
                action("Зменшити масштаб").performClick()
                action("Вписати креслення").performClick()

                if (capture && !landscape) {
                    val scale = canvas().config[MeasurementDrawingSemantics.Projection].scale
                    compose.onNodeWithTag("measurement-edit-context-contour").performClick()
                    compose.onNodeWithText("Усі розміри").performScrollTo().performClick()
                    fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))
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
                    assertEquals(scale, canvas().config[MeasurementDrawingSemantics.Projection].scale, 0.0001)
                    save.assertIsDisplayed()
                    capture("normal-ime")

                    compose.runOnIdle {
                        val window = compose.activity.window
                        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                    }
                    compose.waitUntil(15000) { !imeVisible() }
                    notes.assertIsFocused()
                    assertEquals(scale, canvas().config[MeasurementDrawingSemantics.Projection].scale, 0.0001)
                }
            } finally {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            }
        }
        context.deleteDatabase(name)
        key.fill(0)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
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
}
