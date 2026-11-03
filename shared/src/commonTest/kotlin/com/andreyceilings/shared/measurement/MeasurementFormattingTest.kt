package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.test.*

class MeasurementFormattingTest {
    @Test fun totalsUseCapturedPrecisionAndCommaWithoutBinaryTails() {
        val expected = listOf("20,40" to "18,2", "20,4000" to "18,20",
            "20,400000" to "18,200", "20,400000" to "18,200")
        MeasurementPrecision.entries.forEachIndexed { i, precision ->
            assertEquals(expected[i].first, formatMeasurementArea(20.400000000000002, precision))
            assertEquals(expected[i].second, formatMeasurementLength(18.200000000000003, precision))
        }
        assertEquals("0,3000", formatMeasurementArea(0.1 + 0.2, MeasurementPrecision.ONE_CENTIMETER))
        assertEquals("4000000,000000", formatMeasurementArea(4_000_000.0, MeasurementPrecision.ONE_MILLIMETER))
        assertEquals("18,205", formatMeasurementLength(18.205000000000002, MeasurementPrecision.FIVE_MILLIMETERS))
    }

    @Test fun missingHistoricalPrecisionUsesStoredDigitsWithoutInventingADefault() {
        assertEquals("20,4", formatMeasurementArea(20.400000000000002))
        assertEquals("5,123", formatMeasurementLength(5.123))
        assertEquals("0", formatMeasurementArea(-0.0))
        assertEquals("—", formatMeasurementArea(Double.NaN))
        assertEquals("—", formatMeasurementLength(Double.POSITIVE_INFINITY))
    }
}
