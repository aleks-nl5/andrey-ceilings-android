package com.andreyceilings.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.app.core.CeilingCalculator
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.shared.calculation.MeasurementPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Android UI's actual calculation adapter, rather than calling
 * :shared directly. No Go/AAR or JSON bridge is available in this build.
 */
@RunWith(AndroidJUnit4::class)
class CeilingCalculatorKmpInstrumentedTest {
    @Test
    fun androidCalculatorUsesKmpForFullQuoteAndOneCentimeterPrecision() {
        val timingPhases = mutableListOf<Pair<String, Long>>()
        val estimate = CeilingCalculator.estimate(
            length = 1.006, width = 1.006,
            pricePerM2 = 290.0, profilePrice = 130.0,
            priceItems = emptyList(),
            options = CalculatorOptions(
                lightLineMeters = 1.006, spotCount = 1.0,
                complexity = "multilevel"
            ),
            materialType = "pvc_matte", profileType = "standard_alu",
            precisionStepMeters = 0.01,
            onTimings = { timingPhases.addAll(it) }
        )
        assertEquals("Kotlin KMP", estimate.engine)
        assertEquals(1.0201, estimate.area, 1e-8)
        assertEquals(4.04, estimate.perimeter, 1e-8)
        assertEquals(296.0, estimate.canvasCost, 0.0)
        assertEquals(525.0, estimate.profileCost, 0.0)
        assertEquals(1300.0, estimate.complexityCost, 0.0)
        assertEquals(1079.0, estimate.optionsCost, 0.0)
        assertEquals(3200.0, estimate.total, 0.0)
        assertTrue(timingPhases.any { it.first == "kmp_calculate" })
        assertTrue(timingPhases.any { it.first == "total" })
        assertTrue(timingPhases.none { it.first.startsWith("go_") })
    }

    @Test
    fun androidPriceOverridesAreAppliedToEveryCostComponent() {
        val items = listOf(
            item("canvas.pvc_matte", 400.0),
            item("profile.standard_alu", 300.0),
            item("work.multilevel", 700.0),
            item("light.line", 900.0),
            item("light.spot", 10.0),
            item("work.shadow_profile", 100.0)
        )
        val estimate = CeilingCalculator.estimate(
            length = 5.0, width = 4.0,
            // Explicitly edited calculator input must override the saved price list.
            pricePerM2 = 500.0, profilePrice = 200.0,
            priceItems = items,
            options = CalculatorOptions(
                complexity = "multilevel",
                lightLineMeters = 1.006,
                spotCount = 2.0,
                shadowProfileMeters = 2.0
            ),
            materialType = "pvc_matte",
            profileType = "standard_alu",
            precisionStepMeters = 0.01
        )
        assertEquals(20.0, estimate.area, 1e-8)
        assertEquals(18.0, estimate.perimeter, 1e-8)
        assertEquals(10000.0, estimate.canvasCost, 0.0)
        assertEquals(3600.0, estimate.profileCost, 0.0)
        assertEquals(4410.0, estimate.complexityCost, 0.0)
        assertEquals(1129.0, estimate.optionsCost, 0.0)
        assertEquals(19139.0, estimate.total, 0.0)
    }

    @Test
    fun eachSelectedMeasurementPrecisionReachesKmpFromAndroid() {
        val areas = mapOf(
            MeasurementPrecision.TEN_CENTIMETERS to 1.0,
            MeasurementPrecision.ONE_CENTIMETER to 1.0201,
            MeasurementPrecision.FIVE_MILLIMETERS to 1.010025,
            MeasurementPrecision.ONE_MILLIMETER to 1.012036
        )
        areas.forEach { (precision, expectedArea) ->
            val estimate = CeilingCalculator.estimate(
                length = 1.006, width = 1.006,
                pricePerM2 = 290.0, profilePrice = 130.0,
                priceItems = emptyList(), options = CalculatorOptions(),
                materialType = "pvc_matte", profileType = "standard_alu",
                precisionStepMeters = precision.stepMeters
            )
            assertEquals("Kotlin KMP", estimate.engine)
            assertEquals(expectedArea, estimate.area, 1e-7)
        }
    }

    private fun item(code: String, price: Double) = PriceItemEntity(
        code = code, title = code, category = "test", unit = "test", price = price
    )
}
