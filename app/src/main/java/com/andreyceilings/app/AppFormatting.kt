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

internal fun materialDisplayName(code: String): String = when (code) {
    "pvc_matte" -> "ПВХ матовий"
    "pvc_glossy" -> "ПВХ глянцевий"
    "pvc_satin" -> "ПВХ сатиновий"
    "fabric" -> "Тканинний Descor"
    "fireproof_km2" -> "Негорючий KM2"
    "acoustic" -> "Акустичний"
    else -> code
}

internal fun profileDisplayName(code: String): String = when (code) {
    "standard_alu" -> "Алюмінієвий гарпунний"
    "pvc" -> "ПВХ стіновий"
    "shadow_kraab" -> "Тіньовий EuroKRAAB"
    "floating_led" -> "Парящий з LED"
    "slott_tracks" -> "Слот / трековий"
    else -> code
}

/** Preserve a stored measurement's actual digits after the global precision changes. */
internal fun formatStoredMeters(value: Double): String =
    String.format(java.util.Locale.getDefault(), "%.3f", value).trimEnd('0').trimEnd('.', ',')

internal fun formatStoredSquareMeters(value: Double): String =
    String.format(java.util.Locale.getDefault(), "%.6f", value).trimEnd('0').trimEnd('.', ',')
