package com.andreyceilings.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.app.ui.screens.writeEstimateDocumentPdf
import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.measurement.MeasurementEditStore
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.measurement.MeasurementOutlineDraft
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.*

class FrozenEstimatePdfShapeTest {
    @get:Rule val compose = createComposeRule()

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
                edit.update(shaped, shaped.draft.copy(outline = original.draft.outline), now = 601)
                history.appendIndividual(FrozenRevisionProof.request(db, fixture, frozen.seriesUuid), 602)

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
                    compose.onNodeWithText("Створити PDF").performClick()
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("Зберегти PDF").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Зберегти PDF").performClick()
                    val pages = compose.runOnIdle { assertNotNull(captured) }
                    assertTrue(pages.fileName.endsWith("-v1.pdf"))
                    runBlocking { writeEstimateDocumentPdf(pages, 0 until pages.count) { file.outputStream() } }
                    PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                        assertTrue((0 until pdf.pageCount).any { index ->
                            pdf.openPage(index).use { page ->
                                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val top = (0 until page.height - 195).firstOrNull { y ->
                                        listOf(45, 550).all { x -> bitmap.isDrawingBackground(x, y) &&
                                            bitmap.isDrawingBackground(x, y + 5) }
                                    }
                                    if (top == null) false else {
                                        // Probe a frozen edge and nearby empty space in the A4 drawing.
                                        assertTrue(bitmap.hasInk(edge.first, top + edge.second))
                                        assertFalse(bitmap.hasInk(interior.first, top + interior.second))
                                        true
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }, "The PDF has no drawing panel")
                    }
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

    private fun Bitmap.isDrawingBackground(x: Int, y: Int): Boolean {
        val color = getPixel(x, y)
        return Color.red(color) in 237..245 && Color.green(color) in 242..250 &&
            Color.blue(color) in 242..250
    }

    private fun Bitmap.hasInk(x: Int, y: Int): Boolean =
        (x - 3..x + 3).any { column -> (y - 3..y + 3).any { row -> Color.red(getPixel(column, row)) < 100 } }
}
