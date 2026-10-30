package com.andreyceilings.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.ui.screens.loadImmutableEstimatePdfSnapshot
import com.andreyceilings.app.ui.screens.writeEstimatePdf
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Use a disposable encrypted WAL SQLCipher database, including cold reopen.
 * The renderer must never read or incorporate the *current* price catalog.
 */
@RunWith(AndroidJUnit4::class)
class ImmutableEstimatePdfSnapshotTest {
    private fun source() = JSONObject()
        .put("name", "Kitchen").put("material", "pvc_matte")
        .put("profileType", "standard_alu").put("complexity", "straight")
        .put("lengthMeters", 2.0).put("widthMeters", 3.0)
        .put("areaSquareMeters", 6.0).put("perimeterMeters", 10.0)
        .toString()

    private fun draft(canvasPrice: Double, profilePrice: Double, title: String): ImmutableEstimateDraft {
        val rows = listOf(
            ImmutableEstimateLine("canvas.pvc_matte", title, "м²",
                6.0, canvasPrice, 6.0 * canvasPrice),
            ImmutableEstimateLine("profile.standard_alu", "Aluminium profile", "м.п.",
                10.0, profilePrice, 10.0 * profilePrice)
        )
        val jsonRows = JSONArray()
        rows.forEach { row ->
            jsonRows.put(JSONObject()
                .put("code", row.stableCode)
                .put("title", row.title)
                .put("unit", row.unit)
                .put("quantity", row.quantity)
                .put("unitPrice", row.unitPrice)
                .put("total", row.lineTotal))
        }
        return ImmutableEstimateDraft(
            sourceMeasurementJson = source(),
            detailsJson = JSONObject()
                .put("measurementPrecisionMeters", 0.01)
                .put("lineItems", jsonRows).toString(),
            measurementPrecisionMeters = 0.01,
            calculationRuleVersion = "kmp-v1",
            totalCost = rows.sumOf { it.lineTotal },
            lines = rows
        )
    }

    @Test fun versionedPdfsUseFrozenPricesAndNeverCurrentCatalogAcrossColdReopen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbName = "sqldelight_test_version_pdf_history.db"
        val key = ByteArray(32) { 0x65.toByte() }
        context.deleteDatabase(dbName)
        val pdfFiles = mutableListOf<File>()
        try {
            lateinit var seriesId: String
            lateinit var revisionIds: List<String>
            createEncryptedPrototypeDriver(
                context, key, dbName, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "PDF Historical Project", createdAt = 100L
                )
                val history = ImmutableEstimateRepository(db)
                val first = history.createSeries(
                    projectId, 2026, 200L, draft(10.0, 5.0, "Old canvas")
                )
                val v2 = history.appendRevision(
                    first.seriesUuid, first.initialRevisionUuid, 300L,
                    draft(20.0, 6.0, "Second canvas")
                )
                val v3 = history.appendRevision(
                    first.seriesUuid, v2, 400L,
                    draft(25.0, 7.0, "Third canvas")
                )
                seriesId = first.seriesUuid
                revisionIds = listOf(first.initialRevisionUuid, v2, v3)

                // Change the price and title AFTER all revisions were frozen.
                PriceRepository(db).upsertReturningId(
                    code = "canvas.pvc_matte", title = "Current unrelated price",
                    category = "canvas", unit = "м²", price = 999.0,
                    updatedAt = 500L
                )
            }
            createEncryptedPrototypeDriver(
                context, key, dbName, enableWalForTesting = true
            ).use { reopened ->
                val db = PrototypeDatabase(reopened)
                val expectedTotals = listOf(110.0, 180.0, 220.0)
                val expectedPrices = listOf(10.0, 20.0, 25.0)
                val seenNumbers = mutableSetOf<String>()
                revisionIds.forEachIndexed { index, revisionId ->
                    val frozen = loadImmutableEstimatePdfSnapshot(db, seriesId, revisionId)
                    assertEquals((index + 1).toLong(), frozen.versionNumber)
                    assertEquals(expectedTotals[index], frozen.estimate.totalCost, 0.0)
                    assertEquals("PDF Historical Project", frozen.projectTitle)
                    assertEquals(expectedPrices[index],
                        JSONObject(frozen.estimate.detailsJson).getJSONArray("lineItems")
                            .getJSONObject(0).getDouble("unitPrice"), 0.0)
                    assertFalse(frozen.estimate.detailsJson.contains("999.0"))
                    assertFalse(frozen.printableNumber.contains(seriesId))
                    seenNumbers += frozen.printableNumber

                    val file = File.createTempFile(
                        "estimate_pdf_v${index + 1}_", ".pdf", context.cacheDir
                    )
                    pdfFiles += file
                    writeEstimatePdf(
                        context, Uri.fromFile(file), frozen.estimate,
                        frozen.projectTitle, frozen.versionNumber
                    )
                    assertTrue("Empty PDF for version ${index + 1}", file.length() > 100L)
                    ParcelFileDescriptor.open(
                        file, ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            assertTrue("No PDF pages", renderer.pageCount > 0)
                        }
                    }
                }
                assertEquals(1, seenNumbers.size)
                assertEquals(listOf(1L, 2L, 3L),
                    ImmutableEstimateRepository(db).revisions(seriesId)
                        .map { it.versionNumber })
                assertEquals(expectedTotals,
                    ImmutableEstimateRepository(db).revisions(seriesId)
                        .map { it.totalCost })

                // An immutable revision cannot be presented as another
                // estimate series, even if the caller knows its UUID.
                val otherProject = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Another project", createdAt = 501L
                )
                val unrelated = ImmutableEstimateRepository(db).createSeries(
                    otherProject, 2026, 502L, draft(10.0, 5.0, "Unrelated")
                )
                assertTrue(runCatching {
                    loadImmutableEstimatePdfSnapshot(
                        db, unrelated.seriesUuid, revisionIds[1]
                    )
                }.isFailure)
                val tampered = draft(10.0, 5.0, "Old canvas").copy(
                    detailsJson = JSONObject(draft(10.0, 5.0, "Old canvas").detailsJson)
                        .put("lineItems", JSONArray().put(JSONObject()
                            .put("code", "canvas.pvc_matte")
                            .put("title", "Tampered")
                            .put("unit", "м²")
                            .put("quantity", 6.0)
                            .put("unitPrice", 10.0)
                            .put("total", 60.0))).toString()
                )
                val broken = ImmutableEstimateRepository(db).createSeries(
                    otherProject, 2026, 503L, tampered
                )
                assertTrue(runCatching {
                    loadImmutableEstimatePdfSnapshot(
                        db, broken.seriesUuid, broken.initialRevisionUuid
                    )
                }.isFailure)
            }
        } finally {
            pdfFiles.forEach { it.delete() }
            context.deleteDatabase(dbName)
            key.fill(0)
        }
    }
    @Test fun historicalLShapedPdfsIncludeSixFrozenSidesAcrossV1AndV2() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_l_shaped_pdf_history.db"
        val key = ByteArray(32) { 0x67.toByte() }
        val files = mutableListOf<File>()
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(
                context, key, name, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "L shaped PDF", createdAt = 100L
                )
                val immutableSource = JSONObject(source()).apply {
                    remove("lengthMeters")
                    remove("widthMeters")
                }
                immutableSource.put("shape", "l_shaped")
                    .put("geometrySchemaVersion", 1L)
                    .put("sideA", 5.0).put("sideB", 4.0)
                    .put("sideC", 2.0).put("sideD", 2.0)
                    .put("sideE", 3.0).put("sideF", 2.0)
                    .put("areaSquareMeters", 14.0).put("perimeterMeters", 18.0)
                fun frozenDraft(price: Double): ImmutableEstimateDraft {
                    val items = listOf(
                        ImmutableEstimateLine(
                            "canvas.pvc_matte", "L canvas", "м²",
                            14.0, price, 14.0 * price
                        ),
                        ImmutableEstimateLine(
                            "profile.standard_alu", "L profile", "м.п.",
                            18.0, 5.0, 90.0
                        )
                    )
                    val rows = JSONArray()
                    items.forEach { line ->
                        rows.put(JSONObject()
                            .put("code", line.stableCode)
                            .put("title", line.title)
                            .put("unit", line.unit)
                            .put("quantity", line.quantity)
                            .put("unitPrice", line.unitPrice)
                            .put("total", line.lineTotal))
                    }
                    return ImmutableEstimateDraft(
                        sourceMeasurementJson = immutableSource.toString(),
                        detailsJson = JSONObject()
                            .put("measurementPrecisionMeters", 0.01)
                            .put("lineItems", rows).toString(),
                        measurementPrecisionMeters = 0.01,
                        calculationRuleVersion = "kmp-v1",
                        totalCost = items.sumOf { it.lineTotal },
                        lines = items
                    )
                }
                val history = ImmutableEstimateRepository(db)
                val created = history.createSeries(
                    projectId, 2026, 200L, frozenDraft(10.0)
                )
                val v2 = history.appendRevision(
                    created.seriesUuid, created.initialRevisionUuid,
                    300L, frozenDraft(20.0)
                )
                listOf(created.initialRevisionUuid, v2).forEachIndexed {
                    index, revisionId ->
                    val frozen = loadImmutableEstimatePdfSnapshot(
                        db, created.seriesUuid, revisionId
                    )
                    assertEquals((index + 1).toLong(), frozen.versionNumber)
                    assertEquals(listOf(230.0, 370.0)[index],
                        frozen.estimate.totalCost, 0.0)
                    val input = checkNotNull(frozen.estimate.frozenGeometryInput)
                    assertEquals("l_shaped", input.shape)
                    assertEquals(5.0, input.sideA, 0.0)
                    assertEquals(2.0, input.sideF, 0.0)
                    val output = File.createTempFile(
                        "l_pdf_v${index + 1}_", ".pdf", context.cacheDir
                    )
                    files += output
                    writeEstimatePdf(
                        context, Uri.fromFile(output), frozen.estimate,
                        frozen.projectTitle, frozen.versionNumber
                    )
                    assertTrue(output.length() > 100L)
                    ParcelFileDescriptor.open(
                        output, ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            assertTrue(renderer.pageCount > 0)
                        }
                    }
                }
                assertEquals(230.0,
                    history.revision(created.initialRevisionUuid)!!.totalCost, 0.0)
            }
        } finally {
            files.forEach { it.delete() }
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

}
