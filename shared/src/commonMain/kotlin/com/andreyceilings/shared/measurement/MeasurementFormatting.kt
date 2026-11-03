package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.math.round

/** Ukrainian presentation only. Never feed these rounded strings back into geometry/storage. */
fun formatMeasurementArea(value: Double, precision: MeasurementPrecision? = null): String =
    formatMeasurementNumber(value, precision?.areaDigits ?: 6, trimZeros = precision == null)

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
