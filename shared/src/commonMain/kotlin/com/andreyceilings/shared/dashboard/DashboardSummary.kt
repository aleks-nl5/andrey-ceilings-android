package com.andreyceilings.shared.dashboard

import kotlin.math.roundToLong

/** A measurement must belong to a visible (non-deleted) project to count on the dashboard. */
data class DashboardMeasurement(val projectId: Long, val areaSquareMeters: Double)

/** Values on the home screen are derived only from real, active SQLDelight records. */
data class DashboardSummary(
    val projectCount: Int,
    val measurementCount: Int,
    val measuredAreaSquareMeters: Double
) {
    fun areaLabel(): String {
        if (!measuredAreaSquareMeters.isFinite() || measuredAreaSquareMeters < 0.0 ||
            measuredAreaSquareMeters > Long.MAX_VALUE / 10.0
        ) return "—"
        val tenths = (measuredAreaSquareMeters * 10.0).roundToLong()
        return "${tenths / 10},${tenths % 10} м²"
    }

    companion object {
        fun fromRecords(
            activeProjectIds: Set<Long>,
            measurements: List<DashboardMeasurement>
        ): DashboardSummary {
            val visible = measurements.filter { it.projectId in activeProjectIds }
            val area = visible.sumOf {
                it.areaSquareMeters.takeIf { value -> value.isFinite() && value >= 0.0 } ?: 0.0
            }
            return DashboardSummary(
                projectCount = activeProjectIds.size,
                measurementCount = visible.size,
                measuredAreaSquareMeters = area
            )
        }
    }
}
