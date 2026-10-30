package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.calculation.KmpCeilingCalculator
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.calculation.RoomGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A typed, WRITE-FREE intermediate result for a future immutable v2/v3.
 *
 * This is deliberately NOT an ImmutableEstimateDraft: that requires an
 * independently verified, regenerated detailsJson and original measurement
 * JSON. A price-only preview must never be persisted directly.
 */
data class VerifiedRectangleInputs(
    val lengthMeters: Double,
    val widthMeters: Double,
    val storedAreaSquareMeters: Double,
    val storedPerimeterMeters: Double,
    val precisionStepMeters: Double
)

data class EstimateRevisionCandidate(
    val baseRevisionUuid: String,
    val proposedVersionNumber: Long,
    val sourceCalculationRuleVersion: String,
    val geometry: RoomGeometry,
    val totalCost: Double,
    val lines: List<ImmutableEstimateLine>
)

/**
 * First safe geometry subset: ordinary rectangular ceilings with a single
 * canvas and profile line. Any option, custom shape, rounding adjustment,
 * legacy line, or partial measurement must be handled in a later, explicitly
 * tested KMP geometry/options converter; never guess from saved totals.
 */
object EstimateRevisionCandidates {
    fun prepareSimpleRectangle(
        source: ImmutableEstimateDraft,
        input: VerifiedRectangleInputs,
        preview: EstimateRecalculationPreview
    ): EstimateRevisionCandidate {
        check(preview.canConfirm && preview.priceListStateToken.isNotBlank()) {
            "Cannot prepare a revision from a blocked or incomplete preview"
        }
        check(preview.baseRevisionUuid.isNotBlank() &&
            preview.baseVersionNumber in 1 until Long.MAX_VALUE) {
            "Revision source identity is invalid"
        }
        check(source.calculationRuleVersion == "kmp-v1" &&
            source.moneyRoundingPolicy == "HALF_AWAY_FROM_ZERO" &&
            source.currency == "UAH") {
            "Unsupported source calculation or rounding policy"
        }
        val precision = MeasurementPrecision.fromStep(input.precisionStepMeters)
        check(precision != null &&
            source.measurementPrecisionMeters == input.precisionStepMeters) {
            "Measurement precision is missing or changed"
        }
        require(input.lengthMeters.isFinite() && input.lengthMeters > 0.0)
        require(input.widthMeters.isFinite() && input.widthMeters > 0.0)
        require(input.storedAreaSquareMeters.isFinite() && input.storedAreaSquareMeters > 0.0)
        require(input.storedPerimeterMeters.isFinite() && input.storedPerimeterMeters > 0.0)

        // The caller must obtain these typed values from the saved source
        // measurement JSON; this pure function never makes up dimensions.
        val geometry = KmpCeilingCalculator.geometry(GeometryInput(
            shape = "rectangle",
            length = input.lengthMeters,
            width = input.widthMeters,
            precisionStepMeters = input.precisionStepMeters
        ))
        check(abs(geometry.area - input.storedAreaSquareMeters) < 0.000001 &&
            abs(geometry.perimeter - input.storedPerimeterMeters) < 0.000001) {
            "Saved geometry is not reproducible by the KMP calculator"
        }

        check(source.lines.size == 2 && preview.lines.size == 2 &&
            source.lines == preview.lines.map { it.before }) {
            "Only complete two-line frozen rectangle snapshots are supported"
        }
        check(abs(source.totalCost - preview.originalTotal) < 0.01 &&
            abs(source.lines.sumOf { it.lineTotal } - source.totalCost) < 0.01) {
            "Saved source totals and preview disagree"
        }
        val canvases = source.lines.filter { it.stableCode.startsWith("canvas.") }
        val profiles = source.lines.filter { it.stableCode.startsWith("profile.") }
        check(canvases.size == 1 && profiles.size == 1) {
            "Option, adjustment, missing, or unrecognized calculation lines need full KMP replay"
        }
        check(abs(canvases.single().quantity - geometry.area) < 0.000001 &&
            abs(profiles.single().quantity - geometry.perimeter) < 0.000001) {
            "Frozen canvas/profile quantities differ from source geometry"
        }

        fun goRound(value: Double): Double =
            if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)
        val lines = preview.lines.map { comparison ->
            val after = checkNotNull(comparison.after) { "Incomplete candidate line" }
            val before = comparison.before
            check(comparison.problem == null &&
                after.stableCode == before.stableCode &&
                after.unit == before.unit &&
                after.quantity == before.quantity &&
                after.unitPrice.isFinite() && after.unitPrice >= 0.0 &&
                after.lineTotal.isFinite() && after.lineTotal >= 0.0 &&
                after.lineTotal == goRound(after.quantity * after.unitPrice)) {
                "Repriced line cannot be reproduced exactly"
            }
            after
        }
        val proposedTotal = checkNotNull(preview.proposedTotal)
        check(proposedTotal.isFinite() && proposedTotal >= 0.0 &&
            abs(lines.sumOf { it.lineTotal } - proposedTotal) < 0.01) {
            "Candidate lines do not equal the proposed total"
        }
        return EstimateRevisionCandidate(
            baseRevisionUuid = preview.baseRevisionUuid,
            proposedVersionNumber = preview.baseVersionNumber + 1L,
            sourceCalculationRuleVersion = source.calculationRuleVersion,
            geometry = geometry,
            totalCost = proposedTotal,
            lines = lines.toList()
        )
    }
}
