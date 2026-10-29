package com.andreyceilings.app

import com.andreyceilings.shared.calculation.MeasurementPrecision

internal fun formatMeasuredLength(value: Double, precision: MeasurementPrecision): String =
    String.format(java.util.Locale.getDefault(), "%.${precision.lengthDigits}f", value)

internal fun formatMeasuredArea(value: Double, precision: MeasurementPrecision): String =
    String.format(java.util.Locale.getDefault(), "%.${precision.areaDigits}f", value)

internal fun calculatorOptionsForSave(value: String): Double = value.toSafeDouble()

internal fun formatNumber(value: Double) = String.format("%.2f", value)
internal fun money(value: Double) = String.format("%,.0f ₴", value)

internal fun Double.toPriceInput(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

internal fun String.toSafeDouble(): Double = replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

internal fun Double.format2(): String = String.format(java.util.Locale.getDefault(), "%.2f", this)
