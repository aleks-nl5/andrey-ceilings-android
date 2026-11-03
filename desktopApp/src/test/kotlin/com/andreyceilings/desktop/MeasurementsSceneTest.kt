package com.andreyceilings.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlin.test.*

/** Exercise the shared global picker itself without adding any Desktop host routes. */
class MeasurementsSceneTest {
    @Test fun zeroOneAndMultipleProjectsAlwaysRequireExplicitSelection(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val projects = ProjectRepository(db)
            val store = MeasurementsStore(db)
            val createStore = MeasurementCreateStore(db) { 2 }
            val detailsStore = MeasurementDetailsStore(db)
            val editStore = MeasurementEditStore(db) { 3 }
            val guard = MeasurementCreateNavigationGuard()
            val navigation = mutableStateOf(AppNavigationState().requestQuickCreate(AppQuickCreate.MEASUREMENT))
            var createProjectRequests = 0
            val scene = ImageComposeScene(400, 1000) {
                CeilingsTheme(false) {
                    MeasurementsScreen(store, createStore, detailsStore, editStore,
                        navigation.value, MeasurementPrecision.ONE_CENTIMETER, PaddingValues(16.dp), 0.dp, guard,
                        onNavigate = { navigation.value = guard.transition(navigation.value, it) },
                        onEditSaved = { _, _ -> fail("Picker cannot save an edit") },
                        onUseMeasurement = { fail("Picker cannot calculate") }, onOpenEstimates = {},
                        onCreateProject = { createProjectRequests++ })
                }
            }
            var frame = 0L
            suspend fun awaitText(text: String) = withTimeout(10_000) {
                while (true) {
                    Snapshot.sendApplyNotifications()
                    scene.render(frame++ * 16_000_000L).close()
                    if (scene.nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(text) } }) break
                    delay(10)
                }
            }
            fun click(text: String) {
                val node = scene.nodes().first { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.textTree().contains(text) }
                assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            }
            try {
                awaitText("Спочатку створіть об'єкт")
                click("Створити об'єкт")
                assertEquals(1, createProjectRequests)
                val first = projects.createReturningId(null, "Адреса 1", createdAt = 1)
                awaitText("Адреса 1")
                assertNull(navigation.value.measurementCreate, "Even the only project needs an explicit tap")
                click("Адреса 1")
                assertEquals(MeasurementCreate(first), navigation.value.measurementCreate)
                assertEquals(AppRoute.MEASUREMENTS, navigation.value.current)
                assertNull(navigation.value.projectDetails)
                navigation.value = navigation.value.back().chooseMeasurementProject()
                val second = projects.createReturningId(null, "Адреса 2", createdAt = 1)
                awaitText("Адреса 2")
                assertNull(navigation.value.measurementCreate)
                click("Адреса 2")
                assertEquals(MeasurementCreate(second), navigation.value.measurementCreate)
                assertTrue(MeasurementRepository(db).all().isEmpty(), "Choosing and cancelling never write")
            } finally { scene.close() }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun SemanticsNode.textTree(): String = config.getOrNull(SemanticsProperties.Text).orEmpty()
        .joinToString { it.text } + children.joinToString { it.textTree() }
}
