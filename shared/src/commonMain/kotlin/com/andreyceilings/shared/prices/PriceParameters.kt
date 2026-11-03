package com.andreyceilings.shared.prices

enum class QuantityMode(val value: String) {
    MANUAL("manual"), CEILING_AREA("ceiling_area"), CEILING_PERIMETER("ceiling_perimeter"), ENTERED_LENGTH("entered_length")
}

enum class PlacementKind(val value: String) {
    NONE("none"), POINT("point"), LINE("line"), AREA("area")
}

/** Placement is independent of quantity and never creates a financial row. */
object PriceParameters {
    fun validate(unit: String, quantityMode: QuantityMode) {
        require(unit.isNotBlank()) { "Вкажіть одиницю вимірювання." }
        require(when (quantityMode) {
            QuantityMode.MANUAL -> true
            QuantityMode.CEILING_AREA -> unit == "м²"
            QuantityMode.CEILING_PERIMETER, QuantityMode.ENTERED_LENGTH -> unit == "м.п."
        }) { "Одиниця не відповідає способу визначення кількості." }
    }
}
