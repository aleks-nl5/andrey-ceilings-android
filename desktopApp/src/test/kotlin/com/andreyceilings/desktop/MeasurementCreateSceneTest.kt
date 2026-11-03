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
import kotlin.test.*

class MeasurementCreateSceneTest {
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
                assertTrue(scene.textContent().contains("AB:"))
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
        var navigation = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(7).createMeasurement(7)
        val registry = SaveableStateRegistry(null) { true }
        fun scene(registry: SaveableStateRegistry, precision: MeasurementPrecision) = ImageComposeScene(360, 1000) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                CeilingsTheme(false) {
                    MeasurementCreateScreen(7, precision, null, PaddingValues(16.dp), 0.dp,
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
        val restored = scene(SaveableStateRegistry(saved) { true }, MeasurementPrecision.TEN_CENTIMETERS)
        try {
            restored.render().close()
            assertTrue(restored.textContent().contains("5 мм"))
            assertTrue(restored.nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Відновлена кухня" })
            assertFalse(restored.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { v -> v.text == "Збереження…" } })
            assertTrue(restored.clickText("Скасувати"))
            assertNull(navigation.measurementCreate)
            assertEquals(ProjectDetails(7), navigation.projectDetails)
        } finally { restored.close() }
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
