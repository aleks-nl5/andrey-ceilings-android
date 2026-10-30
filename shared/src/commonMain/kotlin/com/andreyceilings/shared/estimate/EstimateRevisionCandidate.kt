package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.calculation.CostInput
import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.calculation.PriceList
import com.andreyceilings.shared.calculation.roomOptionsFromCanonicalQuantities
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
 * Read-only KMP rectangle replay. Simple two-line ceilings retain the
 * original entry point; canonical optional works require explicit quantities
 * and a full KMP pricing replay. Custom shapes, complexity and historical
 * rounding corrections still fail closed instead of inventing amounts.
 */
object EstimateRevisionCandidates {
    fun prepareSimpleRectangle(
        source: ImmutableEstimateDraft,
        input: VerifiedRectangleInputs,
        preview: EstimateRecalculationPreview
    ): EstimateRevisionCandidate =
        prepareRectangle(source, input, preview, emptyMap(), verifyOptions = false)

    /**
     * The caller must provide EVERY persisted canonical option, including
     * zeros. A nonzero option must have exactly one frozen line, and the new
     * line totals must agree with KmpCeilingCalculator.cost. No database
     * write, synthetic default prices or generated detailsJson occurs here.
     */
    fun prepareRectangleWithOptions(
        source: ImmutableEstimateDraft,
        input: VerifiedRectangleInputs,
        preview: EstimateRecalculationPreview,
        savedOptionQuantities: Map<String, Double>
    ): EstimateRevisionCandidate =
        prepareRectangle(source, input, preview, savedOptionQuantities, verifyOptions = true)

    private fun prepareRectangle(
        source: ImmutableEstimateDraft,
        input: VerifiedRectangleInputs,
        preview: EstimateRecalculationPreview,
        savedOptionQuantities: Map<String, Double>,
        verifyOptions: Boolean
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

        val options = if (verifyOptions) {
            val canonicalCodes = EstimateOptionId.entries.map { it.code }.toSet()
            check(savedOptionQuantities.keys == canonicalCodes) {
                "All saved option quantities must be explicitly supplied"
            }
            check(savedOptionQuantities.values.all { it.isFinite() && it >= 0.0 }) {
                "Invalid saved option quantity"
            }
            savedOptionQuantities
        } else emptyMap()
        val enabledOptions = EstimateOptionId.entries.filter {
            (options[it.code] ?: 0.0) > 0.0
        }
        val expectedLineCount = 2 + enabledOptions.size
        check(source.lines.size == expectedLineCount &&
            preview.lines.size == expectedLineCount &&
            source.lines == preview.lines.map { it.before }) {
            "Frozen lines differ from the complete saved rectangle and options"
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
        if (verifyOptions) {
            check(source.lines.first() == canvases.single() &&
                source.lines[1] == profiles.single() &&
                source.lines.drop(2).map { it.stableCode } == enabledOptions.map { it.code }) {
                "Saved option rows must follow the canonical nonzero option sequence"
            }
            check(source.lines.map { it.stableCode }.toSet().size == source.lines.size) {
                "Repeated price codes in immutable snapshot"
            }
            val profileCode = profiles.single().stableCode
            check(!(profileCode == "profile.shadow_kraab" &&
                (options[EstimateOptionId.SHADOW_PROFILE.code] ?: 0.0) > 0.0) &&
                !(profileCode == "profile.floating_led" &&
                (options[EstimateOptionId.FLOATING_PROFILE.code] ?: 0.0) > 0.0)) {
                "Selected main profile must not be charged a second time"
            }
            enabledOptions.forEach { id ->
                val line = source.lines.single { it.stableCode == id.code }
                val expectedUnit = when (id) {
                    EstimateOptionId.CHANDELIER, EstimateOptionId.SPOT,
                    EstimateOptionId.PIPE_PASS, EstimateOptionId.EXTRA_CORNER -> "шт."
                    else -> "м.п."
                }
                check(line.quantity == options.getValue(id.code) &&
                    line.unit == expectedUnit) {
                    "Frozen quantity or unit does not match saved option: ${id.code}"
                }
            }
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
        if (verifyOptions) {
            // Verify the proposed option total through the actual shared
            // calculator rather than trusting the itemized line sum alone.
            // Different per-item/aggregate rounding must be resolved before
            // the new immutable revision can be saved.
            val canvas = lines.first()
            val profile = lines[1]
            val material = canvas.stableCode.removePrefix("canvas.")
            val profileType = profile.stableCode.removePrefix("profile.")
            val defaultCatalog = KmpCeilingCalculator.defaultPriceList()
            check(material in defaultCatalog.canvasPrices &&
                profileType in defaultCatalog.profilePrices) {
                "Unknown KMP material or profile"
            }
            val byCode = lines.associateBy { it.stableCode }
            val calculatorPrices = PriceList(
                canvasPrices = mapOf(material to canvas.unitPrice),
                profilePrices = mapOf(profileType to profile.unitPrice),
                lightLinePerMeter = byCode[EstimateOptionId.LIGHT_LINE.code]?.unitPrice ?: 0.0,
                shadowProfilePerMeter = byCode[EstimateOptionId.SHADOW_PROFILE.code]?.unitPrice ?: 0.0,
                floatingProfilePerMeter = byCode[EstimateOptionId.FLOATING_PROFILE.code]?.unitPrice ?: 0.0,
                chandelierInstallation = byCode[EstimateOptionId.CHANDELIER.code]?.unitPrice ?: 0.0,
                spotInstallation = byCode[EstimateOptionId.SPOT.code]?.unitPrice ?: 0.0,
                pipePass = byCode[EstimateOptionId.PIPE_PASS.code]?.unitPrice ?: 0.0,
                curtainNichePerMeter = byCode[EstimateOptionId.CURTAIN_NICHE.code]?.unitPrice ?: 0.0,
                extraCornerCost = byCode[EstimateOptionId.EXTRA_CORNER.code]?.unitPrice ?: 0.0,
                hardWallPerMeter = byCode[EstimateOptionId.HARD_WALL.code]?.unitPrice ?: 0.0
            )
            val kmp = KmpCeilingCalculator.cost(
                CostInput(
                    area = geometry.area, perimeter = geometry.perimeter,
                    materialType = material, profileType = profileType,
                    options = roomOptionsFromCanonicalQuantities(options),
                    precisionStepMeters = input.precisionStepMeters
                ),
                calculatorPrices
            )
            check(kmp.canvasCost == canvas.lineTotal &&
                kmp.profileCost == profile.lineTotal &&
                kmp.complexityCost == 0.0 &&
                kmp.optionsCost == lines.drop(2).sumOf { it.lineTotal } &&
                kmp.totalCost == proposedTotal) {
                "Option totals are inconsistent with the KMP calculator and its rounding"
            }
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
