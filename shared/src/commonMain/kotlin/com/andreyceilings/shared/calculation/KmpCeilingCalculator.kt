package com.andreyceilings.shared.calculation

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Independent KMP port of go-core/core/calculator.go.
 *
 * During migration the Android app still uses Go/AAR. This implementation is
 * deliberately not connected to production UI until parity tests cover all
 * calculation paths. Inputs and prices are expected to be finite (as in the
 * current Android calculator adapter).
 */
data class GeometryInput(
    val shape: String = "rectangle",
    val length: Double = 0.0,
    val width: Double = 0.0,
    val sideA: Double = 0.0,
    val sideB: Double = 0.0,
    val sideC: Double = 0.0,
    val sideD: Double = 0.0,
    val sideE: Double = 0.0,
    val sideF: Double = 0.0,
    val diagonal1: Double = 0.0,
    val customSides: List<Double> = emptyList(),
    val precisionStepMeters: Double? = null
)

data class RoomGeometry(val area: Double, val perimeter: Double, val cornersCount: Int)

data class RoomOptions(
    val lightLineMeters: Double = 0.0,
    val shadowProfileMeters: Double = 0.0,
    val floatingCeilingMeters: Double = 0.0,
    val chandeliersCount: Double = 0.0,
    val spotCount: Double = 0.0,
    val pipePassCount: Double = 0.0,
    val curtainNicheMeters: Double = 0.0,
    val extraCorners: Double = 0.0,
    val hardWallMeters: Double = 0.0
)

data class PriceList(
    val canvasPrices: Map<String, Double>,
    val profilePrices: Map<String, Double>,
    val multilevelPerMeterCost: Double = 0.0,
    val curvedPerMeterCost: Double = 0.0,
    val lightLinePerMeter: Double = 0.0,
    val shadowProfilePerMeter: Double = 0.0,
    val floatingProfilePerMeter: Double = 0.0,
    val chandelierInstallation: Double = 0.0,
    val spotInstallation: Double = 0.0,
    val pipePass: Double = 0.0,
    val curtainNichePerMeter: Double = 0.0,
    val extraCornerCost: Double = 0.0,
    val hardWallPerMeter: Double = 0.0
)

data class CostInput(
    val area: Double,
    val perimeter: Double,
    val materialType: String = "pvc_matte",
    val complexity: String = "straight",
    val profileType: String = "standard_alu",
    val options: RoomOptions = RoomOptions(),
    val precisionStepMeters: Double? = null
)

data class CostBreakdown(
    val canvasCost: Double,
    val profileCost: Double,
    val complexityCost: Double,
    val optionsCost: Double,
    val totalCost: Double
)

data class CalculationResult(val geometry: RoomGeometry, val cost: CostBreakdown)

object KmpCeilingCalculator {
    fun defaultPriceList(): PriceList = PriceList(
        canvasPrices = mapOf(
            "pvc_matte" to 290.0, "pvc_glossy" to 310.0, "pvc_satin" to 310.0,
            "fabric" to 890.0, "fireproof_km2" to 480.0, "acoustic" to 980.0
        ),
        profilePrices = mapOf(
            "standard_alu" to 130.0, "pvc" to 90.0, "shadow_kraab" to 380.0,
            "floating_led" to 420.0, "slott_tracks" to 550.0
        ),
        multilevelPerMeterCost = 650.0, curvedPerMeterCost = 350.0,
        lightLinePerMeter = 850.0, shadowProfilePerMeter = 380.0,
        floatingProfilePerMeter = 420.0, chandelierInstallation = 350.0,
        spotInstallation = 220.0, pipePass = 180.0, curtainNichePerMeter = 450.0,
        extraCornerCost = 90.0, hardWallPerMeter = 220.0
    )

    // Go math.Round differs from kotlin.math.round at exact half-way values.
    // Explicitly preserve Go's half-away-from-zero rule (for finite values).
    private fun goRound(value: Double): Double =
        if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)

    private fun round2(value: Double): Double = goRound(value * 100.0) / 100.0

    private fun roundToStep(value: Double, step: Double): Double =
        goRound(value / step) * step

    /** Default null preserves Go legacy geometry; selected resolution rounds dimensions. */
    private fun measuredValue(value: Double, step: Double?): Double =
        if (step == null) value else roundToStep(value, step)

    private fun geometryResult(area: Double, perimeter: Double, corners: Int, step: Double?): RoomGeometry =
        if (step == null) RoomGeometry(round2(area), round2(perimeter), corners)
        else RoomGeometry(roundToStep(area, step * step), roundToStep(perimeter, step), corners)
    private fun nonnegative(value: Double): Double = if (value < 0.0) 0.0 else value

    fun geometry(input: GeometryInput): RoomGeometry {
        val step = input.precisionStepMeters
        require(step == null || step in setOf(0.1, 0.01, 0.005, 0.001)) {
            "Unsupported measurement precision"
        }
        fun measured(value: Double): Double = measuredValue(value, step)
        val length = if (input.length <= 0.0) 4.0 else input.length
        val width = if (input.width <= 0.0) 3.0 else input.width

        if (input.shape == "square") {
            val side = when {
                input.length > 0.0 -> input.length
                input.width > 0.0 -> input.width
                else -> 3.0
            }
            val s = max(0.1, side)
            return geometryResult(measured(s) * measured(s), 4.0 * measured(s), 4, step)
        }

        if (input.shape == "rectangle" || input.shape.isEmpty()) {
            val l = max(0.1, length)
            val w = max(0.1, width)
            return geometryResult(measured(l) * measured(w), 2.0 * (measured(l) + measured(w)), 4, step)
        }

        if (input.shape == "l_shaped") {
            val a = when {
                input.sideA > 0.0 -> input.sideA
                input.length > 0.0 -> input.length
                else -> 5.0
            }
            val b = when {
                input.sideB > 0.0 -> input.sideB
                input.width > 0.0 -> input.width
                else -> 4.0
            }
            val c = if (input.sideC > 0.0) input.sideC else a * 0.5
            val d = if (input.sideD > 0.0) input.sideD else b * 0.5
            val e = if (input.sideE > 0.0) input.sideE else max(0.1, a - c)
            val f = if (input.sideF > 0.0) input.sideF else max(0.1, b - d)
            val area = max(1.0, measured(a) * measured(b) - max(0.0, measured(e) * measured(f)))
            return geometryResult(area, measured(a) + measured(b) + measured(c) + measured(d) + measured(e) + measured(f), 6, step)
        }

        val a = if (input.sideA > 0.0) input.sideA else length
        val b = if (input.sideB > 0.0) input.sideB else width
        val c = if (input.sideC > 0.0) input.sideC else length
        val d = if (input.sideD > 0.0) input.sideD else width
        var area = measured(length) * measured(width)
        if (input.diagonal1 > 0.0) {
            fun triangle(x: Double, y: Double, diagonal: Double): Double {
                val semiperimeter = (x + y + diagonal) / 2.0
                val product = semiperimeter * (semiperimeter - x) *
                    (semiperimeter - y) * (semiperimeter - diagonal)
                return if (product <= 0.0) 0.0 else sqrt(product)
            }
            val candidate = triangle(measured(a), measured(b), measured(input.diagonal1)) +
                triangle(measured(c), measured(d), measured(input.diagonal1))
            if (candidate > 0.0) area = candidate
        }
        val corners = if (input.customSides.size >= 4) input.customSides.size else 5
        return geometryResult(area, measured(a) + measured(b) + measured(c) + measured(d), corners, step)
    }

    fun cost(input: CostInput, priceList: PriceList = defaultPriceList()): CostBreakdown {
        // The Go implementation resets the *whole* list only when canvasPrices is empty.
        val prices = if (priceList.canvasPrices.isEmpty()) defaultPriceList() else priceList
        val step = input.precisionStepMeters
        require(step == null || MeasurementPrecision.fromStep(step) != null) { "Unsupported measurement precision" }
        fun linear(value: Double): Double = measuredValue(nonnegative(value), step)
        fun complexityLength(value: Double): Double =
            if (step == null) goRound(value * 10.0) / 10.0 else measured(value)
        fun measured(value: Double): Double = measuredValue(value, step)
        val area = nonnegative(input.area)
        val perimeter = nonnegative(input.perimeter)
        val material = if (input.materialType in prices.canvasPrices) input.materialType else "pvc_matte"
        val profile = if (input.profileType in prices.profilePrices) input.profileType else "standard_alu"
        // Missing map entries are 0 in Go; keep that behavior for partial overrides.
        val canvasCost = goRound(area * (prices.canvasPrices[material] ?: 0.0))
        val profileCost = goRound(perimeter * (prices.profilePrices[profile] ?: 0.0))
        val complexityCost = when (input.complexity) {
            "multilevel" -> {
                val run = max(2.0, complexityLength(perimeter * 0.35))
                goRound(run * prices.multilevelPerMeterCost)
            }
            "curved" -> {
                val run = max(2.0, complexityLength(perimeter * 0.25))
                goRound(run * prices.curvedPerMeterCost)
            }
            else -> 0.0
        }
        val o = input.options
        var optionsCost = linear(o.lightLineMeters) * prices.lightLinePerMeter
        if (profile != "shadow_kraab") {
            optionsCost += linear(o.shadowProfileMeters) * prices.shadowProfilePerMeter
        }
        if (profile != "floating_led") {
            optionsCost += linear(o.floatingCeilingMeters) * prices.floatingProfilePerMeter
        }
        optionsCost += nonnegative(o.chandeliersCount) * prices.chandelierInstallation
        optionsCost += nonnegative(o.spotCount) * prices.spotInstallation
        optionsCost += nonnegative(o.pipePassCount) * prices.pipePass
        optionsCost += linear(o.curtainNicheMeters) * prices.curtainNichePerMeter
        optionsCost += nonnegative(o.extraCorners) * prices.extraCornerCost
        optionsCost += linear(o.hardWallMeters) * prices.hardWallPerMeter
        optionsCost = goRound(optionsCost)
        return CostBreakdown(
            canvasCost, profileCost, complexityCost, optionsCost,
            canvasCost + profileCost + complexityCost + optionsCost
        )
    }

    /** Matches mobileapi.CalculateJSON: geometry always overrides input cost area/perimeter. */
    fun estimate(
        geometryInput: GeometryInput,
        costInput: CostInput,
        prices: PriceList = defaultPriceList()
    ): CalculationResult {
        val dimensions = geometry(geometryInput)
        return CalculationResult(
            dimensions,
            cost(costInput.copy(area = dimensions.area, perimeter = dimensions.perimeter,
                precisionStepMeters = geometryInput.precisionStepMeters), prices)
        )
    }
}
