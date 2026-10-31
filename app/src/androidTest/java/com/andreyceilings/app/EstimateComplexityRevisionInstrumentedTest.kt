package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.EstimateRevisionSnapshotAssembler
import com.andreyceilings.shared.calculation.EstimateOptionId
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted WAL proof for v2 multilevel/curved construction plus options. */
@RunWith(AndroidJUnit4::class)
class EstimateComplexityRevisionInstrumentedTest {
    private fun draft(
        complexity: String,
        workLength: Double,
        oldWorkPrice: Double,
        optionsQuantity: Double = 2.0
    ): ImmutableEstimateDraft {
        val lines = listOf(
            ImmutableEstimateLine(
                "canvas.pvc_matte", "Old canvas", "м²", 6.0, 10.0, 60.0
            ),
            ImmutableEstimateLine(
                "profile.standard_alu", "Old profile", "м.п.", 10.0, 5.0, 50.0
            ),
            ImmutableEstimateLine(
                "work.$complexity", "Old construction", "м.п.",
                workLength, oldWorkPrice, workLength * oldWorkPrice
            ),
            ImmutableEstimateLine(
                EstimateOptionId.SPOT.code, "Old spot", "шт.",
                optionsQuantity, 11.0, optionsQuantity * 11.0
            )
        )
        val source = JSONObject()
            .put("name", "Room").put("material", "pvc_matte")
            .put("profileType", "standard_alu")
            .put("complexity", complexity)
            .put("lengthMeters", 2.0).put("widthMeters", 3.0)
            .put("areaSquareMeters", 6.0).put("perimeterMeters", 10.0)
        val details = JSONObject().put("measurementPrecisionMeters", 0.01)
        EstimateOptionId.entries.forEach { key ->
            details.put(key.code, if (key == EstimateOptionId.SPOT) optionsQuantity else 0.0)
        }
        val items = JSONArray()
        lines.forEach { line ->
            items.put(JSONObject()
                .put("code", line.stableCode).put("title", line.title)
                .put("unit", line.unit).put("quantity", line.quantity)
                .put("unitPrice", line.unitPrice).put("total", line.lineTotal))
        }
        return ImmutableEstimateDraft(
            sourceMeasurementJson = source.toString(),
            detailsJson = details.put("lineItems", items).toString(),
            measurementPrecisionMeters = 0.01,
            calculationRuleVersion = "kmp-v1",
            totalCost = lines.sumOf { it.lineTotal },
            lines = lines
        )
    }

    @Test fun encryptedWalConfirmsBothComplexityTypesAndKeepsPreviousVersion() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val filename = "sqldelight_test_complex_revisions.db"
        val key = ByteArray(32) { 0x47.toByte() }
        context.deleteDatabase(filename)
        try {
            createEncryptedPrototypeDriver(
                context, key, filename, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Complexity work", createdAt = 10L
                )
                val prices = PriceRepository(db)
                listOf(
                    Triple("canvas.pvc_matte", "м²", 20.0),
                    Triple("profile.standard_alu", "м.п.", 6.0),
                    Triple("work.multilevel", "м.п.", 200.0),
                    Triple("work.curved", "м.п.", 100.0),
                    Triple(EstimateOptionId.SPOT.code, "шт.", 25.0)
                ).forEach { (code, unit, price) ->
                    prices.upsertReturningId(
                        code = code, title = "Repriced $code",
                        category = code.substringBefore('.'),
                        unit = unit, price = price, updatedAt = 100L
                    )
                }
                val history = ImmutableEstimateRepository(db)
                val assembler = EstimateRevisionSnapshotAssembler(db)
                for ((complexity, length, oldPrice, newPrice) in listOf(
                    listOf("multilevel", "3.5", "100.0", "200.0"),
                    listOf("curved", "2.5", "80.0", "100.0")
                )) {
                    val workLength = length.toDouble()
                    val firstDraft = draft(complexity, workLength, oldPrice.toDouble())
                    val created = history.createSeries(
                        projectId = project, year = 2026, createdAt = 200L,
                        firstRevision = firstDraft
                    )
                    val number = history.series(created.seriesUuid)!!.documentNumber
                    val prepared = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                    val expected = 230.0 + workLength * newPrice.toDouble()
                    assertEquals(expected, prepared.draft.totalCost, 0.0)
                    assertEquals(
                        listOf(120.0, 60.0, workLength * newPrice.toDouble(), 50.0),
                        prepared.draft.lines.map { it.lineTotal }
                    )
                    val confirmed = assembler.confirmPreparedRectangle(prepared, 300L)
                    assertEquals(2L, confirmed.versionNumber)
                    assertEquals(expected,
                        history.revision(confirmed.revisionUuid)!!.totalCost, 0.0)
                    assertEquals(firstDraft.totalCost,
                        history.revision(created.initialRevisionUuid)!!.totalCost, 0.0)
                    assertEquals(number,
                        history.series(created.seriesUuid)!!.documentNumber)
                    assertEquals(
                        listOf("Old canvas", "Old profile", "Old construction", "Old spot"),
                        history.lines(created.initialRevisionUuid)
                            .map { it.titleSnapshot }
                    )
                    assertTrue(runCatching {
                        assembler.confirmPreparedRectangle(prepared, 301L)
                    }.isFailure)
                    assertEquals(2, history.revisions(created.seriesUuid).size)

                    // Fraudulent lengths or missing work lines must fail
                    // before any immutable INSERT.
                    val inconsistent = draft(
                        complexity, workLength + 0.5, oldPrice.toDouble()
                    )
                    val unsafe = history.createSeries(
                        projectId = project, year = 2026, createdAt = 201L,
                        firstRevision = inconsistent
                    )
                    assertTrue(runCatching {
                        assembler.prepareLatestSimpleRectangle(unsafe.seriesUuid)
                    }.isFailure)
                    assertEquals(1, history.revisions(unsafe.seriesUuid).size)
                }
            }
        } finally {
            context.deleteDatabase(filename)
            key.fill(0)
        }
    }
}
