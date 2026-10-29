package com.andreyceilings.app.core

import android.os.SystemClock
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.shared.calculation.CostInput
import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.calculation.KmpCeilingCalculator
import com.andreyceilings.shared.calculation.RoomOptions

/** The Android UI contract for the shared Kotlin Multiplatform calculation engine. */
data class CeilingEstimate(
    val area: Double,
    val perimeter: Double,
    val canvasCost: Double,
    val profileCost: Double,
    val complexityCost: Double,
    val optionsCost: Double,
    val total: Double,
    val engine: String
)

data class CalculatorOptions(
    val lightLineMeters: Double = 0.0,
    val shadowProfileMeters: Double = 0.0,
    val floatingCeilingMeters: Double = 0.0,
    val chandeliersCount: Double = 0.0,
    val spotCount: Double = 0.0,
    val pipePassCount: Double = 0.0,
    val curtainNicheMeters: Double = 0.0,
    val extraCorners: Double = 0.0,
    val hardWallMeters: Double = 0.0,
    val complexity: String = "straight"
)

/**
 * Android-only UI adapter. Geometry, pricing, options and money rounding
 * all run in :shared/commonMain. No Go bindings, AAR or second calculator.
 */
object CeilingCalculator {
    fun estimate(
        length: Double,
        width: Double,
        pricePerM2: Double,
        profilePrice: Double,
        priceItems: List<PriceItemEntity>,
        options: CalculatorOptions,
        materialType: String,
        profileType: String,
        precisionStepMeters: Double = 0.01,
        onTimings: ((List<Pair<String, Long>>) -> Unit)? = null
    ): CeilingEstimate {
        val totalStartedAt = if (onTimings != null) SystemClock.elapsedRealtimeNanos() else 0L
        val phaseTimings = mutableListOf<Pair<String, Long>>()
        fun <T> measure(phase: String, block: () -> T): T {
            if (onTimings == null) return block()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            return try {
                block()
            } finally {
                phaseTimings += phase to ((SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000L)
            }
        }
        try {
            require(length.isFinite() && width.isFinite()) { "Некоректні розміри приміщення" }
            require(pricePerM2.isFinite() && profilePrice.isFinite()) { "Некоректна ціна матеріалу або профілю" }
            val defaults = measure("default_prices") { KmpCeilingCalculator.defaultPriceList() }
            val dbPrices = measure("database_price_mapping") {
                priceItems.associate { it.code to it.price.coerceAtLeast(0.0) }
            }
            val prices = measure("price_merge") {
                defaults.copy(
                    canvasPrices = defaults.canvasPrices.mapValues { (code, default) ->
                        dbPrices["canvas.$code"] ?: default
                    } + (materialType to pricePerM2.coerceAtLeast(0.0)),
                    profilePrices = defaults.profilePrices.mapValues { (code, default) ->
                        dbPrices["profile.$code"] ?: default
                    } + (profileType to profilePrice.coerceAtLeast(0.0)),
                    multilevelPerMeterCost = dbPrices["work.multilevel"] ?: defaults.multilevelPerMeterCost,
                    curvedPerMeterCost = dbPrices["work.curved"] ?: defaults.curvedPerMeterCost,
                    lightLinePerMeter = dbPrices["light.line"] ?: defaults.lightLinePerMeter,
                    shadowProfilePerMeter = dbPrices["work.shadow_profile"] ?: defaults.shadowProfilePerMeter,
                    floatingProfilePerMeter = dbPrices["work.floating_ceiling"] ?: defaults.floatingProfilePerMeter,
                    chandelierInstallation = dbPrices["light.chandelier"] ?: defaults.chandelierInstallation,
                    spotInstallation = dbPrices["light.spot"] ?: defaults.spotInstallation,
                    pipePass = dbPrices["work.pipe_pass"] ?: defaults.pipePass,
                    curtainNichePerMeter = dbPrices["work.curtain_niche"] ?: defaults.curtainNichePerMeter,
                    extraCornerCost = dbPrices["work.extra_corner"] ?: defaults.extraCornerCost,
                    hardWallPerMeter = dbPrices["work.hard_wall"] ?: defaults.hardWallPerMeter
                )
            }
            val result = measure("kmp_calculate") {
                KmpCeilingCalculator.estimate(
                    geometryInput = GeometryInput(
                        shape = "rectangle",
                        length = length.coerceAtLeast(0.0),
                        width = width.coerceAtLeast(0.0),
                        precisionStepMeters = precisionStepMeters
                    ),
                    costInput = CostInput(
                        area = 0.0,
                        perimeter = 0.0,
                        materialType = materialType,
                        profileType = profileType,
                        complexity = options.complexity,
                        options = RoomOptions(
                            lightLineMeters = options.lightLineMeters,
                            shadowProfileMeters = options.shadowProfileMeters,
                            floatingCeilingMeters = options.floatingCeilingMeters,
                            chandeliersCount = options.chandeliersCount,
                            spotCount = options.spotCount,
                            pipePassCount = options.pipePassCount,
                            curtainNicheMeters = options.curtainNicheMeters,
                            extraCorners = options.extraCorners,
                            hardWallMeters = options.hardWallMeters
                        )
                    ),
                    prices = prices
                )
            }
            val geometry = result.geometry
            val cost = result.cost
            listOf(geometry.area, geometry.perimeter, cost.canvasCost, cost.profileCost,
                cost.complexityCost, cost.optionsCost, cost.totalCost).forEach {
                check(it.isFinite() && it >= 0.0) { "KMP повернув некоректний результат" }
            }
            return CeilingEstimate(
                area = geometry.area,
                perimeter = geometry.perimeter,
                canvasCost = cost.canvasCost,
                profileCost = cost.profileCost,
                complexityCost = cost.complexityCost,
                optionsCost = cost.optionsCost,
                total = cost.totalCost,
                engine = "Kotlin KMP"
            )
        } finally {
            if (onTimings != null) {
                phaseTimings += "total" to ((SystemClock.elapsedRealtimeNanos() - totalStartedAt) / 1_000L)
                onTimings.invoke(phaseTimings.toList())
            }
        }
    }
}
