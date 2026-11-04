package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.prices.QuantityMode

/** Strict positive decimal -> integer thousandths. Never rounds or passes through Double. */
fun parseElementMilli(text: String, unit: String?): Long? {
    val value = text.trim()
    if (!Regex("[0-9]+(?:[.,][0-9]{1,3})?").matches(value)) return null
    val parts = value.replace(',', '.').split('.')
    val whole = parts[0].toLongOrNull() ?: return null
    if (whole > ElementQuantityRules.MAX_MILLI / 1000) return null
    val fraction = parts.getOrNull(1)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
    val milli = whole * 1000 + fraction
    return milli.takeIf { it in 1..ElementQuantityRules.MAX_MILLI &&
        !unit.isNullOrBlank() && (unit.trim() != "шт." || it % 1000L == 0L) }
}

fun formatElementMilli(milli: Long): String {
    val fraction = (milli % 1000).toString().padStart(3, '0').trimEnd('0')
    return (milli / 1000).toString() + if (fraction.isEmpty()) "" else ",$fraction"
}

fun formatElementQuantity(quantity: ElementQuantity, unit: String?): String = when (quantity) {
    ElementQuantity.Unknown -> "Потрібно уточнити"
    is ElementQuantity.Exact -> "${formatElementMilli(quantity.milli)} ${unit.orEmpty()}"
    is ElementQuantity.Range -> "${formatElementMilli(quantity.minMilli)}–${formatElementMilli(quantity.maxMilli)} ${unit.orEmpty()}"
    ElementQuantity.CeilingArea -> "За площею стелі"
    ElementQuantity.CeilingPerimeter -> "За периметром стелі"
}

enum class ElementQuantityKind(val label: String) { UNKNOWN("Невідомо"), EXACT("Точно"), RANGE("Діапазон") }

data class ElementQuantityInput(
    val kind: ElementQuantityKind = ElementQuantityKind.UNKNOWN,
    val exact: String = "", val minimum: String = "", val maximum: String = ""
) {
    fun quantity(unit: String?, mode: QuantityMode? = null): ElementQuantity? {
        val result = when (mode) {
            QuantityMode.CEILING_AREA -> ElementQuantity.CeilingArea
            QuantityMode.CEILING_PERIMETER -> ElementQuantity.CeilingPerimeter
            else -> when (kind) {
                ElementQuantityKind.UNKNOWN -> ElementQuantity.Unknown
                ElementQuantityKind.EXACT -> ElementQuantity.Exact(parseElementMilli(exact, unit) ?: return null)
                ElementQuantityKind.RANGE -> ElementQuantity.Range(
                    parseElementMilli(minimum, unit) ?: return null, parseElementMilli(maximum, unit) ?: return null)
            }
        }
        return try { ElementQuantityRules.validate(result, unit, mode); result }
        catch (_: MeasurementElementFailure.InvalidQuantity) { null }
    }
    companion object {
        fun from(quantity: ElementQuantity): ElementQuantityInput = when (quantity) {
            is ElementQuantity.Exact -> ElementQuantityInput(ElementQuantityKind.EXACT, exact = formatElementMilli(quantity.milli))
            is ElementQuantity.Range -> ElementQuantityInput(ElementQuantityKind.RANGE,
                minimum = formatElementMilli(quantity.minMilli), maximum = formatElementMilli(quantity.maxMilli))
            else -> ElementQuantityInput()
        }
    }
}
