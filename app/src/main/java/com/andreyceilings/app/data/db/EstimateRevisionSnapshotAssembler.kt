package com.andreyceilings.app.data.db

import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.estimate.EstimateRecalculationRepository
import com.andreyceilings.shared.estimate.EstimateRevisionCandidates
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.estimate.VerifiedRectangleInputs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges the persisted Android v1 snapshot to KMP's write-free revision
 * candidate, without reconstructing missing historical values.
 *
 * Strictly scoped to an ordinary rectangle with two frozen lines and NO
 * priced or unpriced options. This is a read-only preparation API, NOT the
 * v2/v3 confirmation path. It cannot change any SQLDelight records.
 */
internal class EstimateRevisionSnapshotAssembler(private val database: PrototypeDatabase) {
    internal data class Prepared(
        val seriesUuid: String,
        val sourceRevisionUuid: String,
        val proposedVersion: Long,
        val draft: ImmutableEstimateDraft
    )

    fun prepareLatestSimpleRectangle(seriesUuid: String): Prepared {
        val history = ImmutableEstimateRepository(database)
        val series = checkNotNull(history.series(seriesUuid)) { "Estimate series not found" }
        check(series.deletedAt == null) { "Deleted estimate series cannot be revised" }
        val latest = checkNotNull(history.revisions(seriesUuid).lastOrNull()) {
            "Estimate series has no saved revision"
        }
        val savedLines = history.lines(latest.revisionUuid).map { row ->
            ImmutableEstimateLine(
                stableCode = row.stableCode,
                title = row.titleSnapshot,
                unit = row.unitSnapshot,
                quantity = row.quantitySnapshot,
                unitPrice = row.unitPriceSnapshot,
                lineTotal = row.lineTotalSnapshot,
                priceItemUuid = row.priceItemUuid
            )
        }
        val saved = ImmutableEstimateDraft(
            sourceMeasurementJson = latest.sourceMeasurementJson,
            detailsJson = latest.detailsJson,
            measurementPrecisionMeters = latest.measurementPrecisionMeters,
            calculationRuleVersion = latest.calculationRuleVersion,
            totalCost = latest.totalCost,
            lines = savedLines,
            currency = latest.currency,
            moneyRoundingPolicy = latest.moneyRoundingPolicy
        )

        val source = JSONObject(saved.sourceMeasurementJson)
        val details = JSONObject(saved.detailsJson)
        // Current Android v1 saves exactly these fields. Reject missing,
        // unknown or newly introduced inputs until they have a tested replay
        // mapping. In particular never treat a missing complexity as straight.
        val sourceFields = setOf(
            "name", "material", "profileType", "complexity", "lengthMeters",
            "widthMeters", "areaSquareMeters", "perimeterMeters"
        )
        val actualSourceKeys = source.keys().asSequence().toSet()
        check(actualSourceKeys == sourceFields) {
            "Incomplete or unsupported frozen measurement inputs"
        }
        check(source.getString("name").isNotBlank())
        check(source.getString("complexity") == "straight") {
            "Complex ceiling requires full KMP options calculation"
        }
        check(source.getString("material").isNotBlank() &&
            source.getString("profileType").isNotBlank())

        val optionKeys = EstimateOptionId.entries.map { it.code }.toSet()
        val detailFields = optionKeys + setOf("measurementPrecisionMeters", "lineItems")
        check(details.keys().asSequence().toSet() == detailFields) {
            "Missing or unrecognized saved estimate options"
        }
        for (code in optionKeys) {
            // Even an unpriced historical option changes the geometry or
            // included works. Never silently drop it when saving a new v2.
            val amount = details.getDouble(code)
            check(amount.isFinite() && amount == 0.0) {
                "Nonzero option $code requires full KMP calculation"
            }
        }
        val precision = details.getDouble("measurementPrecisionMeters")
        check(precision == latest.measurementPrecisionMeters) {
            "Frozen measurement precision differs between snapshots"
        }
        check(savedLines.size == 2)
        val canvas = savedLines.singleOrNull { it.stableCode.startsWith("canvas.") }
            ?: error("Exactly one frozen canvas line is required")
        val profile = savedLines.singleOrNull { it.stableCode.startsWith("profile.") }
            ?: error("Exactly one frozen profile line is required")
        check(canvas.stableCode == "canvas.${source.getString("material")}" &&
            profile.stableCode == "profile.${source.getString("profileType")}") {
            "Frozen price codes differ from measured material/profile"
        }

        // A second independent check: original detailsJson must describe
        // exactly the rows and totals of the immutable SQLDelight history.
        // A price-only preview is NOT enough to validate PDF/details content.
        val historicalItems = details.getJSONArray("lineItems")
        check(historicalItems.length() == savedLines.size) {
            "Saved detailed line items differ from immutable history"
        }
        savedLines.forEachIndexed { index, line ->
            val entry = historicalItems.getJSONObject(index)
            check(entry.keys().asSequence().toSet() ==
                setOf("code", "title", "unit", "quantity", "unitPrice", "total") &&
                entry.getString("code") == line.stableCode &&
                entry.getString("title") == line.title &&
                entry.getString("unit") == line.unit &&
                entry.getDouble("quantity") == line.quantity &&
                entry.getDouble("unitPrice") == line.unitPrice &&
                entry.getDouble("total") == line.lineTotal) {
                "Immutable history and stored detailsJson line #$index disagree"
            }
        }

        val preview = EstimateRecalculationRepository(database).previewLatest(seriesUuid)
        check(preview.baseRevisionUuid == latest.revisionUuid) {
            "Source revision changed while preparing a candidate"
        }
        val candidate = EstimateRevisionCandidates.prepareSimpleRectangle(
            saved,
            VerifiedRectangleInputs(
                lengthMeters = source.getDouble("lengthMeters"),
                widthMeters = source.getDouble("widthMeters"),
                storedAreaSquareMeters = source.getDouble("areaSquareMeters"),
                storedPerimeterMeters = source.getDouble("perimeterMeters"),
                precisionStepMeters = precision
            ),
            preview
        )
        val proposedLines = JSONArray()
        candidate.lines.forEach { line ->
            proposedLines.put(JSONObject()
                .put("code", line.stableCode)
                .put("title", line.title)
                .put("unit", line.unit)
                .put("quantity", line.quantity)
                .put("unitPrice", line.unitPrice)
                .put("total", line.lineTotal))
        }

        // Do not mutate the original JSON. Retain exact saved measurement
        // inputs and all explicitly-zero options. Replace only lineItems with
        // the independently verified new frozen prices and totals.
        val rebuiltDetails = JSONObject(saved.detailsJson)
            .put("lineItems", proposedLines)
            .toString()
        val draft = saved.copy(
            detailsJson = rebuiltDetails,
            totalCost = candidate.totalCost,
            lines = candidate.lines
        )
        check(JSONObject(draft.detailsJson).getJSONArray("lineItems").length() == draft.lines.size)
        return Prepared(
            seriesUuid = seriesUuid,
            sourceRevisionUuid = candidate.baseRevisionUuid,
            proposedVersion = candidate.proposedVersionNumber,
            draft = draft
        )
    }
}
