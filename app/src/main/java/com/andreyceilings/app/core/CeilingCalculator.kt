package com.andreyceilings.app.core

import com.andreyceilings.app.data.db.PriceItemEntity
import org.json.JSONObject

/** Inputs for the optional work lines in a room estimate. */
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

/** Estimate returned to the Compose UI. */
data class CeilingEstimate(
    val area: Double,
    val perimeter: Double,
    val canvasCost: Double,
    val profileCost: Double,
    val complexityCost: Double,
    val optionsCost: Double,
    val total: Double,
    val engine: String = "Kotlin fallback"
)

/**
 * Native adapter for the Go calculator. It supplies prices from Room where available.
 * The Kotlin fallback keeps the UI usable until the gomobile AAR is installed; that
 * fallback intentionally calculates only the basic canvas/profile amount.
 */
object CeilingCalculator {
    fun estimate(
        length: Double,
        width: Double,
        canvasPrice: Double,
        profilePrice: Double,
        priceItems: List<PriceItemEntity> = emptyList(),
        options: CalculatorOptions = CalculatorOptions(),
        materialType: String = "pvc_matte",
        profileType: String = "standard_alu"
    ): CeilingEstimate {
        val safeLength = length.coerceAtLeast(0.0)
        val safeWidth = width.coerceAtLeast(0.0)
        val safeCanvasPrice = canvasPrice.coerceAtLeast(0.0)
        val safeProfilePrice = profilePrice.coerceAtLeast(0.0)
        val itemPrices = priceItems.associate { it.code to it.price.coerceAtLeast(0.0) }

        fun price(code: String, fallback: Double) = itemPrices[code] ?: fallback.coerceAtLeast(0.0)
        val canvasPrices = JSONObject()
            .put("pvc_matte", if (materialType == "pvc_matte") safeCanvasPrice else price("canvas.pvc_matte", 290.0))
            .put("pvc_glossy", if (materialType == "pvc_glossy") safeCanvasPrice else price("canvas.pvc_glossy", 310.0))
            .put("pvc_satin", if (materialType == "pvc_satin") safeCanvasPrice else price("canvas.pvc_satin", 310.0))
            .put("fabric", if (materialType == "fabric") safeCanvasPrice else price("canvas.fabric", 890.0))
            .put("fireproof_km2", if (materialType == "fireproof_km2") safeCanvasPrice else price("canvas.fireproof_km2", 480.0))
            .put("acoustic", if (materialType == "acoustic") safeCanvasPrice else price("canvas.acoustic", 980.0))
        val profilePrices = JSONObject()
            .put("standard_alu", if (profileType == "standard_alu") safeProfilePrice else price("profile.standard_alu", 130.0))
            .put("pvc", if (profileType == "pvc") safeProfilePrice else price("profile.pvc", 90.0))
            .put("shadow_kraab", if (profileType == "shadow_kraab") safeProfilePrice else price("profile.shadow_kraab", 380.0))
            .put("floating_led", if (profileType == "floating_led") safeProfilePrice else price("profile.floating_led", 420.0))
            .put("slott_tracks", if (profileType == "slott_tracks") safeProfilePrice else price("profile.slott_tracks", 550.0))

        val request = JSONObject()
            .put("geometry", JSONObject()
                .put("shape", "rectangle")
                .put("length", safeLength)
                .put("width", safeWidth))
            .put("cost", JSONObject()
                .put("materialType", materialType)
                .put("profileType", profileType)
                .put("complexity", options.complexity)
                .put("options", JSONObject()
                    .put("lightLinesMeters", options.lightLinesMeters.coerceAtLeast(0.0))
                    .put("shadowProfileMeters", options.shadowProfileMeters.coerceAtLeast(0.0))
                    .put("floatingCeilingMeters", options.floatingCeilingMeters.coerceAtLeast(0.0))
                    .put("chandeliersCount", options.chandeliersCount.coerceAtLeast(0.0))
                    .put("spotsGX53Count", options.spotsGX53Count.coerceAtLeast(0.0))
                    .put("pipePassCount", options.pipePassCount.coerceAtLeast(0.0))
                    .put("curtainNicheMeters", options.curtainNicheMeters.coerceAtLeast(0.0))
                    .put("extraCorners", options.extraCorners.coerceAtLeast(0.0))
                    .put("hardWallMeters", options.hardWallMeters.coerceAtLeast(0.0))))
            .put("prices", JSONObject()
                .put("canvasPrices", canvasPrices)
                .put("profilePrices", profilePrices)
                .put("multilevelPerMeterCost", price("work.multilevel", 650.0))
                .put("curvedPerMeterCost", price("work.curved", 350.0))
                .put("lightLinePerMeter", price("light.line", 850.0))
                .put("shadowProfilePerMeter", price("work.shadow_profile", 380.0))
                .put("floatingProfilePerMeter", price("work.floating_ceiling", 420.0))
                .put("chandelierInstallation", price("light.chandelier", 350.0))
                .put("spotInstallation", price("light.spot_gx53", 220.0))
                .put("pipePass", price("work.pipe_pass", 180.0))
                .put("curtainNichePerMeter", price("work.curtain_niche", 450.0))
                .put("extraCornerCost", price("work.extra_corner", 90.0))
                .put("hardWallPerMeter", price("work.hard_wall", 220.0)))

        val goResult = runCatching { callGoCore(request.toString()) }.getOrNull()
        if (goResult != null) {
            val response = JSONObject(goResult)
            if (!response.has("error") || response.isNull("error")) {
                val geometry = response.getJSONObject("geometry")
                val cost = response.getJSONObject("cost")
                return CeilingEstimate(
                    area = geometry.optDouble("area"),
                    perimeter = geometry.optDouble("perimeter"),
                    canvasCost = cost.optDouble("canvasCost"),
                    profileCost = cost.optDouble("profileCost"),
                    complexityCost = cost.optDouble("complexityCost"),
                    optionsCost = cost.optDouble("optionsCost"),
                    total = cost.optDouble("totalCost"),
                    engine = "Go"
                )
            }
        }

        val area = kotlin.math.round(safeLength * safeWidth * 100.0) / 100.0
        val perimeter = kotlin.math.round(2 * (safeLength + safeWidth) * 100.0) / 100.0
        val canvas = kotlin.math.round(area * safeCanvasPrice)
        val profile = kotlin.math.round(perimeter * safeProfilePrice)
        return CeilingEstimate(area, perimeter, canvas, profile, 0.0, 0.0, canvas + profile)
    }

    private fun callGoCore(json: String): String {
        val bindingClass = Class.forName("com.andreyceilings.core.Mobileapi")
        val method = bindingClass.methods.firstOrNull {
            it.name.equals("calculateJSON", ignoreCase = true) &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: error("CalculateJSON method not found in gomobile binding")
        return method.invoke(null, json) as String
    }
}
