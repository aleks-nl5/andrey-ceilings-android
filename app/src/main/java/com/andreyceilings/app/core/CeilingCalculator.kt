package com.andreyceilings.app.core

import android.os.SystemClock
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
        // No Kotlin calculation fallback: if the Go binding fails, this method throws.
        val prices = measure("default_prices_and_parse") {
            JSONObject(Mobileapi.defaultPricesJSON()).also { parsed ->
                check(parsed.optString("error").isBlank()) {
                    parsed.optString("error").ifBlank { "Go не повернув прайс-лист" }
                }
            }
        }
        val canvasPrices = prices.getJSONObject("canvasPrices")
        val profilePrices = prices.getJSONObject("profilePrices")
        val dbPrices = measure("database_price_mapping") {
            priceItems.associate { it.code to it.price.coerceAtLeast(0.0) }
        }

        measure("price_merge") {
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
            dbPrices["light.spot"]?.let { prices.put("spotInstallation", it) }
            dbPrices["work.pipe_pass"]?.let { prices.put("pipePass", it) }
            dbPrices["work.curtain_niche"]?.let { prices.put("curtainNichePerMeter", it) }
            dbPrices["work.extra_corner"]?.let { prices.put("extraCornerCost", it) }
            dbPrices["work.hard_wall"]?.let { prices.put("hardWallPerMeter", it) }
        }

        val request = measure("request_build") { JSONObject()
            .put("geometry", JSONObject()
                .put("shape", "rectangle")
                .put("length", length.coerceAtLeast(0.0))
                .put("width", width.coerceAtLeast(0.0))
                .put("precisionStepMeters", precisionStepMeters))
            .put("cost", JSONObject()
                .put("materialType", materialType)
                .put("profileType", profileType)
                .put("complexity", options.complexity)
                .put("options", JSONObject()
                    .put("lightLineMeters", options.lightLineMeters)
                    .put("shadowProfileMeters", options.shadowProfileMeters)
                    .put("floatingCeilingMeters", options.floatingCeilingMeters)
                    .put("chandeliersCount", options.chandeliersCount)
                    .put("spotCount", options.spotCount)
                    .put("pipePassCount", options.pipePassCount)
                    .put("curtainNicheMeters", options.curtainNicheMeters)
                    .put("extraCorners", options.extraCorners)
                    .put("hardWallMeters", options.hardWallMeters)))
            .put("prices", prices) }

        val requestJson = measure("request_serialize") { request.toString() }
        val responseJson = measure("go_calculate") { Mobileapi.calculateJSON(requestJson) }
        val response = measure("response_parse") { JSONObject(responseJson) }
        check(response.optString("error").isBlank()) { response.optString("error", "Go calculation failed") }
        val geometry = response.getJSONObject("geometry")
        val cost = response.getJSONObject("cost")
        fun requiredFinite(obj: JSONObject, key: String): Double {
            val value = obj.getDouble(key)
            check(value.isFinite() && value >= 0.0) { "Go повернув некоректне значення: $key" }
            return value
        }
        // The engine label is assigned only after a successful Go response is validated.
        return CeilingEstimate(
            area = requiredFinite(geometry, "area"),
            perimeter = requiredFinite(geometry, "perimeter"),
            canvasCost = requiredFinite(cost, "canvasCost"),
            profileCost = requiredFinite(cost, "profileCost"),
            complexityCost = requiredFinite(cost, "complexityCost"),
            optionsCost = requiredFinite(cost, "optionsCost"),
            total = requiredFinite(cost, "totalCost"),
            engine = "Go"
        )
        } finally {
            if (onTimings != null) {
                phaseTimings += "total" to ((SystemClock.elapsedRealtimeNanos() - totalStartedAt) / 1_000L)
                onTimings.invoke(phaseTimings.toList())
            }
        }
    }
}
