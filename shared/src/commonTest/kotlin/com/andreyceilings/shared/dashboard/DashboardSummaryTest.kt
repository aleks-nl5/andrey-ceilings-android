package com.andreyceilings.shared.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardSummaryTest {
    @Test
    fun countsOnlyMeasurementsFromActiveProjectsAndIgnoresInvalidArea() {
        val result = DashboardSummary.fromRecords(
            setOf(1L, 3L),
            listOf(
                DashboardMeasurement(1L, 12.25),
                DashboardMeasurement(3L, 7.2),
                DashboardMeasurement(2L, 100.0), // Deleted project is excluded.
                DashboardMeasurement(1L, Double.NaN),
                DashboardMeasurement(3L, -8.0)
            )
        )
        assertEquals(2, result.projectCount)
        assertEquals(4, result.measurementCount)
        assertEquals(19.45, result.measuredAreaSquareMeters, 0.000001)
        assertEquals("19,5 м²", result.areaLabel())
    }

    @Test
    fun emptyAndInvalidStatsNeverBecomeInventedDashboardNumbers() {
        val empty = DashboardSummary.fromRecords(emptySet(), emptyList())
        assertEquals(0, empty.projectCount)
        assertEquals(0, empty.measurementCount)
        assertEquals("0,0 м²", empty.areaLabel())
        assertEquals("—", DashboardSummary(1, 1, Double.POSITIVE_INFINITY).areaLabel())
    }
}
