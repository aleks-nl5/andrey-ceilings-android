package com.andreyceilings.shared.calculation

import kotlin.math.floor

/** Measurement resolution in meters. Money always rounds independently. */
enum class MeasurementPrecision(
    val stepMeters: Double,
    val label: String,
    val lengthDigits: Int,
    val areaDigits: Int
) {
    TEN_CENTIMETERS(0.1, "10 см", 1, 2),
    ONE_CENTIMETER(0.01, "1 см", 2, 4),
    FIVE_MILLIMETERS(0.005, "5 мм", 3, 6),
    ONE_MILLIMETER(0.001, "1 мм", 3, 6);

    fun length(value: Double): Double = floor(value / stepMeters + 0.5) * stepMeters
    fun area(value: Double): Double {
        val stepArea = stepMeters * stepMeters
        return floor(value / stepArea + 0.5) * stepArea
    }

    companion object {
        fun fromStep(value: Double): MeasurementPrecision? = entries.firstOrNull { it.stepMeters == value }
    }
}
