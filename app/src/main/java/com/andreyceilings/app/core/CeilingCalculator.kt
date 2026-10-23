package com.andreyceilings.app.core

import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.core.mobileapi.Mobileapi
import org.json.JSONObject

/** Values returned by the Go calculation engine. */
data class CeilingEstimate(
    val area: Double,
    val perimeter: Double,
    val canvasCost: Double,
    val profileCost: Double,
    val complexityCost: Double,
    val optionsCost: Double,
    val total: Double,
    val engine: String = "Go"
)

data class CalculatorOptions(
    val lightLinesMeters: Double = 0.0,
    val shadowProfileMeters: Double = 0.0,
    val floatingCeilingMeters: Double = 0.0,
    val chandeliersCount: Double = 0.0,
    val spotsGX53Count: Double = 0.0,
    val pipePassCount: Double = 0.0,
    val curtainNicheMeters: Double = 0.0,
    val extraCorners: Double = 0.0,
    val hardWallMeters: Double = 0.0,
    val complexity: String = "straight"
)

/** Kotlin is only an adapter: all geometry and price calculations are performed by Go. */
object CeilingCalculator {
    fun estimate(
        length: Double,
        width: Double,
        pricePerM2: Double,
        profilePrice: Double,
        priceItems: List<PriceItemEntity>,
        options: CalculatorOptions,
        materialType: String,
        profileType: String
    ): CeilingEstimate {
        val prices = JSONObject(Mobileapi.defaultPricesJSON())
        val canvasPrices = prices.getJSONObject("canvasPrices")
        val profilePrices = prices.getJSONObject("profilePrices")
        val dbPrices = priceItems.associate { it.code to it.price.coerceAtLeast(0.0) }

        val canvasCodes = listOf("pvc_matte", "pvc_glossy", "pvc_satin", "fabric", "fireproof_km2", "acoustic")
        canvasCodes.forEach { code -> dbPrices["canvas.$code"]?.let { canvasPrices.put(code, it) } }
        val profileCodes = listOf("standard_alu", "pvc", "shadow_kraab", "floating_led", "slott_tracks")
        profileCodes.forEach { code -> dbPrices["profile.$code"]?.let { profilePrices.put(code, it) } }
        // Current input values remain authoritative while the user edits the calculator.
        canvasPrices.put(materialType, pricePerM2.coerceAtLeast(0.0))
        profilePrices.put(profileType, profilePrice.coerceAtLeast(0.0))
        dbPrices["work.multilevel"]?.let { prices.put("multilevelPerMeterCost", it) }
        dbPrices["work.curved"]?.let { prices.put("curvedPerMeterCost", it) }
        dbPrices["light.line"]?.let { prices.put("lightLinePerMeter", it) }
        dbPrices["work.shadow_profile"]?.let { prices.put("shadowProfilePerMeter", it) }
        dbPrices["work.floating_ceiling"]?.let { prices.put("floatingProfilePerMeter", it) }
        dbPrices["light.chandelier"]?.let { prices.put("chandelierInstallation", it) }
        dbPrices["light.spot_gx53"]?.let { prices.put("spotInstallation", it) }
        dbPrices["work.pipe_pass"]?.let { prices.put("pipePass", it) }
        dbPrices["work.curtain_niche"]?.let { prices.put("curtainNichePerMeter", it) }
        dbPrices["work.extra_corner"]?.let { prices.put("extraCornerCost", it) }
        dbPrices["work.hard_wall"]?.let { prices.put("hardWallPerMeter", it) }

        val request = JSONObject()
            .put("geometry", JSONObject()
                .put("shape", "rectangle")
                .put("length", length.coerceAtLeast(0.0))
                .put("width", width.coerceAtLeast(0.0)))
            .put("cost", JSONObject()
                .put("materialType", materialType)
                .put("profileType", profileType)
                .put("complexity", options.complexity)
                .put("options", JSONObject()
                    .put("lightLinesMeters", options.lightLinesMeters)
                    .put("shadowProfileMeters", options.shadowProfileMeters)
                    .put("floatingCeilingMeters", options.floatingCeilingMeters)
                    .put("chandeliersCount", options.chandeliersCount)
                    .put("spotsGX53Count", options.spotsGX53Count)
                    .put("pipePassCount", options.pipePassCount)
                    .put("curtainNicheMeters", options.curtainNicheMeters)
                    .put("extraCorners", options.extraCorners)
                    .put("hardWallMeters", options.hardWallMeters)))
            .put("prices", prices)

        val response = JSONObject(Mobileapi.calculateJSON(request.toString()))
        check(response.optString("error").isBlank()) { response.optString("error", "Go calculation failed") }
        val geometry = response.getJSONObject("geometry")
        val cost = response.getJSONObject("cost")
        return CeilingEstimate(
            area = geometry.getDouble("area"),
            perimeter = geometry.getDouble("perimeter"),
            canvasCost = cost.getDouble("canvasCost"),
            profileCost = cost.getDouble("profileCost"),
            complexityCost = cost.getDouble("complexityCost"),
            optionsCost = cost.getDouble("optionsCost"),
            total = cost.getDouble("totalCost")
        )
    }
}
