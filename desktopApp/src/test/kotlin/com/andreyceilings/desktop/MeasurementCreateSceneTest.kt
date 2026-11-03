package com.andreyceilings.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MeasurementCreateSceneTest {
    @Test fun creationAndReadonlyTotalsFormatEveryCapturedPrecisionWithoutChangingGeometry() {
        val expected = listOf("20,40 м² · Периметр: 18,2 м", "20,4000 м² · Периметр: 18,20 м",
            "20,400000 м² · Периметр: 18,200 м", "20,400000 м² · Периметр: 18,200 м")
        MeasurementPrecision.entries.forEachIndexed { i, precision ->
            val draft = draft("rectangle").copy(precision = precision,
                outline = MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5,10", "width" to "4,00")))
            val geometry = assertNotNull(validateMeasurementCreate(draft).geometry)
            val wireBefore = MeasurementGeometryCodec.write(geometry)
            for (readonly in listOf(false, true)) {
                val model = MeasurementCreateModel(null, draft)
                val scene = ImageComposeScene(500, 1000) {
                    CeilingsTheme(false) {
                        if (readonly) MeasurementDetailsScreen(MeasurementDetailsState.Loaded(MeasurementDetailsData(
                            3, 7, "Кухня", "Об'єкт", "Адреса", "rectangle",
                            20.400000000000002, 18.200000000000003, "", MeasurementGeometryView.Captured(geometry))), onBack = {})
                        else MeasurementCreateScreen(model,
                            MeasurementCreateParent.Active("Об'єкт"), onBack = {}, onSaved = {})
                    }
                }
                try {
                    scene.render().close()
                    assertTrue(scene.textContent().contains("Площа: ${expected[i]}"), scene.textContent())
                    assertEquals(wireBefore, MeasurementGeometryCodec.write(geometry))
                } finally { scene.close() }
            }
        }
    }

    // Only constructing the synthetic key uses this pinned Compose test API;
    // production receives the ordinary KeyEvent supplied by Window.
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun slowSaveConsumesEscapeAndBlocksBackTabsAndCancelUntilResultIsDisplayed(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Scene parent", createdAt = 1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val store = MeasurementCreateStore(db) {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                2L
            }
            val model = MeasurementCreateModel(store, draft("rectangle").copy(projectId = project))
            val guard = MeasurementCreateNavigationGuard()
            val navigation = mutableStateOf(AppNavigationState().selectTab(AppRoute.OBJECTS)
                .openProject(project).createMeasurement(project))
            val scene = ImageComposeScene(360, 1000) {
                CeilingsTheme(false) {
                    if (navigation.value.measurementCreate != null) MeasurementCreateScreen(model,
                        MeasurementCreateParent.Active("Scene parent"), onBack = {
                            navigation.value = guard.transition(navigation.value, navigation.value.back())
                        }, onSaved = { id -> navigation.value = guard.transition(navigation.value,
                            navigation.value.measurementCreated(project, id)) }, navigationGuard = guard)
                    else androidx.compose.material3.Text("Відкрито замір ${navigation.value.measurementDetails?.measurementId}")
                }
            }
            try {
                scene.render().close()
                val editor = navigation.value
                assertTrue(scene.clickText("Зберегти"))
                // No frame/effect has run yet: Save must already own navigation.
                assertTrue(guard.blocksExit(editor))
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                navigation.value = guard.transition(navigation.value, navigation.value.back())
                val escape = androidx.compose.ui.input.key.KeyEvent(
                    androidx.compose.ui.input.key.Key.Escape, androidx.compose.ui.input.key.KeyEventType.KeyDown)
                var escaped = false
                assertTrue(handleNavigationKeyEvent(escape, navigation.value, AppRoute.entries.toSet(), guard) { escaped = true })
                assertFalse(escaped)
                navigation.value = guard.transition(navigation.value, navigation.value.selectTab(AppRoute.PRICES))
                assertEquals(editor, navigation.value)
                assertFalse(model.cancel())
                model.save()
                Snapshot.sendApplyNotifications()
                scene.render(16_000_000).close()
                assertTrue(scene.textContent().contains("Збереження триває"))
                assertTrue(scene.textContent().contains("Новий замір"))
                release.countDown()
                val id = withTimeout(10_000) { model.state.first { it.savedId != null }.savedId!! }
                repeat(4) { i -> Snapshot.sendApplyNotifications(); scene.render((i + 2) * 16_000_000L).close() }
                assertEquals(MeasurementDetails(project, id), navigation.value.measurementDetails)
                assertTrue(scene.textContent().contains("Відкрито замір $id"))
                assertEquals(id, MeasurementRepository(db).forProject(project).single().id)
                assertFalse(guard.blocksExit(navigation.value))
                assertTrue(handleNavigationKeyEvent(escape, navigation.value, AppRoute.entries.toSet(), guard) {
                    navigation.value = guard.transition(navigation.value, it)
                })
                assertEquals(project, navigation.value.projectDetails?.projectId)
                assertNull(navigation.value.measurementDetails)
            } finally { release.countDown(); scene.close() }
        }
    }

    private fun draft(shape: String): MeasurementCreateDraft {
        val outline = when (shape) {
            "rectangle" -> MeasurementOutlineDraft.Template(shape, mapOf("length" to "5", "width" to "4"))
            "l_shaped" -> MeasurementOutlineDraft.Template(shape, mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2"))
            else -> MeasurementOutlineDraft.Orthogonal(listOf("5", "4", "5", "4").map { MeasurementSideInput(it) })
        }
        return MeasurementCreateDraft(7, MeasurementPrecision.ONE_CENTIMETER, "Кухня", outline = outline)
    }

    @Test fun editorRendersAllShapesThemesResizeAndWorkingCancel() {
        for (shape in listOf("rectangle", "l_shaped", "polygon")) for (dark in listOf(false, true)) {
            val model = MeasurementCreateModel(null, draft(shape))
            var backs = 0
            var saves = 0
            val scene = ImageComposeScene(360, 1000) {
                CeilingsTheme(dark) {
                    MeasurementCreateScreen(model, MeasurementCreateParent.Active("Обраний об'єкт"),
                        onBack = { backs++ }, onSaved = { saves++ })
                }
            }
            try {
                scene.render().close()
                assertTrue(scene.textContent().contains("Обраний об'єкт"))
                // Inline dimensions are now length-only; vertex/side names belong to editor inputs.
                assertTrue(scene.textContent().contains("5 м"), "Dimension legend must remain visible")
                assertFalse(scene.textContent().contains("AB:"), "Drawing must not show side-name prefixes")
                assertTrue(scene.textContent().contains("Площа:"))
                assertFalse(scene.textContent().contains("Сторона 1"))
                scene.constraints = Constraints.fixed(240, 700)
                scene.render(16_000_000).close()
                scene.constraints = Constraints.fixed(900, 1000)
                scene.render(32_000_000).close()
                assertTrue(scene.clickText("Скасувати"))
                assertTrue(model.state.value.cancelled)
                assertEquals(1, backs)
                assertEquals(0, saves)
            } finally { scene.close() }
        }
    }

    @Test fun formRegistryRestoresTextAndPrecisionButNeverStartsSave() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Restore parent", createdAt = 1)
            val store = MeasurementCreateStore(db) { 2 }
            val activeStore = mutableStateOf<MeasurementCreateStore?>(store)
            var navigation = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).createMeasurement(project)
            val registry = SaveableStateRegistry(null) { true }
            fun scene(registry: SaveableStateRegistry, precision: MeasurementPrecision) = ImageComposeScene(360, 1000) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    CeilingsTheme(false) {
                        MeasurementCreateScreen(project, precision, activeStore.value, PaddingValues(16.dp), 0.dp,
                            onBack = { navigation = navigation.back() },
                            onSaved = { fail("Restoration must never save") })
                    }
                }
            }
            val first = scene(registry, MeasurementPrecision.FIVE_MILLIMETERS)
            val saved: Map<String, List<Any?>>
            try {
                first.render().close()
                val field = first.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(field.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("Відновлена кухня")) == true)
                Snapshot.sendApplyNotifications()
                first.render(16_000_000).close()
                saved = registry.performSave()
            } finally { first.close() }
            navigation = AppNavigationState.restore(navigation.snapshot())
            activeStore.value = null // Android's transient startup state after Activity recreation.
            val restored = scene(SaveableStateRegistry(saved) { true }, MeasurementPrecision.TEN_CENTIMETERS)
            try {
                restored.render().close()
                assertTrue(restored.textContent().contains("база недоступна"))
                assertFalse(restored.nodes().any { it.config.getOrNull(SemanticsActions.SetText) != null })
                activeStore.value = store
                Snapshot.sendApplyNotifications()
                restored.render(16_000_000).close()
                assertTrue(restored.textContent().contains("5 мм"))
                assertTrue(restored.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Відновлена кухня" })
                assertFalse(restored.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { v -> v.text == "Збереження…" } })
                assertTrue(restored.clickText("Скасувати"))
                assertNull(navigation.measurementCreate)
                assertEquals(ProjectDetails(project), navigation.projectDetails)
                assertTrue(MeasurementRepository(db).all().isEmpty(), "Restoring the form never writes")
            } finally { restored.close() }
        }
    }

    @Test fun invalidFormAndUnavailableParentExplainWhySaveIsDisabled() {
        val model = MeasurementCreateModel(null, MeasurementCreateDraft(7, MeasurementPrecision.ONE_MILLIMETER))
        val scene = ImageComposeScene(320, 900) {
            CeilingsTheme(false) { MeasurementCreateScreen(model, MeasurementCreateParent.Unavailable, onBack = {}, onSaved = {}) }
        }
        try {
            scene.render().close()
            assertTrue(scene.textContent().contains("база недоступна"))
            assertTrue(scene.textContent().contains("Креслення недоступне"))
            val save = scene.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree().contains("Зберегти") }
            assertTrue(SemanticsProperties.Disabled in save.config)
        } finally { scene.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.textContent() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    private fun ImageComposeScene.clickText(text: String): Boolean = nodes().first {
        it.config.getOrNull(SemanticsActions.OnClick) != null && it.textTree().contains(text)
    }.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
}
