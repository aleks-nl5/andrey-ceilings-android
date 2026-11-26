package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.math.round
import kotlin.math.abs

/** Currency identity is UAH in storage; the symbol is presentation only. */
internal const val HRYVNIA_SYMBOL = "₴"

/** Exact integer cents with the current Ukrainian decimal convention, independent of UI language. */
fun formatMeasurementMoneyNumber(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    return "$sign${abs(cents / 100)},${abs(cents % 100).toString().padStart(2, '0')}"
}

fun measurementMoney(cents: Long): String = "${formatMeasurementMoneyNumber(cents)} $HRYVNIA_SYMBOL"

/** Ukrainian presentation only. Never feed these rounded strings back into geometry/storage. */
fun formatMeasurementArea(value: Double, precision: MeasurementPrecision? = null): String =
    formatMeasurementNumber(value, precision?.areaDigits ?: 6, trimZeros = precision == null)

/** Display-only rounding to 0.01 m²; not used for geometry or financial quantities. */
fun formatMeasurementAreaCompact(value: Double): String =
    formatMeasurementNumber(value, 2, trimZeros = false)

/** Compact live perimeter, independent of capture precision and never used in calculations. */
fun formatMeasurementLengthCompact(value: Double): String =
    formatMeasurementNumber(value, 2, trimZeros = false)

fun formatMeasurementLength(value: Double, precision: MeasurementPrecision? = null): String =
    formatMeasurementNumber(value, precision?.lengthDigits ?: 3, trimZeros = precision == null)

private fun formatMeasurementNumber(value: Double, digits: Int, trimZeros: Boolean): String {
    val scale = (1..digits).fold(1L) { factor, _ -> factor * 10 }
    val scaled = value * scale
    if (!scaled.isFinite() || scaled < 0 || scaled >= Long.MAX_VALUE.toDouble()) return "—"
    val units = round(scaled).toLong()
    val fraction = (units % scale).toString().padStart(digits, '0')
        .let { if (trimZeros) it.trimEnd('0') else it }
    return (units / scale).toString() + if (fraction.isEmpty()) "" else ",$fraction"
}
