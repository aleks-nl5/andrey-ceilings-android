package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.test.*

class MeasurementFormattingTest {
    @Test fun hryvniaPresentationUsesExactCentsAndSeparateSymbol() {
        assertEquals("0,00 ₴", measurementMoney(0))
        assertEquals("0,01 ₴", measurementMoney(1))
        assertEquals("10080,00 ₴", measurementMoney(1008000))
        assertEquals("19706,20 ₴", measurementMoney(1970620))
        assertEquals("-0,01 ₴", measurementMoney(-1))
        assertEquals("92233720368547758,07", formatMeasurementMoneyNumber(Long.MAX_VALUE))
        assertEquals("-92233720368547758,08", formatMeasurementMoneyNumber(Long.MIN_VALUE))
    }

    @kotlin.test.Test fun workspaceAreaShortensOnlyVisiblePrecision() {
        kotlin.test.assertEquals("6,00", formatMeasurementAreaCompact(6.0))
        kotlin.test.assertEquals("13,76", formatMeasurementAreaCompact(13.7608))
        // The canonical high-precision display and stored geometry are unchanged.
        kotlin.test.assertEquals("13,7608", formatMeasurementArea(13.7608))
        kotlin.test.assertEquals("18,20", formatMeasurementLengthCompact(18.200000000000003))
        kotlin.test.assertEquals("18,21", formatMeasurementLengthCompact(18.207))
        kotlin.test.assertEquals("—", formatMeasurementLengthCompact(Double.NaN))
    }

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
