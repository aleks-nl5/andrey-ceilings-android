package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class MeasurementElementsSceneTest {
    @Test fun realCmpCrudPickerDirtyBackAndConflictInBothThemes(): Unit = runBlocking {
        val ui=java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(ui) {
        for (dark in listOf(false, true)) {
            // Windows exercises these UI actions with the same unlocked encrypted JDBC as its host.
            val directory = java.nio.file.Files.createTempDirectory("p3b-scene-").toFile()
            val driver = if (System.getProperty("os.name").startsWith("Windows", true))
                SqlCipherWindowsDriverFactory.open(directory.resolve("scene.db"), ByteArray(32) { 72 })
            else JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            val db = PrototypeDatabase(driver)
            PriceCatalogSchema.create(driver)
            val project = ProjectRepository(db).createReturningId(null, "Scene", createdAt = 1)
            val measurement = MeasurementRepository(db).createReturningId(project, "Кухня", createdAt = 2)
            val route = ElementRoute(project, measurement, checkNotNull(MeasurementRepository(db).find(measurement)).uuid)
            val store = MeasurementElementsStore(db, { 3 }, Dispatchers.Unconfined)
            val model = store.model(route)
            val guard = MeasurementCreateNavigationGuard()
            guard.bindElements(model)
            var nav = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).openMeasurement(project, measurement)
            suspend fun ready() { withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded } } }
            suspend fun saved() { withTimeout(10_000) { model.editor.first { it == null } } }
            ready()
            val scene = ImageComposeScene(640, 1200, coroutineContext=ui) {
                CeilingsTheme(dark) {
                    val state by model.state.collectAsState()
                    MeasurementElementsSection(state, model::add, model::open, model::retry)
                    val editor by model.editor.collectAsState()
                    val catalog by model.catalog.collectAsState()
                    editor?.let { MeasurementElementEditor(it, catalog, state is MeasurementElementsState.Loaded,
                        model::update, model::requestDismiss, model::save, model::refreshCatalog,
                        model::continueEditing, model::discardAndExit, model::requestReload, model::cancelReload, model::reload) }
                }
            }
            var testFailure:Throwable?=null
            try {
                scene.awaitText("кількість можна уточнити")
                scene.click("Додати елемент"); scene.frame(); scene.click("Побажання"); scene.frame()
                scene.setField("Назва побажання", "Карниз")
                val uuid = checkNotNull(model.editor.value).draft.uuid
                assertSame(model, store.model(route))
                assertEquals(nav, guard.transition(nav, nav.back())); scene.frame()
                scene.awaitText("Відхилити зміни?")
                scene.click("Продовжити"); scene.frame(); scene.click("Зберегти"); saved(); scene.frame()
                assertEquals(uuid, MeasurementElementRepository(db).active().single().uuid)
                scene.click("Редагувати"); scene.frame()
                scene.setField("Одиниця", "м.п."); scene.click("Точно"); scene.frame()
                scene.setField("Точна кількість", "2")
                scene.click("Вставити кому"); scene.frame()
                assertEquals("2,", model.editor.value?.draft?.input?.exact)
                scene.setField("Точна кількість", "2,5")
                scene.click("Зберегти"); saved(); scene.frame()
                scene.awaitText("2,5 м.п.")
                scene.click("Прив'язати до прайсу"); scene.frame()
                withTimeout(10_000) { model.catalog.first { it is ElementCatalogState.Loaded } }
                scene.setField("Пошук за назвою", "Світильник точковий")
                scene.frame()
                // Select a real active UUID, never a title-based write.
                val catalog = model.catalog.value as ElementCatalogState.Loaded
                val spot = catalog.items.first { it.code == "light.spot" }
                scene.setField("Пошук за назвою", spot.title.uppercase()); scene.frame()
                scene.click(spot.title); scene.frame()
                scene.awaitText("несумісні")
                scene.click("Задати нову кількість"); scene.frame()
                scene.setField("Точна кількість", "6")
                scene.click("Підтвердити прив'язування"); saved(); scene.frame()
                scene.awaitText("6 шт."); scene.awaitText("З прайсу")
                scene.click("Редагувати"); scene.frame(); scene.setField("Примітка", "Local draft")
                val repo = MeasurementElementRepository(db); val old = checkNotNull(repo.find(uuid))
                repo.edit(old.version, "Concurrent", old.approval, old.quantity, 4)
                scene.click("Зберегти")
                withTimeout(10_000) { model.editor.first { it?.conflict == true } }; scene.frame()
                scene.awaitText("Чернетку збережено")
                scene.click("Перезавантажити актуальні дані"); scene.frame()
                scene.awaitText("Замінити чернетку актуальними даними?")
                scene.click("Замінити чернетку")
                withTimeout(10_000) { model.editor.first { it?.draft?.note == "Concurrent" && !it.saving } }; scene.frame()
                scene.click("Скасувати"); scene.frame()
                scene.click("Видалити"); scene.frame(); scene.awaitText("Сам замір залишиться")
                scene.click("Підтвердити видалення"); saved()
                withTimeout(10_000) { model.state.first { it is MeasurementElementsState.Loaded && it.items.isEmpty() } }
                scene.awaitText("кількість можна уточнити")
                assertNotNull(MeasurementRepository(db).find(measurement))
                model.add(); model.choose(ElementEditorAction.CREATE_WISH); model.update { it.copy(label = "Dirty") }
                var switched = false
                guard.transition(nav, nav.selectTab(AppRoute.PRICES)) { nav = it; switched = true }
                scene.frame(); assertFalse(switched)
                scene.click("Відхилити зміни"); assertTrue(switched)
            } catch(failure:Throwable) { testFailure=failure;throw failure
            } finally {
                try {scene.close()} catch(cleanup:Throwable) {if(testFailure!=null)testFailure.addSuppressed(cleanup) else throw cleanup}
                finally {store.closeAndAwaitWrites();driver.close();directory.deleteRecursively()}
            }
        }
        } } finally {ui.close()}
    }

    @Test fun hoistedDetailsEmbedsElementsAndFullscreenRemainsDrawingOnly():Unit = runBlocking {
        val guard = MeasurementCreateNavigationGuard()
        val scene = ImageComposeScene(640, 1800) {
            CeilingsTheme(false) {
                MeasurementDetailsScreen(MeasurementDetailsState.Loaded(MeasurementDetailsData(1, 2,
                    "Кухня", "Об'єкт", "Адреса", "rectangle", 20.0, 18.0, "",
                    MeasurementGeometryView.HistoricalRectangle(5.0, 4.0), "parent-uuid")),
                    onBack = {}, navigationGuard = guard, elementsContent = {
                        MeasurementElementsSection(MeasurementElementsState.Loaded(emptyList()), {}, { _, _ -> }, {})
                    })
            }
        }
        try {
            scene.frame(); assertTrue(scene.text().contains("Елементи стелі"))
            scene.click("На весь екран"); scene.frame()
            assertTrue(guard.fullscreen.value); assertFalse(scene.text().contains("Елементи стелі"))
            scene.click("Згорнути"); scene.frame(); assertTrue(scene.text().contains("Додати елемент"))
        } finally { scene.close() }
    }

    private fun ImageComposeScene.frame() { Snapshot.sendApplyNotifications(); render(System.nanoTime()).close() }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } + children.joinToString { it.textTree() }
    private fun ImageComposeScene.text() = semanticsOwners.joinToString { it.rootSemanticsNode.textTree() }
    /**
     * SQLDelight Flow and Compose semantics can become visible on different frames,
     * especially with encrypted JDBC on Windows. Wait for the requested UI action
     * rather than racing the next frame after a completed repository write.
     * Still fail loudly with the visible action tree when a control never appears.
     */
    private suspend fun ImageComposeScene.awaitAction(label: String, eligible: (SemanticsNode) -> Boolean): SemanticsNode {
        repeat(150) {
            frame()
            nodes().firstOrNull { eligible(it) && it.textTree().contains(label) }?.let { return it }
            delay(20)
        }
        val visible = nodes().filter(eligible).joinToString(" | ") { it.textTree().take(180) }
        fail("Compose control '$label' did not appear within 3 seconds. Available controls: $visible. UI: ${text().take(1200)}")
    }
    /** List Flow may deliver after the editor closes on encrypted Windows JDBC. */
    private suspend fun ImageComposeScene.awaitText(label: String) {
        repeat(200) {
            frame()
            if (text().contains(label)) return
            delay(25)
        }
        fail("Compose text '$label' did not appear within 5 seconds. UI: ${text().take(1800)}")
    }
    private suspend fun ImageComposeScene.click(label: String) {
        val node = awaitAction(label) { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertNull(node.config.getOrNull(SemanticsProperties.Disabled), label)
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
        frame()
    }
    private suspend fun ImageComposeScene.setField(label: String, value: String) {
        val node = awaitAction(label) { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(node.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value)) == true)
        frame()
    }
}
