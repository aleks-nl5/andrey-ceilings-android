package com.andreyceilings.app.data.db

import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.estimate.EstimateRecalculationPreview
import com.andreyceilings.shared.estimate.EstimateRecalculationRepository
import com.andreyceilings.shared.estimate.EstimateRevisionCandidates
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.estimate.VerifiedLShapedInputs
import com.andreyceilings.shared.estimate.VerifiedRectangleInputs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges the persisted Android v1 snapshot to KMP's write-free revision
 * candidate, without reconstructing missing historical values.
 *
 * Only fully saved rectangle or orthogonal six-side L-shaped geometry is
 * accepted, with canonical options and frozen complexity work. No inferred
 * polygon sides, legacy rounding, missing works or historical fallbacks.
 * Preparation is read-only; confirmation rechecks in one SQLDelight transaction.
 */
internal class EstimateRevisionSnapshotAssembler(private val database: PrototypeDatabase) {
    internal data class Prepared(
        val seriesUuid: String,
        val sourceRevisionUuid: String,
        val proposedVersion: Long,
        val draft: ImmutableEstimateDraft,
        /** Full source-revision, catalog and repriced line snapshot at preview. */
        val preview: EstimateRecalculationPreview
    )

    internal data class Confirmed(
        val seriesUuid: String,
        val revisionUuid: String,
        val versionNumber: Long
    )

    /**
     * Explicit confirmation entry point for verified frozen geometries.
     * All validation and INSERTs share ONE SQLDelight write transaction.
     *
     * Do not trust the supplied snapshot: regenerate the entire preview and
     * candidate against the current revision and complete current catalog.
     * Failure at any point rolls back revision header and all child lines.
     */
    internal fun confirmPreparedRectangle(
        prepared: Prepared,
        createdAt: Long,
        afterInsertForTesting: (() -> Unit)? = null
    ): Confirmed {
        require(createdAt > 0L)
        return database.transactionWithResult {
            val previewNow = EstimateRecalculationRepository(database)
                .previewLatest(prepared.seriesUuid)
            check(previewNow.canConfirm && previewNow == prepared.preview) {
                "Stale preview: immutable source revision or catalog changed"
            }
            val fresh = prepareLatestSimpleRectangle(prepared.seriesUuid)
            check(fresh == prepared &&
                fresh.proposedVersion == previewNow.baseVersionNumber + 1L) {
                "Stale or modified candidate snapshot"
            }
            val history = ImmutableEstimateRepository(database)
            val id = history.appendRevision(
                seriesUuid = prepared.seriesUuid,
                expectedLatestRevisionUuid = prepared.sourceRevisionUuid,
                createdAt = createdAt,
                snapshot = fresh.draft
            )
            afterInsertForTesting?.invoke()
            val inserted = checkNotNull(history.revision(id))
            check(inserted.seriesUuid == prepared.seriesUuid &&
                inserted.parentRevisionUuid == prepared.sourceRevisionUuid &&
                inserted.versionNumber == prepared.proposedVersion &&
                inserted.totalCost == prepared.draft.totalCost &&
                inserted.detailsJson == prepared.draft.detailsJson &&
                inserted.sourceMeasurementJson == prepared.draft.sourceMeasurementJson) {
                "Persisted immutable revision differs from confirmed candidate"
            }
            val rows = history.lines(id).map { row ->
                ImmutableEstimateLine(
                    stableCode = row.stableCode, title = row.titleSnapshot,
                    unit = row.unitSnapshot, quantity = row.quantitySnapshot,
                    unitPrice = row.unitPriceSnapshot, lineTotal = row.lineTotalSnapshot,
                    priceItemUuid = row.priceItemUuid
                )
            }
            check(rows == prepared.draft.lines) {
                "Persisted revision lines differ from confirmed candidate"
            }
            Confirmed(prepared.seriesUuid, id, inserted.versionNumber)
        }
    }

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
        // Recognize complete persisted schemas only. The shape is never
        // reconstructed from area/perimeter or from a live measurement.
        val commonSourceFields = setOf(
            "name", "material", "profileType", "complexity",
            "areaSquareMeters", "perimeterMeters"
        )
        val rectangleSourceFields = commonSourceFields + setOf(
            "lengthMeters", "widthMeters"
        )
        val lShapedSourceFields = commonSourceFields + setOf(
            "shape", "sideA", "sideB", "sideC", "sideD", "sideE", "sideF"
        )
        val actualSourceKeys = source.keys().asSequence().toSet()
        val frozenShape = when {
            actualSourceKeys == rectangleSourceFields -> "rectangle"
            actualSourceKeys == lShapedSourceFields &&
                source.getString("shape") == "l_shaped" -> "l_shaped"
            else -> error("Incomplete or unsupported frozen measurement inputs")
        }
        check(source.getString("name").isNotBlank())
        val complexity = source.getString("complexity")
        check(complexity in setOf("straight", "multilevel", "curved")) {
            "Unsupported ceiling complexity: missing independent KMP replay"
        }
        check(source.getString("material").isNotBlank() &&
            source.getString("profileType").isNotBlank())

        val optionKeys = EstimateOptionId.entries.map { it.code }.toSet()
        val detailFields = optionKeys + setOf("measurementPrecisionMeters", "lineItems")
        check(details.keys().asSequence().toSet() == detailFields) {
            "Missing or unrecognized saved estimate options"
        }
        val savedOptionQuantities = optionKeys.associateWith { code ->
            val amount = details.getDouble(code)
            check(amount.isFinite() && amount >= 0.0) {
                "Invalid saved option quantity for $code"
            }
            amount
        }
        val precision = details.getDouble("measurementPrecisionMeters")
        check(precision == latest.measurementPrecisionMeters) {
            "Frozen measurement precision differs between snapshots"
        }
        val complexityCount = if (complexity == "straight") 0 else 1
        check(savedLines.size == 2 + complexityCount +
            savedOptionQuantities.values.count { it > 0.0 }) {
            "Every complexity/option work item needs its own frozen priced line"
        }
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
        val candidate = when (frozenShape) {
            "rectangle" -> EstimateRevisionCandidates.prepareRectangleWithOptions(
                saved,
                VerifiedRectangleInputs(
                    lengthMeters = source.getDouble("lengthMeters"),
                    widthMeters = source.getDouble("widthMeters"),
                    storedAreaSquareMeters = source.getDouble("areaSquareMeters"),
                    storedPerimeterMeters = source.getDouble("perimeterMeters"),
                    precisionStepMeters = precision,
                    complexity = complexity
                ),
                preview,
                savedOptionQuantities
            )
            "l_shaped" -> EstimateRevisionCandidates.prepareLShapedWithOptions(
                saved,
                VerifiedLShapedInputs(
                    sideA = source.getDouble("sideA"),
                    sideB = source.getDouble("sideB"),
                    sideC = source.getDouble("sideC"),
                    sideD = source.getDouble("sideD"),
                    sideE = source.getDouble("sideE"),
                    sideF = source.getDouble("sideF"),
                    storedAreaSquareMeters = source.getDouble("areaSquareMeters"),
                    storedPerimeterMeters = source.getDouble("perimeterMeters"),
                    precisionStepMeters = precision,
                    complexity = complexity
                ),
                preview,
                savedOptionQuantities
            )
            else -> error("Unsupported frozen geometry")
        }
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
        // inputs and ALL saved options, including priced nonzero ones.
        // Replace only lineItems after KMP has verified their new totals.
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
            draft = draft,
            preview = preview
        )
    }
}
