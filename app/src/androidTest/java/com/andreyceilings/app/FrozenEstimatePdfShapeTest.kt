package com.andreyceilings.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.app.ui.screens.writeEstimateDocumentPdf
import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.measurement.MeasurementEditStore
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.measurement.MeasurementOutlineDraft
import com.andreyceilings.shared.measurement.DrawingPoint
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import com.andreyceilings.shared.measurement.MeasurementGeometryCodec
import com.andreyceilings.shared.measurement.MeasurementGeometryView
import com.andreyceilings.shared.measurement.measurementDrawingContour
import com.andreyceilings.shared.measurement.measurementDrawingProjection
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.*

class FrozenEstimatePdfShapeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rectangleRevisionShowsItsFourVertexLetters() = assertFrozenContour(
        MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "5", "width" to "4")),
        edge = 298 to 36, interior = 298 to 98
    )

    @Test fun lShapedRevisionRetainsItsInnerCorner() = assertFrozenContour(
        MeasurementOutlineDraft.Template("l_shaped", mapOf("A" to "5", "B" to "4", "C" to "2", "D" to "2")),
        edge = 298 to 98, interior = 298 to 159
    )

    @Test fun polygonRevisionRetainsItsDiagonal() = assertFrozenContour(
        MeasurementOutlineDraft.Captured(FrozenPolygonGeometryContract.capture("polygon", listOf(
            CeilingPointMm(0, 0), CeilingPointMm(4000, 0), CeilingPointMm(4000, 1500),
            CeilingPointMm(2000, 3000), CeilingPointMm(0, 1500)
        ), emptyMap(), 0.001)),
        edge = 339 to 128, interior = 298 to 128
    )

    private fun assertFrozenContour(
        outline: MeasurementOutlineDraft,
        edge: Pair<Int, Int>,
        interior: Pair<Int, Int>
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "frozen-pdf-shape.db"
        val key = ByteArray(32) { 0x47 }
        val file = File.createTempFile("frozen-pdf-shape", ".pdf", context.cacheDir)
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(context, key, name, initializePriceCatalog = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val measurement = assertNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
                val edit = MeasurementEditStore(db) { 500 }
                val original = edit.load(fixture.projectId, measurement.id)
                val shaped = edit.update(original, original.draft.copy(outline = outline))
                val history = ImmutableEstimateRepository(db)
                val frozen = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
                val saved = assertNotNull(history.exactIndividual(frozen.seriesUuid, frozen.initialRevisionUuid))
                val geometry = MeasurementGeometryCodec.read(saved.frozen.source.drawingJson)
                val contour = assertNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))

                val store = MeasurementElementsStore(db, { 610 })
                try {
                    var captured: EstimateDocumentPages? = null
                    compose.setContent {
                        CompositionLocalProvider(LocalEstimateDocumentExporter provides EstimateDocumentExporter { pages, _, done ->
                            captured = pages
                            done(null)
                        }) {
                            CeilingsTheme(false) {
                                SavedIndividualQuoteDialog(store, frozen.seriesUuid, frozen.initialRevisionUuid) {}
                            }
                        }
                    }
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("Створити PDF").fetchSemanticsNodes().isNotEmpty()
                    }
                    fun exportFirstRevision(): List<RectF> {
                        compose.runOnIdle { captured = null }
                        compose.onNodeWithText("Створити PDF").performClick()
                        compose.waitUntil(10_000) {
                            compose.onAllNodesWithText("Зберегти PDF").fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.onNodeWithText("Зберегти PDF").performClick()
                        val pages = compose.runOnIdle { assertNotNull(captured) }
                        assertTrue(pages.fileName.endsWith("-v1.pdf"))
                        runBlocking { writeEstimateDocumentPdf(pages, 0 until pages.count) { file.outputStream() } }
                        return assertRenderedContour(file, contour, edge, interior)
                    }

                    val firstLetters = exportFirstRevision()
                    compose.onNode(hasText("Назад") and hasAnyAncestor(
                        isDialog() and hasAnyDescendant(hasText("Попередній перегляд PDF"))
                    )).performClick()

                    edit.update(shaped, shaped.draft.copy(outline = original.draft.outline), now = 601)
                    val second = history.appendIndividual(FrozenRevisionProof.request(db, fixture, frozen.seriesUuid), 602)
                    val revised = assertNotNull(history.exactIndividual(frozen.seriesUuid, second.initialRevisionUuid))
                    assertNotEquals(geometry.vertices, MeasurementGeometryCodec.read(revised.frozen.source.drawingJson).vertices)
                    assertEquals(firstLetters, exportFirstRevision(), "Re-exported v1 must retain its original vertex positions")
                    assertEquals(saved.frozen, history.exactIndividual(frozen.seriesUuid, frozen.initialRevisionUuid)?.frozen)
                } finally {
                    runBlocking { store.closeAndAwaitWrites() }
                }
            }
        } finally {
            key.fill(0)
            context.deleteDatabase(name)
            file.delete()
        }
    }

    private fun assertRenderedContour(
        file: File,
        contour: MeasurementDrawingContour,
        edge: Pair<Int, Int>,
        interior: Pair<Int, Int>
    ): List<RectF> {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
            for (index in 0 until pdf.pageCount) {
                pdf.openPage(index).use page@{ page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val top = (0 until page.height - 195).firstOrNull { y ->
                            listOf(45, 550).all { x -> bitmap.isDrawingBackground(x, y) &&
                                bitmap.isDrawingBackground(x, y + 5) }
                        } ?: return@page
                        assertTrue(bitmap.hasInk(edge.first, top + edge.second))
                        assertFalse(bitmap.hasInk(interior.first, top + interior.second))
                        return if (Build.VERSION.SDK_INT >= 35) assertVertexLetters(page, bitmap, contour, top)
                        else emptyList()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        fail("The PDF has no drawing panel")
    }

    @RequiresApi(35)
    private fun assertVertexLetters(
        page: PdfRenderer.Page, bitmap: Bitmap, contour: MeasurementDrawingContour, panelTop: Int
    ): List<RectF> {
        val projection = assertNotNull(measurementDrawingProjection(contour, 515f, 195f))
        return contour.vertexNames.mapIndexed { index, name ->
            val vertex = projection.project(contour.points[index])
            val matches = page.searchText(name).flatMap { it.bounds }.filter {
                it.left >= 40f && it.right <= 555f && it.top >= panelTop && it.bottom <= panelTop + 195
            }
            val glyph = assertNotNull(matches.singleOrNull(), "Expected one '$name' inside the drawing, found $matches")
            assertTrue(isNearVertex(glyph, vertex, panelTop), "Letter '$name' is away from its vertex: $glyph / $vertex")
            assertTrue((floor(glyph.left).toInt()..ceil(glyph.right).toInt()).any { x ->
                (floor(glyph.top).toInt()..ceil(glyph.bottom).toInt()).any { y -> Color.red(bitmap.getPixel(x, y)) < 150 }
            }, "The glyph '$name' must be visible in the rasterized PDF")
            RectF(glyph).apply { offset(0f, -panelTop.toFloat()) }
        }
    }

    private fun isNearVertex(glyph: RectF, vertex: DrawingPoint, panelTop: Int): Boolean =
        abs(glyph.centerX() - (40 + vertex.x)) <= 24 &&
            abs(glyph.centerY() - (panelTop + vertex.y)) <= 24

    private fun Bitmap.isDrawingBackground(x: Int, y: Int): Boolean {
        val color = getPixel(x, y)
        return Color.red(color) in 237..245 && Color.green(color) in 242..250 &&
            Color.blue(color) in 242..250
    }

    private fun Bitmap.hasInk(x: Int, y: Int): Boolean =
        (x - 3..x + 3).any { column -> (y - 3..y + 3).any { row -> Color.red(getPixel(column, row)) < 100 } }
}
