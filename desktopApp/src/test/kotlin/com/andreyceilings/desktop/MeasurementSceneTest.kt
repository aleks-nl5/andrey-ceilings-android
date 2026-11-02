package com.andreyceilings.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
                        assertTrue(text.contains("1:"))
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
            assertTrue(scene.textContent().contains("20.0"))
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

    private fun ImageComposeScene.textContent(): String {
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        return semanticsOwners.flatMap { texts(it.rootSemanticsNode) }.joinToString("\n")
    }
}
