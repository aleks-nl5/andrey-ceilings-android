package com.andreyceilings.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.EstimateDao
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.EstimateRevisionSnapshotAssembler
import com.andreyceilings.app.data.db.FrozenPolygonGeometryJson
import com.andreyceilings.app.ui.screens.loadImmutableEstimatePdfSnapshot
import com.andreyceilings.app.ui.screens.writeEstimatePdf
import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.calculation.MeasuredOrthogonalSide
import com.andreyceilings.shared.calculation.OrthogonalTurn
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v3 represents the measured sides and turns, not just a derived drawing.
 * Prove that an encrypted estimate's history, copies, repricing and PDFs keep
 * exactly those measurements across a genuine SQLCipher WAL cold reopen.
 */
@RunWith(AndroidJUnit4::class)
class MeasuredSidesV3EstimateRegressionTest {
    private fun savedDetails(lines: List<ImmutableEstimateLine>): String {
        val json = JSONObject().put("measurementPrecisionMeters", 0.01)
        EstimateOptionId.entries.forEach { json.put(it.code, 0.0) }
        val rows = JSONArray()
        lines.forEach { line ->
            rows.put(JSONObject()
                .put("code", line.stableCode)
                .put("title", line.title)
                .put("unit", line.unit)
                .put("quantity", line.quantity)
                .put("unitPrice", line.unitPrice)
                .put("total", line.lineTotal))
        }
        return json.put("lineItems", rows).toString()
    }

    @Test fun encryptedWalSideTurnEstimateCopyRevisionsHistoricalPdfAndTampering() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbName = "sqlcipher_orthogonal_sides_v3_estimate.db"
        val key = ByteArray(32) { 0x57.toByte() }
        val outputFiles = mutableListOf<File>()
        context.deleteDatabase(dbName)
        val r = OrthogonalTurn.RIGHT
        val l = OrthogonalTurn.LEFT
        val frozen = FrozenPolygonGeometryContract.captureSides(
            listOf(
                MeasuredOrthogonalSide(5000, r),
                MeasuredOrthogonalSide(2000, r),
                MeasuredOrthogonalSide(3000, l),
                MeasuredOrthogonalSide(2000, r),
                MeasuredOrthogonalSide(2000, r),
                MeasuredOrthogonalSide(4000, r)
            ), 0.01
        )
        try {
            var sourceJson = ""
            lateinit var seriesUuid: String
            lateinit var revisionIds: List<String>
            createEncryptedPrototypeDriver(
                context, key, dbName, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Measured polygon project", createdAt = 100L
                )
                val prices = PriceRepository(db)
                val canvasId = prices.upsertReturningId(
                    code = "canvas.pvc_matte", title = "Canvas repriced",
                    category = "canvas", unit = "м²", price = 20.0, updatedAt = 200L
                )
                prices.upsertReturningId(
                    code = "profile.standard_alu", title = "Profile repriced",
                    category = "profile", unit = "м.п.", price = 6.0, updatedAt = 200L
                )
                val lines = listOf(
                    ImmutableEstimateLine("canvas.pvc_matte", "Original canvas", "м²",
                        14.0, 10.0, 140.0),
                    ImmutableEstimateLine("profile.standard_alu", "Original profile", "м.п.",
                        18.0, 5.0, 90.0)
                )
                val estimate = EstimateEntity(
                    projectId = projectId, name = "Side-based polygon",
                    material = "pvc_matte", profileType = "standard_alu",
                    complexity = "straight",
                    lengthMeters = 5.0, widthMeters = 4.0,
                    areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    canvasCost = 140.0, profileCost = 90.0,
                    complexityCost = 0.0, optionsCost = 0.0, totalCost = 230.0,
                    detailsJson = savedDetails(lines), frozenPolygon = frozen
                )
                val repository = EstimateRepository(db)
                val dao = EstimateDao(repository)
                val id = dao.insert(estimate)
                sourceJson = checkNotNull(repository.originalRevisionInputForId(id))
                val source = JSONObject(sourceJson)
                assertEquals(3L, source.getLong("geometrySchemaVersion"))
                assertEquals(6, source.getJSONArray("sides").length())
                assertEquals("LEFT",
                    source.getJSONArray("sides").getJSONObject(2).getString("turnAfter"))
                assertEquals(frozen, FrozenPolygonGeometryJson.read(source, 0.01))
                val stored = dao.observeAll().first().single()
                assertEquals(frozen, stored.frozenPolygon)
                val copyId = dao.insert(
                    stored.copy(id = 0L, name = "Copy of polygon", createdAt = 160L)
                )
                val copied = JSONObject(checkNotNull(
                    repository.originalRevisionInputForId(copyId)
                ))
                assertEquals(3L, copied.getLong("geometrySchemaVersion"))
                assertEquals(frozen, FrozenPolygonGeometryJson.read(copied, 0.01))
                assertEquals(2L, repository.count())

                seriesUuid = checkNotNull(repository.find(id)?.seriesUuid)
                val history = ImmutableEstimateRepository(db)
                val v1 = history.revisions(seriesUuid).single()
                val assembler = EstimateRevisionSnapshotAssembler(db)
                val proposedV2 = assembler.prepareLatestSimpleRectangle(seriesUuid)
                assertEquals(388.0, proposedV2.draft.totalCost, 0.0)
                assertEquals(sourceJson, proposedV2.draft.sourceMeasurementJson)
                val v2 = assembler.confirmPreparedRectangle(proposedV2, createdAt = 220L)
                assertEquals(2L, v2.versionNumber)

                prices.upsertReturningId(
                    id = canvasId, code = "canvas.pvc_matte",
                    title = "Canvas v3 price", category = "canvas",
                    unit = "м²", price = 21.0, updatedAt = 230L
                )
                val proposedV3 = assembler.prepareLatestSimpleRectangle(seriesUuid)
                assertEquals(402.0, proposedV3.draft.totalCost, 0.0)
                val v3 = assembler.confirmPreparedRectangle(proposedV3, createdAt = 240L)
                assertEquals(3L, v3.versionNumber)
                assertEquals(listOf(230.0, 388.0, 402.0),
                    history.revisions(seriesUuid).map { it.totalCost })
                assertEquals(sourceJson,
                    history.revision(v3.revisionUuid)!!.sourceMeasurementJson)
                revisionIds = listOf(v1.revisionUuid, v2.revisionUuid, v3.revisionUuid)
                assertEquals("Original canvas",
                    history.lines(v1.revisionUuid).first().titleSnapshot)
            }
            createEncryptedPrototypeDriver(
                context, key, dbName, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val history = ImmutableEstimateRepository(db)
                assertEquals(listOf(230.0, 388.0, 402.0),
                    history.revisions(seriesUuid).map { it.totalCost })
                revisionIds.forEachIndexed { index, revisionId ->
                    val snapshot = loadImmutableEstimatePdfSnapshot(
                        db, seriesUuid, revisionId
                    )
                    assertEquals((index + 1).toLong(), snapshot.versionNumber)
                    assertEquals(frozen, snapshot.estimate.frozenPolygon)
                    assertEquals(sourceJson, history.revision(revisionId)!!
                        .sourceMeasurementJson)
                    val output = File.createTempFile(
                        "side_polygon_v3_${index}_", ".pdf", context.cacheDir
                    )
                    outputFiles += output
                    writeEstimatePdf(
                        context, Uri.fromFile(output), snapshot.estimate,
                        snapshot.projectTitle, snapshot.versionNumber
                    )
                    assertTrue(output.length() > 100L)
                    ParcelFileDescriptor.open(
                        output, ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { handle ->
                        PdfRenderer(handle).use { pdf ->
                            assertTrue(pdf.pageCount > 0)
                        }
                    }
                }
                val source = JSONObject(sourceJson)
                source.getJSONArray("sides").getJSONObject(0).put("lengthMm", 5100L)
                assertTrue(runCatching {
                    FrozenPolygonGeometryJson.read(source, 0.01)
                }.isFailure)
                source.getJSONArray("sides").getJSONObject(0)
                    .put("lengthMm", 5000L).put("turnAfter", "LEFT")
                assertTrue(runCatching {
                    FrozenPolygonGeometryJson.read(source, 0.01)
                }.isFailure)
                assertFalse(sourceJson.contains("5100"))
            }
        } finally {
            outputFiles.forEach { it.delete() }
            context.deleteDatabase(dbName)
            key.fill(0)
        }
    }
}
