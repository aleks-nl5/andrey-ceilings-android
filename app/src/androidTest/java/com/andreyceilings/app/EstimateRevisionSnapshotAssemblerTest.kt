package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.EstimateRevisionSnapshotAssembler
import com.andreyceilings.app.data.db.EstimateDao
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.estimate.VerifiedCeilingGeometry
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.app.data.db.FrozenPolygonGeometryJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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

/**
 * Tests actual encrypted SQLDelight snapshots, not synthetic Android UI state.
 * All changes remain inside disposable WAL databases and create NO v2 row.
 */
@RunWith(AndroidJUnit4::class)
class EstimateRevisionSnapshotAssemblerTest {
    private fun sourceJson(): String = JSONObject()
        .put("shape", "rectangle").put("geometrySchemaVersion", 1L)
        .put("name", "Kitchen").put("material", "pvc_matte")
        .put("profileType", "standard_alu").put("complexity", "straight")
        .put("lengthMeters", 2.0).put("widthMeters", 3.0)
        .put("areaSquareMeters", 6.0).put("perimeterMeters", 10.0).toString()

    private fun detailsJson(
        lines: List<ImmutableEstimateLine>,
        optionValue: Double = 0.0
    ): String {
        val details = JSONObject().put("measurementPrecisionMeters", 0.01)
        EstimateOptionId.entries.forEach { option ->
            details.put(option.code, if (option == EstimateOptionId.SPOT) optionValue else 0.0)
        }
        val frozen = JSONArray()
        lines.forEach { row ->
            frozen.put(JSONObject()
                .put("code", row.stableCode).put("title", row.title)
                .put("unit", row.unit).put("quantity", row.quantity)
                .put("unitPrice", row.unitPrice).put("total", row.lineTotal))
        }
        return details.put("lineItems", frozen).toString()
    }

    @Test fun encryptedWalV1SourceProducesConsistentWriteFreeRepricedSnapshot() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_revision_snapshot_assembler.db"
        val key = ByteArray(32) { 0x63.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(
                context, key, name, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Kitchen estimate", createdAt = 10L
                )
                val prices = PriceRepository(db)
                prices.upsertReturningId(
                    code = "canvas.pvc_matte", title = "New canvas",
                    category = "canvas", unit = "м²", price = 20.0, updatedAt = 30L
                )
                prices.upsertReturningId(
                    code = "profile.standard_alu", title = "New aluminium",
                    category = "profile", unit = "м.п.", price = 6.0, updatedAt = 30L
                )
                val original = listOf(
                    ImmutableEstimateLine(
                        "canvas.pvc_matte", "Old canvas", "м²", 6.0, 10.0, 60.0
                    ),
                    ImmutableEstimateLine(
                        "profile.standard_alu", "Old aluminium", "м.п.", 10.0, 5.0, 50.0
                    )
                )
                val history = ImmutableEstimateRepository(db)
                val saved = ImmutableEstimateDraft(
                    sourceMeasurementJson = sourceJson(),
                    detailsJson = detailsJson(original),
                    measurementPrecisionMeters = 0.01,
                    calculationRuleVersion = "kmp-v1",
                    totalCost = 110.0,
                    lines = original
                )
                val created = history.createSeries(
                    projectId = projectId, year = 2026,
                    createdAt = 20L, firstRevision = saved
                )
                val assembler = EstimateRevisionSnapshotAssembler(db)
                val prepared = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                assertEquals(created.seriesUuid, prepared.seriesUuid)
                assertEquals(created.initialRevisionUuid, prepared.sourceRevisionUuid)
                assertEquals(2L, prepared.proposedVersion)
                assertEquals(180.0, prepared.draft.totalCost, 0.0)
                assertEquals(listOf(120.0, 60.0),
                    prepared.draft.lines.map { it.lineTotal })
                assertEquals(saved.sourceMeasurementJson, prepared.draft.sourceMeasurementJson)
                val rebuilt = JSONObject(prepared.draft.detailsJson)
                assertEquals("New canvas",
                    rebuilt.getJSONArray("lineItems").getJSONObject(0).getString("title"))
                assertEquals(180.0, (0 until rebuilt.getJSONArray("lineItems").length())
                    .sumOf { rebuilt.getJSONArray("lineItems")
                        .getJSONObject(it).getDouble("total") }, 0.0)
                assertEquals(1, history.revisions(created.seriesUuid).size)
                assertEquals(110.0,
                    history.revision(created.initialRevisionUuid)!!.totalCost, 0.0)
                assertEquals("Old canvas",
                    history.lines(created.initialRevisionUuid).first().titleSnapshot)

                // Adding an option with no additional priced line must NOT
                // silently produce a future commercial document missing it.
                val withOption = saved.copy(detailsJson = detailsJson(original, optionValue = 1.0))
                val other = history.createSeries(
                    projectId = projectId, year = 2026,
                    createdAt = 21L, firstRevision = withOption
                )
                assertTrue(runCatching {
                    assembler.prepareLatestSimpleRectangle(other.seriesUuid)
                }.isFailure)

                // A real nonzero canonical option must be preserved in the
                // immutable snapshot AND in regenerated PDF detailsJson.
                prices.upsertReturningId(
                    code = EstimateOptionId.SPOT.code, title = "New spot",
                    category = "lighting", unit = "шт.", price = 25.0,
                    updatedAt = 31L
                )
                val optionLine = ImmutableEstimateLine(
                    EstimateOptionId.SPOT.code, "Old spot", "шт.", 2.0, 11.0, 22.0
                )
                val withPricedOption = saved.copy(
                    totalCost = 132.0,
                    lines = original + optionLine,
                    detailsJson = detailsJson(original + optionLine, optionValue = 2.0)
                )
                val optionSeries = history.createSeries(
                    projectId = projectId, year = 2026,
                    createdAt = 24L, firstRevision = withPricedOption
                )
                val preparedOption = assembler.prepareLatestSimpleRectangle(
                    optionSeries.seriesUuid
                )
                assertEquals(230.0, preparedOption.draft.totalCost, 0.0)
                assertEquals(listOf(120.0, 60.0, 50.0),
                    preparedOption.draft.lines.map { it.lineTotal })
                val optionDetails = JSONObject(preparedOption.draft.detailsJson)
                assertEquals(2.0, optionDetails.getDouble(EstimateOptionId.SPOT.code), 0.0)
                assertEquals(3, optionDetails.getJSONArray("lineItems").length())
                assertEquals(1, history.revisions(optionSeries.seriesUuid).size)
                assertEquals(132.0, history.revision(optionSeries.initialRevisionUuid)!!
                    .totalCost, 0.0)

                // Even with three saved lines, a mismatching option quantity
                // must not be treated as a trustworthy previous calculation.
                val mismatchedQuantity = history.createSeries(
                    projectId = projectId, year = 2026,
                    createdAt = 25L, firstRevision = withPricedOption.copy(
                        detailsJson = detailsJson(
                            original + optionLine, optionValue = 3.0
                        )
                    )
                )
                assertTrue(runCatching {
                    assembler.prepareLatestSimpleRectangle(
                        mismatchedQuantity.seriesUuid
                    )
                }.isFailure)

                val badDetails = JSONObject(saved.detailsJson)
                    .put("lineItems", JSONArray().put(JSONObject()
                        .put("code", original[0].stableCode)
                        .put("title", "Tampered historical canvas")
                        .put("unit", original[0].unit)
                        .put("quantity", original[0].quantity)
                        .put("unitPrice", original[0].unitPrice)
                        .put("total", original[0].lineTotal)))
                    .toString()
                val inconsistent = history.createSeries(
                    projectId = projectId, year = 2026,
                    createdAt = 22L, firstRevision = saved.copy(detailsJson = badDetails)
                )
                assertTrue(runCatching {
                    assembler.prepareLatestSimpleRectangle(inconsistent.seriesUuid)
                }.isFailure)
                val incompatibleSource = history.createSeries(
                    projectId = projectId, year = 2026,
                    createdAt = 23L,
                    firstRevision = saved.copy(sourceMeasurementJson = JSONObject(sourceJson())
                        .put("complexity", "multilevel").toString())
                )
                assertTrue(runCatching {
                    assembler.prepareLatestSimpleRectangle(incompatibleSource.seriesUuid)
                }.isFailure)
                assertEquals(1, history.revisions(created.seriesUuid).size)
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    @Test fun encryptedWalAtomicConfirmationRejectsStalePreviewAndRollsBack() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_revision_atomic_confirm.db"
        val key = ByteArray(32) { 0x4f.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(
                context, key, name, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Versioned project", createdAt = 100L
                )
                val prices = PriceRepository(db)
                prices.upsertReturningId(
                    code = "canvas.pvc_matte", title = "New canvas",
                    category = "canvas", unit = "м²", price = 20.0, updatedAt = 100L
                )
                prices.upsertReturningId(
                    code = "profile.standard_alu", title = "New profile",
                    category = "profile", unit = "м.п.", price = 6.0, updatedAt = 100L
                )
                val spotId = prices.upsertReturningId(
                    code = EstimateOptionId.SPOT.code, title = "New spot",
                    category = "lighting", unit = "шт.", price = 25.0, updatedAt = 100L
                )
                val original = listOf(
                    ImmutableEstimateLine(
                        "canvas.pvc_matte", "Old canvas", "м²", 6.0, 10.0, 60.0
                    ),
                    ImmutableEstimateLine(
                        "profile.standard_alu", "Old profile", "м.п.", 10.0, 5.0, 50.0
                    ),
                    ImmutableEstimateLine(
                        EstimateOptionId.SPOT.code, "Old spot", "шт.", 2.0, 11.0, 22.0
                    )
                )
                val v1 = ImmutableEstimateDraft(
                    sourceMeasurementJson = sourceJson(),
                    detailsJson = detailsJson(original, optionValue = 2.0),
                    measurementPrecisionMeters = 0.01,
                    calculationRuleVersion = "kmp-v1",
                    totalCost = 132.0, lines = original
                )
                val history = ImmutableEstimateRepository(db)
                val created = history.createSeries(
                    projectId = project, year = 2026, createdAt = 200L,
                    firstRevision = v1
                )
                val number = history.series(created.seriesUuid)!!.documentNumber
                val assembler = EstimateRevisionSnapshotAssembler(db)
                val v2Preview = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                assertEquals(1, history.revisions(created.seriesUuid).size)
                assertEquals(230.0, v2Preview.draft.totalCost, 0.0)

                val v2 = assembler.confirmPreparedRectangle(v2Preview, 300L)
                assertEquals(2L, v2.versionNumber)
                assertEquals(created.initialRevisionUuid,
                    history.revision(v2.revisionUuid)!!.parentRevisionUuid)
                assertEquals(230.0, history.revision(v2.revisionUuid)!!.totalCost, 0.0)
                assertEquals(132.0,
                    history.revision(created.initialRevisionUuid)!!.totalCost, 0.0)
                assertEquals("Old canvas",
                    history.lines(created.initialRevisionUuid).first().titleSnapshot)
                assertEquals(number, history.series(created.seriesUuid)!!.documentNumber)
                assertEquals(3, history.lines(v2.revisionUuid).size)

                // A second confirmation of the same v1 proposal cannot
                // create an extra v2 or overwrite the accepted v2.
                assertTrue(runCatching {
                    assembler.confirmPreparedRectangle(v2Preview, 301L)
                }.isFailure)
                assertEquals(2, history.revisions(created.seriesUuid).size)

                // An unrelated added price changes the full catalog token,
                // despite not changing the estimate's amount.
                val staleV3 = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                prices.upsertReturningId(
                    code = "work.unused", title = "Unrelated",
                    category = "work", unit = "шт.", price = 3.0, updatedAt = 101L
                )
                assertTrue(runCatching {
                    assembler.confirmPreparedRectangle(staleV3, 400L)
                }.isFailure)

                // Detect same-timestamp price edits, not just max(updatedAt).
                val oldPriceV3 = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                prices.upsertReturningId(
                    id = spotId, code = EstimateOptionId.SPOT.code,
                    title = "Renamed spot", category = "lighting",
                    unit = "шт.", price = 30.0, updatedAt = 100L
                )
                assertTrue(runCatching {
                    assembler.confirmPreparedRectangle(oldPriceV3, 401L)
                }.isFailure)
                assertEquals(2, history.revisions(created.seriesUuid).size)

                // Even a failure AFTER the header and lines were inserted
                // must roll back the complete nested transaction.
                val validV3 = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                assertEquals(240.0, validV3.draft.totalCost, 0.0)
                val deliberate = IllegalStateException("Injected after immutable INSERT")
                val interrupted = runCatching {
                    assembler.confirmPreparedRectangle(
                        validV3, 402L, afterInsertForTesting = { throw deliberate }
                    )
                }
                assertTrue(interrupted.exceptionOrNull() === deliberate)
                assertEquals(2, history.revisions(created.seriesUuid).size)

                val v3 = assembler.confirmPreparedRectangle(validV3, 403L)
                assertEquals(3L, v3.versionNumber)
                assertEquals(v2.revisionUuid,
                    history.revision(v3.revisionUuid)!!.parentRevisionUuid)
                assertEquals(240.0, history.revision(v3.revisionUuid)!!.totalCost, 0.0)
                assertEquals("Renamed spot",
                    history.lines(v3.revisionUuid).last().titleSnapshot)
                assertEquals(listOf(1L, 2L, 3L),
                    history.revisions(created.seriesUuid).map { it.versionNumber })
                assertEquals(number, history.series(created.seriesUuid)!!.documentNumber)
                assertEquals(132.0,
                    history.revision(created.initialRevisionUuid)!!.totalCost, 0.0)
                assertEquals(230.0, history.revision(v2.revisionUuid)!!.totalCost, 0.0)
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
    @Test fun encryptedWalLShapedRevisionV1ToV3PreservesEveryFrozenVersion() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_l_shaped_revision_assembler.db"
        val key = ByteArray(32) { 0x37.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(
                context, key, name, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(
                    clientId = null, title = "L-shaped ceiling", createdAt = 10L
                )
                val prices = PriceRepository(db)
                val canvasId = prices.upsertReturningId(
                    code = "canvas.pvc_matte", title = "New canvas",
                    category = "canvas", unit = "м²", price = 20.0, updatedAt = 30L
                )
                prices.upsertReturningId(
                    code = "profile.standard_alu", title = "New profile",
                    category = "profile", unit = "м.п.", price = 6.0, updatedAt = 30L
                )
                val original = listOf(
                    ImmutableEstimateLine(
                        "canvas.pvc_matte", "Old canvas", "м²", 14.0, 10.0, 140.0
                    ),
                    ImmutableEstimateLine(
                        "profile.standard_alu", "Old profile", "м.п.", 18.0, 5.0, 90.0
                    )
                )
                val frozenSource = JSONObject()
                    .put("name", "L-shaped ceiling")
                    .put("material", "pvc_matte")
                    .put("profileType", "standard_alu")
                    .put("complexity", "straight")
                    .put("shape", "l_shaped")
                    .put("geometrySchemaVersion", 1L)
                    .put("sideA", 5.0).put("sideB", 4.0)
                    .put("sideC", 2.0).put("sideD", 2.0)
                    .put("sideE", 3.0).put("sideF", 2.0)
                    .put("areaSquareMeters", 14.0)
                    .put("perimeterMeters", 18.0).toString()
                val saved = ImmutableEstimateDraft(
                    sourceMeasurementJson = frozenSource,
                    detailsJson = detailsJson(original),
                    measurementPrecisionMeters = 0.01,
                    calculationRuleVersion = "kmp-v1",
                    totalCost = 230.0,
                    lines = original
                )
                val history = ImmutableEstimateRepository(db)
                val created = history.createSeries(
                    projectId = project, year = 2026, createdAt = 20L,
                    firstRevision = saved
                )
                val documentNumber = history.series(created.seriesUuid)!!.documentNumber
                val assembler = EstimateRevisionSnapshotAssembler(db)
                val prepared = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                assertEquals(388.0, prepared.draft.totalCost, 0.0)
                assertEquals(listOf(280.0, 108.0),
                    prepared.draft.lines.map { it.lineTotal })
                assertEquals(frozenSource, prepared.draft.sourceMeasurementJson)
                assertEquals(1, history.revisions(created.seriesUuid).size)
                val v2 = assembler.confirmPreparedRectangle(prepared, createdAt = 31L)
                assertEquals(2L, v2.versionNumber)
                assertEquals(created.initialRevisionUuid,
                    history.revision(v2.revisionUuid)!!.parentRevisionUuid)
                assertEquals(230.0,
                    history.revision(created.initialRevisionUuid)!!.totalCost, 0.0)
                assertEquals("Old canvas",
                    history.lines(created.initialRevisionUuid).first().titleSnapshot)
                assertTrue(runCatching {
                    assembler.confirmPreparedRectangle(prepared, createdAt = 32L)
                }.isFailure)

                prices.upsertReturningId(
                    id = canvasId, code = "canvas.pvc_matte",
                    title = "Canvas v3", category = "canvas",
                    unit = "м²", price = 21.0, updatedAt = 40L
                )
                val next = assembler.prepareLatestSimpleRectangle(created.seriesUuid)
                assertEquals(402.0, next.draft.totalCost, 0.0)
                val v3 = assembler.confirmPreparedRectangle(next, createdAt = 41L)
                assertEquals(3L, v3.versionNumber)
                assertEquals(v2.revisionUuid,
                    history.revision(v3.revisionUuid)!!.parentRevisionUuid)
                assertEquals(listOf(230.0, 388.0, 402.0),
                    history.revisions(created.seriesUuid).map { it.totalCost })
                assertEquals(documentNumber,
                    history.series(created.seriesUuid)!!.documentNumber)
                assertEquals(frozenSource,
                    history.revision(v3.revisionUuid)!!.sourceMeasurementJson)

                // An L-shape with a missing side, false closure or stale area
                // never becomes an accepted revision, even in a disposable DB.
                for (bad in listOf(
                    JSONObject(frozenSource).apply { remove("sideE") },
                    JSONObject(frozenSource).put("sideE", 3.5),
                    JSONObject(frozenSource).put("areaSquareMeters", 13.0)
                )) {
                    val invalid = history.createSeries(
                        projectId = project, year = 2026,
                        createdAt = 50L + history.revisions(created.seriesUuid).size,
                        firstRevision = saved.copy(sourceMeasurementJson = bad.toString())
                    )
                    assertTrue(runCatching {
                        assembler.prepareLatestSimpleRectangle(invalid.seriesUuid)
                    }.isFailure)
                    assertEquals(1, history.revisions(invalid.seriesUuid).size)
                }
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    @Test fun androidFirstSaveFreezesActualSixSidesAndRepricesWithoutMutatingV1() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_l_shaped_first_save.db"
        val key = ByteArray(32) { 0x51.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(
                context, key, name, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Saved L geometry", createdAt = 100L
                )
                val prices = PriceRepository(db)
                prices.upsertReturningId(
                    code = "canvas.pvc_matte", title = "Updated canvas",
                    category = "canvas", unit = "м²", price = 20.0, updatedAt = 120L
                )
                prices.upsertReturningId(
                    code = "profile.standard_alu", title = "Updated profile",
                    category = "profile", unit = "м.п.", price = 6.0, updatedAt = 120L
                )
                val frozen = VerifiedCeilingGeometry.capturedLShaped(
                    listOf(5.0, 4.0, 2.0, 2.0, 3.0, 2.0), 0.01
                )
                val oldLines = listOf(
                    ImmutableEstimateLine("canvas.pvc_matte", "Original canvas",
                        "м²", 14.0, 10.0, 140.0),
                    ImmutableEstimateLine("profile.standard_alu", "Original profile",
                        "м.п.", 18.0, 5.0, 90.0)
                )
                val input = EstimateEntity(
                    projectId = projectId, name = "Real L measurement",
                    material = "pvc_matte", profileType = "standard_alu",
                    complexity = "straight",
                    lengthMeters = frozen.sideA, widthMeters = frozen.sideB,
                    areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    canvasCost = 140.0, profileCost = 90.0,
                    complexityCost = 0.0, optionsCost = 0.0,
                    totalCost = 230.0, detailsJson = detailsJson(oldLines),
                    frozenGeometryInput = frozen
                )
                val compat = EstimateRepository(db)
                val id = EstimateDao(compat).insert(input)
                val frozenJson = JSONObject(compat.originalRevisionInputForId(id)!!)
                assertEquals("l_shaped", frozenJson.getString("shape"))
                assertEquals(1L, frozenJson.getLong("geometrySchemaVersion"))
                listOf("sideA", "sideB", "sideC", "sideD", "sideE", "sideF")
                    .forEach { side -> assertTrue(frozenJson.has(side)) }
                assertEquals(14.0, frozenJson.getDouble("areaSquareMeters"), 0.0)
                assertEquals(18.0, frozenJson.getDouble("perimeterMeters"), 0.0)
                assertTrue(!frozenJson.has("lengthMeters"))
                assertTrue(!frozenJson.has("widthMeters"))
                val seriesUuid = checkNotNull(compat.find(id)?.seriesUuid)
                val history = ImmutableEstimateRepository(db)
                val v1 = history.revisions(seriesUuid).single()
                assertEquals(230.0, v1.totalCost, 0.0)
                val prepared = EstimateRevisionSnapshotAssembler(db)
                    .prepareLatestSimpleRectangle(seriesUuid)
                assertEquals(388.0, prepared.draft.totalCost, 0.0)
                val v2 = EstimateRevisionSnapshotAssembler(db)
                    .confirmPreparedRectangle(prepared, createdAt = 150L)
                assertEquals(2L, v2.versionNumber)
                assertEquals(230.0, history.revision(v1.revisionUuid)!!.totalCost, 0.0)
                assertEquals("Original canvas",
                    history.lines(v1.revisionUuid).first().titleSnapshot)
                assertEquals(v1.sourceMeasurementJson,
                    history.revision(v2.revisionUuid)!!.sourceMeasurementJson)

                // Bad metadata cannot be inserted as a new commercial v1.
                assertTrue(runCatching {
                    EstimateDao(compat).insert(
                        input.copy(
                            areaSquareMeters = 13.0,
                            name = "Invalid L room"
                        )
                    )
                }.isFailure)
                assertEquals(1L, compat.count())

                // The Android list adapter must retain frozen sides too:
                // otherwise copying a valid L estimate would silently turn
                // its immutable source into a rectangle.
                val reloaded = EstimateDao(compat).observeAll().first().single()
                assertEquals(frozen, reloaded.frozenGeometryInput)
                val duplicateId = EstimateDao(compat).insert(
                    reloaded.copy(
                        id = 0L, name = "L-shaped copy", createdAt = 160L
                    )
                )
                val duplicateSource = JSONObject(
                    compat.originalRevisionInputForId(duplicateId)!!
                )
                assertEquals("l_shaped", duplicateSource.getString("shape"))
                assertEquals(3.0, duplicateSource.getDouble("sideE"), 0.0)
                assertEquals(2L, compat.count())
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

    @Test fun encryptedWalPolygonV2FirstSaveCopyV2V3AndTamperingFailClosed() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "sqldelight_test_polygon_v2_first_save.db"
        val key = ByteArray(32) { 0x6A.toByte() }
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(
                context, key, name, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Polygon geometry v2", createdAt = 100L
                )
                val prices = PriceRepository(db)
                val canvasId = prices.upsertReturningId(
                    code = "canvas.pvc_matte", title = "Repriced canvas",
                    category = "canvas", unit = "м²", price = 20.0, updatedAt = 200L
                )
                prices.upsertReturningId(
                    code = "profile.standard_alu", title = "Repriced profile",
                    category = "profile", unit = "м.п.", price = 6.0, updatedAt = 200L
                )
                val inputsMm = mapOf(
                    "A" to 5000L, "B" to 4000L,
                    "C" to 2000L, "D" to 2000L
                )
                val polygon = FrozenPolygonGeometryContract.capture(
                    "l_shaped",
                    CeilingShapeTemplates.fromMeasurements(
                        "l_shaped", inputsMm
                    ).vertices,
                    inputsMm, 0.01
                )
                val initialLines = listOf(
                    ImmutableEstimateLine(
                        "canvas.pvc_matte", "Canvas v1", "м²",
                        14.0, 10.0, 140.0
                    ),
                    ImmutableEstimateLine(
                        "profile.standard_alu", "Profile v1", "м.п.",
                        18.0, 5.0, 90.0
                    )
                )
                val row = EstimateEntity(
                    projectId = projectId, name = "L polygon v2",
                    material = "pvc_matte", profileType = "standard_alu",
                    complexity = "straight",
                    lengthMeters = 5.0, widthMeters = 4.0,
                    areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    canvasCost = 140.0, profileCost = 90.0,
                    complexityCost = 0.0, optionsCost = 0.0,
                    totalCost = 230.0, detailsJson = detailsJson(initialLines),
                    frozenPolygon = polygon
                )
                val compat = EstimateRepository(db)
                val dao = EstimateDao(compat)
                val id = dao.insert(row)
                val source = compat.originalRevisionInputForId(id)!!
                val parsed = JSONObject(source)
                assertEquals(2L, parsed.getLong("geometrySchemaVersion"))
                assertEquals("l_shaped", parsed.getString("shape"))
                assertEquals(6, parsed.getJSONArray("vertices").length())
                assertEquals(4, parsed.getJSONObject("measurementsMm").length())
                assertEquals(polygon, FrozenPolygonGeometryJson.read(parsed, 0.01))
                assertTrue(!parsed.has("sideE"))
                assertTrue(!parsed.has("lengthMeters"))
                val loaded = dao.observeAll().first().single()
                assertEquals(polygon, loaded.frozenPolygon)
                val copyId = dao.insert(
                    loaded.copy(id = 0L, name = "L copy", createdAt = 160L)
                )
                val copySource = JSONObject(
                    compat.originalRevisionInputForId(copyId)!!
                )
                assertEquals(2L, copySource.getLong("geometrySchemaVersion"))
                assertEquals(polygon,
                    FrozenPolygonGeometryJson.read(copySource, 0.01))
                val history = ImmutableEstimateRepository(db)
                val seriesUuid = checkNotNull(compat.find(id)?.seriesUuid)
                val original = history.revisions(seriesUuid).single()
                assertEquals(FrozenPolygonGeometryContract.CALCULATION_RULE_VERSION,
                    original.calculationRuleVersion)
                val assembler = EstimateRevisionSnapshotAssembler(db)
                val prepared = assembler.prepareLatestSimpleRectangle(seriesUuid)
                assertEquals(388.0, prepared.draft.totalCost, 0.0)
                assertEquals(source, prepared.draft.sourceMeasurementJson)
                val v2 = assembler.confirmPreparedRectangle(prepared, createdAt = 220L)
                assertEquals(2L, v2.versionNumber)
                prices.upsertReturningId(
                    id = canvasId, code = "canvas.pvc_matte",
                    title = "Canvas v3", category = "canvas",
                    unit = "м²", price = 21.0, updatedAt = 230L
                )
                val preparedV3 = assembler.prepareLatestSimpleRectangle(seriesUuid)
                assertEquals(402.0, preparedV3.draft.totalCost, 0.0)
                val v3 = assembler.confirmPreparedRectangle(preparedV3, createdAt = 240L)
                assertEquals(3L, v3.versionNumber)
                assertEquals(listOf(230.0, 388.0, 402.0),
                    history.revisions(seriesUuid).map { it.totalCost })
                assertEquals(source,
                    history.revision(v3.revisionUuid)!!.sourceMeasurementJson)
                assertEquals("Canvas v1",
                    history.lines(original.revisionUuid).first().titleSnapshot)
                val badValues = listOf(
                    polygon.copy(vertices = polygon.vertices.reversed()),
                    polygon.copy(measurementsMm = polygon.measurementsMm - "C"),
                    polygon.copy(areaSquareMeters = 13.0)
                )
                badValues.forEach { corrupted ->
                    assertTrue(runCatching {
                        dao.insert(row.copy(
                            name = "Invalid geometry",
                            frozenPolygon = corrupted
                        ))
                    }.isFailure)
                }
                assertEquals(2L, compat.count())
            }
        } finally {
            context.deleteDatabase(name)
            key.fill(0)
        }
    }

}
