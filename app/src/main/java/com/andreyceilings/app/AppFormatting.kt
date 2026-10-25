package com.andreyceilings.app

internal fun calculatorOptionsForSave(value: String): Double = value.toSafeDouble()

internal fun formatNumber(value: Double) = String.format("%.2f", value)
internal fun money(value: Double) = String.format("%,.0f ₴", value)

internal fun Double.toPriceInput(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

internal fun String.toSafeDouble(): Double = replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0