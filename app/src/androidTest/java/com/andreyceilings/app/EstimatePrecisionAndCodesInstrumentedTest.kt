package com.andreyceilings.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.core.EstimateLineItems
import com.andreyceilings.app.data.db.EstimateEntity
import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.calculation.MeasurementPrecision
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EstimatePrecisionAndCodesInstrumentedTest {
    @Test
    fun canonicalSpotAndLightLineAmountsRemainConsistentWithGoAndPdfSnapshot() {
        val precision = MeasurementPrecision.ONE_CENTIMETER
        val details = JSONObject()
            .put("measurementPrecisionMeters", precision.stepMeters)
            .put(EstimateOptionId.LIGHT_LINE.code, precision.length(1.006))
            .put(EstimateOptionId.SPOT.code, 1)
        val estimate = EstimateEntity(
            projectId = 1,
            name = "Кімната",
            material = "pvc_matte",
            profileType = "standard_alu",
            complexity = "multilevel",
            lengthMeters = 1.01,
            widthMeters = 1.01,
            areaSquareMeters = 1.0201,
            perimeterMeters = 4.04,
            canvasCost = 296.0,
            profileCost = 525.0,
            complexityCost = 1300.0,
            optionsCost = 1079.0,
            totalCost = 3200.0
        )
        val rows = EstimateLineItems.create(estimate, details, emptyList(), precision)
        assertEquals(3200.0, rows.sumOf { it.total }, 0.0)
        assertTrue(rows.any { it.code == "light.spot" && it.quantity == 1.0 })
        assertTrue(rows.any { it.code == "light.line" && it.quantity == 1.01 })
        assertTrue(rows.none { it.code == "system.rounding" })
        details.put("lineItems", EstimateLineItems.toJson(rows))
        val restored = EstimateLineItems.fromJson(details.toString())
        assertEquals(rows, restored)
        assertEquals(precision.stepMeters, details.getDouble("measurementPrecisionMeters"), 0.0)
    }
}
