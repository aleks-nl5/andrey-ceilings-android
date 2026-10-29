package com.andreyceilings.shared.calculation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden values are asserted independently in go-core/core/parity_vectors_test.go.
 * Never update these expectations to match Kotlin without verifying the Go result.
 */
class KmpCeilingCalculatorParityTest {
    @Test
    fun selectedMeasurementResolutionMatchesGo() {
        fun geometryFor(step: Double) = KmpCeilingCalculator.geometry(
            GeometryInput(length = 1.006, width = 1.006, precisionStepMeters = step)
        )
        val coarse = geometryFor(0.1)
        assertEquals(1.0, coarse.area, 1e-8)
        assertEquals(4.0, coarse.perimeter, 1e-8)
        val centimetre = geometryFor(0.01)
        assertEquals(1.0201, centimetre.area, 1e-8)
        assertEquals(4.04, centimetre.perimeter, 1e-8)
        val fine = geometryFor(0.005)
        assertEquals(1.010025, fine.area, 1e-8)
        assertEquals(4.02, fine.perimeter, 1e-8)
        assertTrue(coarse.area != centimetre.area && fine.area != centimetre.area)
    }

    private val calculator = KmpCeilingCalculator

    @Test
    fun geometryMatchesGoForNormalFallbackAndCustomShapes() {
        assertEquals(RoomGeometry(20.52, 18.4, 4),
            calculator.geometry(GeometryInput(shape = "rectangle", length = 5.4, width = 3.8)))
        assertEquals(RoomGeometry(12.0, 14.0, 4),
            calculator.geometry(GeometryInput(shape = "rectangle", length = -1.0)))
        assertEquals(RoomGeometry(9.0, 12.0, 4),
            calculator.geometry(GeometryInput(shape = "square")))
        assertEquals(RoomGeometry(6.25, 10.0, 4),
            calculator.geometry(GeometryInput(shape = "square", width = 2.5)))
        assertEquals(RoomGeometry(14.0, 18.0, 6),
            calculator.geometry(GeometryInput(shape = "l_shaped",
                sideA = 5.0, sideB = 4.0, sideC = 2.0,
                sideD = 2.0, sideE = 3.0, sideF = 2.0)))
        assertEquals(RoomGeometry(15.0, 18.0, 6),
            calculator.geometry(GeometryInput(shape = "l_shaped", sideA = 5.0, sideB = 4.0)))
        assertEquals(RoomGeometry(12.0, 14.0, 5),
            calculator.geometry(GeometryInput(shape = "custom", length = 3.0, width = 4.0,
                diagonal1 = 5.0)))
        assertEquals(RoomGeometry(12.0, 14.0, 6),
            calculator.geometry(GeometryInput(shape = "custom", length = 3.0, width = 4.0,
                diagonal1 = 5.0, customSides = List(6) { 1.0 })))
        assertEquals(RoomGeometry(0.01, 0.4, 4),
            calculator.geometry(GeometryInput(shape = "rectangle", length = 0.05, width = 0.1)))
    }

    @Test
    fun fullDefaultCostWithExtraWorkMatchesGo() {
        val input = CostInput(area = 20.52, perimeter = 18.4,
            options = RoomOptions(chandeliersCount = 1.0, spotsGX53Count = 4.0,
                pipePassCount = 1.0))
        assertEquals(CostBreakdown(5951.0, 2392.0, 0.0, 1410.0, 9753.0),
            calculator.cost(input))
    }

    @Test
    fun partialPriceOverridesAndIntegratedGeometryMatchGoMobileApi() {
        val prices = PriceList(
            canvasPrices = mapOf("pvc_matte" to 100.0),
            profilePrices = mapOf("standard_alu" to 10.0),
            lightLinePerMeter = 50.0
        )
        val result = calculator.estimate(
            geometryInput = GeometryInput(length = 5.4, width = 3.8),
            // The Go mobile API ignores these stale dimensions and uses geometry().
            costInput = CostInput(area = 999.0, perimeter = 999.0,
                options = RoomOptions(lightLinesMeters = 2.0)),
            prices = prices
        )
        assertEquals(RoomGeometry(20.52, 18.4, 4), result.geometry)
        assertEquals(CostBreakdown(2052.0, 184.0, 0.0, 100.0, 2336.0), result.cost)
    }

    @Test
    fun multilevelCurvedAndProfileExclusionMatchGo() {
        assertEquals(CostBreakdown(2900.0, 2392.0, 4160.0, 0.0, 9452.0),
            calculator.cost(CostInput(area = 10.0, perimeter = 18.4, complexity = "multilevel")))
        assertEquals(CostBreakdown(2900.0, 2392.0, 1610.0, 0.0, 6902.0),
            calculator.cost(CostInput(area = 10.0, perimeter = 18.4, complexity = "curved")))
        assertEquals(0.0, calculator.cost(CostInput(area = 10.0, perimeter = 12.0,
            profileType = "shadow_kraab",
            options = RoomOptions(shadowProfileMeters = 12.0))).optionsCost)
        assertEquals(0.0, calculator.cost(CostInput(area = 10.0, perimeter = 12.0,
            profileType = "floating_led",
            options = RoomOptions(floatingCeilingMeters = 12.0))).optionsCost)
    }

    @Test
    fun negativeInputsFallbacksAndRoundingMatchGo() {
        assertEquals(CostBreakdown(0.0, 0.0, 0.0, 0.0, 0.0),
            calculator.cost(CostInput(area = -10.0, perimeter = -3.0,
                options = RoomOptions(spotsGX53Count = -2.0))))
        assertEquals(800.0, calculator.cost(CostInput(area = 0.0, perimeter = 0.0,
            options = RoomOptions(lightLinesMeters = 0.5,
                spotsGX53Count = 1.5, extraCorners = 0.5))).optionsCost)
        assertEquals(CostBreakdown(1.0, 0.0, 0.0, 0.0, 1.0),
            calculator.cost(CostInput(area = 0.005, perimeter = 0.0),
                PriceList(mapOf("pvc_matte" to 100.0), mapOf("standard_alu" to 0.0))))
        assertEquals(CostBreakdown(-1.0, 0.0, 0.0, 0.0, -1.0),
            calculator.cost(CostInput(area = 0.5, perimeter = 0.0),
                PriceList(mapOf("pvc_matte" to -1.0), mapOf("standard_alu" to 0.0))))
        assertEquals(2900.0, calculator.cost(CostInput(area = 10.0, perimeter = 0.0,
            materialType = "unknown"), calculator.defaultPriceList()).canvasCost)
        // An empty Go canvasPrices map resets the whole price list.
        assertEquals(2900.0, calculator.cost(CostInput(area = 10.0, perimeter = 0.0),
            PriceList(emptyMap(), mapOf("standard_alu" to 1.0))).canvasCost)
    }
}
