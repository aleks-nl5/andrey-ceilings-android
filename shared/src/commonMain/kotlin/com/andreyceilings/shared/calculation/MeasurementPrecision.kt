package com.andreyceilings.shared.calculation

enum class MeasurementPrecision(val stepMeters: Double, val label: String) {
    TEN_CENTIMETERS(0.1, "10 см"),
    ONE_CENTIMETER(0.01, "1 см"),
    FIVE_MILLIMETERS(0.005, "5 мм"),
    ONE_MILLIMETER(0.001, "1 мм");
    companion object {
        fun fromStep(value: Double): MeasurementPrecision? = entries.firstOrNull { it.stepMeters == value }
    }
}
