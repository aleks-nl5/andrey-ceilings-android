package com.andreyceilings.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.objects.*
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ClientListEntry
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import com.andreyceilings.shared.theme.ceilingsColorScheme
import org.jetbrains.skia.Bitmap
import kotlin.test.*

class MeasurementSceneTest {
    private fun geometries(): List<MeasurementGeometryView> {
        val rectangle = mapOf("length" to 5000L, "width" to 4000L)
        val lShape = mapOf("A" to 5000L, "B" to 4000L, "C" to 2000L, "D" to 2000L)
        val r = OrthogonalTurn.RIGHT
        return listOf(
            MeasurementGeometryView.Captured(FrozenPolygonGeometryContract.capture("rectangle",
                CeilingShapeTemplates.fromMeasurements("rectangle", rectangle).vertices, rectangle, 0.01)),
            MeasurementGeometryView.Captured(FrozenPolygonGeometryContract.capture("l_shaped",
                CeilingShapeTemplates.fromMeasurements("l_shaped", lShape).vertices, lShape, 0.01)),
            MeasurementGeometryView.Captured(FrozenPolygonGeometryContract.capture("polygon",
                listOf(CeilingPointMm(0, 0), CeilingPointMm(3000, 0), CeilingPointMm(0, 4000)), emptyMap(), 0.001)),
            MeasurementGeometryView.Captured(FrozenPolygonGeometryContract.captureSides(
                listOf(5000L, 3000L, 5000L, 3000L).map { MeasuredOrthogonalSide(it, r) }, 0.001)),
            MeasurementGeometryView.HistoricalRectangle(5.12345, 4.0)
        )
    }

    @Test fun canvasActuallyRendersEveryShapeInBothModesThemesAndViewportSizes() {
        for (geometry in geometries()) for (compact in listOf(false, true))
            for (dark in listOf(false, true)) for (width in listOf(160, 640)) {
                val scene = ImageComposeScene(width, 700) {
                    CeilingsTheme(dark) {
                        Box(Modifier.fillMaxSize().background(ceilingsColorScheme(dark).background)) {
                            MeasurementDrawing(geometry, Modifier.fillMaxWidth().padding(8.dp), compact)
                        }
                    }
                }
                try {
                    val image = scene.render()
                    val bitmap = Bitmap.makeFromImage(image)
                    try {
                        val ink = ceilingsColorScheme(dark).primary.toArgb()
                        var inkPixels = 0
                        for (y in 0 until 320) for (x in 0 until width)
                            if (bitmap.getColor(x, y) == ink) inkPixels++
                        assertTrue(inkPixels > 10, "Contour/vertices must actually be painted")
                        val text = scene.textContent()
                        assertTrue(text.contains("AB:"))
                        assertTrue(text.contains("м"))
                    } finally { bitmap.close(); image.close() }
                    scene.constraints = Constraints.fixed(120, 500)
                    scene.render(16_000_000).close()
                } finally { scene.close() }
            }
    }

    @Test fun invalidGeometryShowsAnExplicitMessageInsteadOfCrashingCanvas() {
        val invalid = (geometries().first() as MeasurementGeometryView.Captured).geometry
            .copy(vertices = emptyList())
        for (geometry in listOf(MeasurementGeometryView.Unavailable,
            MeasurementGeometryView.Captured(invalid), MeasurementGeometryView.HistoricalRectangle(Double.NaN, 4.0))) {
            val scene = ImageComposeScene(160, 300) {
                CeilingsTheme(false) { MeasurementDrawing(geometry) }
            }
            try {
                scene.render().close()
                assertTrue(scene.textContent().contains("Креслення недоступне"))
            } finally { scene.close() }
        }
    }

    @Test fun detailsShowsStoredDataAndBackWhileRemovingDeletedOrUnavailableContent() {
        var backs = 0
        val state = mutableStateOf<MeasurementDetailsState>(MeasurementDetailsState.Loading)
        val scene = ImageComposeScene(360, 800) {
            CeilingsTheme(false) {
                MeasurementDetailsScreen(state.value, PaddingValues(16.dp), 24.dp, onBack = { backs++ })
            }
        }
        try {
            scene.render().close()
            assertTrue(scene.textContent().contains("Завантаження"))
            state.value = MeasurementDetailsState.Loaded(MeasurementDetailsData(3, 7, "Кухня", "Об'єкт",
                "Збережена адреса", "rectangle", 20.0, 18.0, "Збережені примітки", geometries().first()))
            Snapshot.sendApplyNotifications()
            scene.render(16_000_000).close()
            assertTrue(scene.textContent().contains("Кухня"))
            assertTrue(scene.textContent().contains("20,0000"))
            scene.sendPointerEvent(PointerEventType.Press, Offset(44f, 60f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(44f, 60f), type = PointerType.Touch)
            assertEquals(1, backs)
            for ((next, message) in listOf(MeasurementDetailsState.Missing to "Замір не знайдено",
                MeasurementDetailsState.Unavailable to "база недоступна")) {
                state.value = next
                Snapshot.sendApplyNotifications()
                scene.render(32_000_000).close()
                assertTrue(scene.textContent().contains(message))
                assertFalse(scene.textContent().contains("Кухня"))
                assertFalse(scene.textContent().contains("Збережена адреса"))
            }
        } finally { scene.close() }
    }

    @Test fun nestedRestoreAndBackRetainSearchAndScrollWhileTabSwitchStillStartsAtTop() {
        for (creating in listOf(false, true)) {
        val rows = ObjectsClientsRows((1..40).map {
            ProjectListEntry(it.toLong(), "Адреса $it", "Адреса $it", null, "", "measurement", "unpaid", "")
        }, (1..40).map { ClientListEntry(it.toLong(), "Адреса $it", "", "", "", 0) })
        val navigation = mutableStateOf(AppNavigationState().selectTab(AppRoute.OBJECTS))
        val switches = mutableListOf<ObjectsClientsTab>()
        fun makeScene(registry: SaveableStateRegistry) = ImageComposeScene(360, 700) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                CeilingsTheme(false) {
                    ObjectsClientsScreen(rows, navigation.value.projectDetails?.projectId, null,
                        onOpenProject = { navigation.value = navigation.value.openProject(it) },
                        onCloseProject = { navigation.value = navigation.value.back() }, databaseAvailable = true,
                        onSaveClient = { _, _ -> }, onSaveProject = { _, _ -> },
                        onDeleteClient = {}, onDeleteProject = {}, onDial = {}, onExternalAction = { _, _ -> },
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 84.dp),
                        horizontalOverlayInset = 16.dp, measurementDetails = navigation.value.measurementDetails,
                        measurementCreate = navigation.value.measurementCreate,
                        onListTabChanged = { switches += it },
                        onOpenMeasurement = { p, m -> navigation.value = navigation.value.openMeasurement(p, m) })
                }
            }
        }
        fun visibleAddresses(scene: ImageComposeScene): List<String> = scene.nodes()
            .filter { it.boundsInRoot.top >= 170 && it.boundsInRoot.bottom <= 700 }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { value -> value.text } }
            .filter { Regex("Адреса [0-9]+").matches(it) }
        val firstRegistry = SaveableStateRegistry(null) { true }
        val first = makeScene(firstRegistry)
        val saved: Map<String, List<Any?>>
        val before: List<String>
        val routeSnapshot: List<String>
        try {
            first.render().close()
            val search = first.nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(search.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("Адреса")) == true)
            Snapshot.sendApplyNotifications()
            first.render(16_000_000).close()
            first.sendPointerEvent(PointerEventType.Press, Offset(140f, 550f), timeMillis = 1000, type = PointerType.Touch)
            first.sendPointerEvent(PointerEventType.Move, Offset(140f, 250f), timeMillis = 1050, type = PointerType.Touch)
            // Pause before release so this restore test saves an idle position,
            // rather than creating a second tap that would open a project card.
            first.sendPointerEvent(PointerEventType.Release, Offset(140f, 250f), timeMillis = 1600, type = PointerType.Touch)
            first.render(616_000_000).close()
            before = visibleAddresses(first)
            assertTrue(before.isNotEmpty(), first.textContent())
            assertFalse(before.contains("Адреса 1"))
            val project = navigation.value.openProject(7)
            navigation.value = if (creating) project.createMeasurement(7) else project.openMeasurement(7, 12)
            Snapshot.sendApplyNotifications()
            first.render(632_000_000).close()
            saved = firstRegistry.performSave()
            routeSnapshot = navigation.value.snapshot()
        } finally { first.close() }
        navigation.value = AppNavigationState.restore(routeSnapshot)
        val restored = makeScene(SaveableStateRegistry(saved) { true })
        try {
            restored.render().close()
            assertTrue(restored.textContent().contains("база недоступна"))
            restored.sendPointerEvent(PointerEventType.Press, Offset(44f, 36f), type = PointerType.Touch)
            restored.sendPointerEvent(PointerEventType.Release, Offset(44f, 36f), type = PointerType.Touch)
            restored.render(16_000_000).close()
            assertNull(navigation.value.measurementDetails)
            assertNull(navigation.value.measurementCreate)
            assertEquals(7L, navigation.value.projectDetails?.projectId)
            navigation.value = navigation.value.back()
            Snapshot.sendApplyNotifications()
            restored.render(32_000_000).close()
            assertTrue(restored.textContent().contains("Адреса"))
            assertEquals(before, visibleAddresses(restored))
            restored.sendPointerEvent(PointerEventType.Press, Offset(260f, 90f), type = PointerType.Touch)
            restored.sendPointerEvent(PointerEventType.Release, Offset(260f, 90f), type = PointerType.Touch)
            restored.render(48_000_000).close()
            assertEquals(ObjectsClientsTab.CLIENTS, switches.lastOrNull())
            val firstClient = restored.nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Адреса 1" }
            }
            assertEquals(169f, firstClient.boundsInRoot.top, "Fresh client tab begins immediately below measured controls")
        } finally { restored.close() }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun ImageComposeScene.textContent(): String {
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        return semanticsOwners.flatMap { texts(it.rootSemanticsNode) }.joinToString("\n")
    }
}
