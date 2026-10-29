package com.andreyceilings.app.core

import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.calculation.MeasurementPrecision
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.ceil

/** A snapshot of a single priced line, saved with the estimate so later price changes do not alter history. */
data class EstimateLineItem(
    val title: String,
    val quantity: Double,
    val unit: String,
    val unitPrice: Double,
    val total: Double,
    val code: String
)

object EstimateLineItems {
    fun create(
        estimate: EstimateEntity,
        optionsJson: JSONObject,
        priceItems: List<PriceItemEntity>,
        precision: MeasurementPrecision
    ): List<EstimateLineItem> {
        fun roundMoney(value: Double): Double =
            if (value >= 0) floor(value + 0.5) else ceil(value - 0.5)
        val priceMap = priceItems.associate { it.code to it.price.coerceAtLeast(0.0) }
        fun price(code: String, fallback: Double) = priceMap[code] ?: fallback
        fun quantity(key: String) = optionsJson.optDouble(key, 0.0).coerceAtLeast(0.0)
        val rows = mutableListOf<EstimateLineItem>()
        fun add(title: String, qty: Double, unit: String, unitPrice: Double, forcedTotal: Double? = null, code: String) {
            if (qty <= 0.0) return
            val amount = forcedTotal ?: roundMoney(qty * unitPrice)
            rows += EstimateLineItem(title, qty, unit, unitPrice, amount, code)
        }

        val materialInfo = when (estimate.material) {
            "pvc_glossy" -> Triple("ПВХ полотно глянцеве", "canvas.pvc_glossy", 310.0)
            "pvc_satin" -> Triple("ПВХ полотно сатинове", "canvas.pvc_satin", 310.0)
            "fabric" -> Triple("Тканинне полотно", "canvas.fabric", 890.0)
            "fireproof_km2" -> Triple("Негорюче полотно KM2", "canvas.fireproof_km2", 480.0)
            "acoustic" -> Triple("Акустичне полотно", "canvas.acoustic", 980.0)
            else -> Triple("ПВХ полотно матове", "canvas.pvc_matte", 290.0)
        }
        val materialPrice = if (estimate.areaSquareMeters > 0) estimate.canvasCost / estimate.areaSquareMeters else price(materialInfo.second, materialInfo.third)
        add(materialInfo.first, estimate.areaSquareMeters, "м²", materialPrice, estimate.canvasCost, materialInfo.second)

        val profileInfo = when (estimate.profileType) {
            "pvc" -> Triple("ПВХ профіль", "profile.pvc", 90.0)
            "shadow_kraab" -> Triple("Тіньовий профіль KRAAB", "profile.shadow_kraab", 380.0)
            "floating_led" -> Triple("Профіль для парящої стелі LED", "profile.floating_led", 420.0)
            "slott_tracks" -> Triple("Слот / трекова система", "profile.slott_tracks", 550.0)
            else -> Triple("Стандартний алюмінієвий профіль", "profile.standard_alu", 130.0)
        }
        val profileQty = estimate.perimeterMeters
        val profilePrice = if (profileQty > 0) estimate.profileCost / profileQty else price(profileInfo.second, profileInfo.third)
        add(profileInfo.first, profileQty, "м.п.", profilePrice, estimate.profileCost, profileInfo.second)

        when (estimate.complexity) {
            "multilevel" -> {
                val qty = max(2.0, precision.length(estimate.perimeterMeters * .35))
                add("Багаторівнева конструкція", qty, "м.п.", price("work.multilevel", 650.0), estimate.complexityCost, "work.multilevel")
            }
            "curved" -> {
                val qty = max(2.0, precision.length(estimate.perimeterMeters * .25))
                add("Криволінійна конструкція", qty, "м.п.", price("work.curved", 350.0), estimate.complexityCost, "work.curved")
            }
        }

        val optionRows = listOf(
            Triple("Світлова лінія", EstimateOptionId.LIGHT_LINE.code, EstimateOptionId.LIGHT_LINE.code) to ("м.п." to 850.0),
            Triple("Додатковий тіньовий профіль", EstimateOptionId.SHADOW_PROFILE.code, EstimateOptionId.SHADOW_PROFILE.code) to ("м.п." to 380.0),
            Triple("Додатковий парящий профіль", EstimateOptionId.FLOATING_PROFILE.code, EstimateOptionId.FLOATING_PROFILE.code) to ("м.п." to 420.0),
            Triple("Монтаж люстри", EstimateOptionId.CHANDELIER.code, EstimateOptionId.CHANDELIER.code) to ("шт." to 350.0),
            Triple("Точковий світильник", EstimateOptionId.SPOT.code, EstimateOptionId.SPOT.code) to ("шт." to 220.0),
            Triple("Обхід труби", EstimateOptionId.PIPE_PASS.code, EstimateOptionId.PIPE_PASS.code) to ("шт." to 180.0),
            Triple("Ніша для штор", EstimateOptionId.CURTAIN_NICHE.code, EstimateOptionId.CURTAIN_NICHE.code) to ("м.п." to 450.0),
            Triple("Додатковий кут", EstimateOptionId.EXTRA_CORNER.code, EstimateOptionId.EXTRA_CORNER.code) to ("шт." to 90.0),
            Triple("Складна стіна", EstimateOptionId.HARD_WALL.code, EstimateOptionId.HARD_WALL.code) to ("м.п." to 220.0)
        )
        optionRows.forEach { (spec, unitSpec) ->
            val (title, key, code) = spec
            val (unit, fallback) = unitSpec
            val qty = quantity(key)
            if (key == EstimateOptionId.SHADOW_PROFILE.code && estimate.profileType == "shadow_kraab") return@forEach
            if (key == EstimateOptionId.FLOATING_PROFILE.code && estimate.profileType == "floating_led") return@forEach
            add(title, qty, unit, price(code, fallback), code = code)
        }

        // Preserve the exact stored total if line-level rounding differs by a few currency units.
        val delta = roundMoney(estimate.totalCost - rows.sumOf { it.total })
        if (delta != 0.0) rows += EstimateLineItem("Коригування округлення", 1.0, "посл.", delta, delta, "system.rounding")
        return rows
    }

    fun toJson(rows: List<EstimateLineItem>): JSONArray = JSONArray().also { array ->
        rows.forEach { row -> array.put(JSONObject()
            .put("title", row.title).put("quantity", row.quantity).put("unit", row.unit)
            .put("unitPrice", row.unitPrice).put("total", row.total).put("code", row.code)) }
    }

    fun fromJson(detailsJson: String): List<EstimateLineItem> = runCatching {
        val array = JSONObject(detailsJson).optJSONArray("lineItems") ?: return emptyList()
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            EstimateLineItem(item.optString("title"), item.optDouble("quantity"), item.optString("unit"), item.optDouble("unitPrice"), item.optDouble("total"), item.optString("code"))
        }
    }.getOrDefault(emptyList())
}
