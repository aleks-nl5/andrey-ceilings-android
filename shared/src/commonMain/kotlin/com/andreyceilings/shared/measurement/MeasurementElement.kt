package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode

/** Working quantities in thousandths, independent of measurement precision and placements. */
sealed interface ElementQuantity {
    data object Unknown : ElementQuantity
    data class Exact(val milli: Long) : ElementQuantity
    data class Range(val minMilli: Long, val maxMilli: Long) : ElementQuantity
    data object CeilingArea : ElementQuantity
    data object CeilingPerimeter : ElementQuantity
}

enum class ElementApproval(val value: String) { DISCUSSING("discussing"), APPROVED("approved") }
enum class ElementPlacement(val value: String) { UNPLACED("unplaced"), APPROXIMATE("approximate"), EXACT("exact") }
enum class CatalogAvailability { ACTIVE, DELETED_OR_MISSING }

sealed interface ElementSource {
    data object Wish : ElementSource
    /** Selection-time metadata. No sale price or automatic rebinding. */
    data class CatalogSelection(
        val priceItemUuid: String,
        val code: String,
        val quantityMode: QuantityMode,
        val placementKind: PlacementKind,
        val availability: CatalogAvailability
    ) : ElementSource
}

data class ElementVersion(val uuid: String, val measurementUuid: String, val expectedUpdatedAt: Long)

data class MeasurementElement(
    val uuid: String,
    val measurementUuid: String,
    val source: ElementSource,
    val label: String,
    val note: String,
    val unit: String?,
    val approval: ElementApproval,
    val quantity: ElementQuantity,
    val placement: ElementPlacement,
    val placedQuantityMilli: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?
) {
    val version get() = ElementVersion(uuid, measurementUuid, updatedAt)
}

/** Keep never converts incompatible units/bases; Replace is explicit caller correction. */
sealed interface LinkQuantity {
    data object Keep : LinkQuantity
    data class Replace(val quantity: ElementQuantity) : LinkQuantity
}

sealed class MeasurementElementFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ParentUnavailable : MeasurementElementFailure("Measurement or project missing/deleted")
    class CatalogUnavailable : MeasurementElementFailure("Catalog item or group missing/deleted")
    class InvalidQuantity : MeasurementElementFailure("Invalid quantity, bounds, unit or basis")
    class InvalidInput : MeasurementElementFailure("Invalid element input")
    class StaleVersion : MeasurementElementFailure("Element changed or deleted; reload")
    class RetryConflict : MeasurementElementFailure("UUID already used with a different payload or tombstone")
    class IncompatibleQuantity : MeasurementElementFailure("Explicit quantity correction required")
    class DatabaseUnavailable(cause: Throwable) : MeasurementElementFailure("Element database unavailable", cause)
}

object ElementQuantityRules {
    const val MAX_MILLI = 9_000_000_000_000_000L

    fun validate(quantity: ElementQuantity, unit: String?, mode: QuantityMode? = null) {
        fun reject(): Nothing = throw MeasurementElementFailure.InvalidQuantity()
        if (unit != null && unit.isBlank()) reject()
        fun value(milli: Long) {
            if (unit.isNullOrBlank() || milli !in 1..MAX_MILLI || (unit == "шт." && milli % 1000L != 0L)) reject()
        }
        when (quantity) {
            is ElementQuantity.Exact -> value(quantity.milli)
            is ElementQuantity.Range -> {
                value(quantity.minMilli); value(quantity.maxMilli)
                if (quantity.minMilli >= quantity.maxMilli) reject()
            }
            else -> Unit
        }
        when (mode) {
            null, QuantityMode.MANUAL, QuantityMode.ENTERED_LENGTH -> {
                if (quantity == ElementQuantity.CeilingArea || quantity == ElementQuantity.CeilingPerimeter) reject()
                if (mode == QuantityMode.ENTERED_LENGTH && unit != "м.п.") reject()
            }
            QuantityMode.CEILING_AREA -> if (quantity != ElementQuantity.CeilingArea || unit != "м²") reject()
            QuantityMode.CEILING_PERIMETER -> if (quantity != ElementQuantity.CeilingPerimeter || unit != "м.п.") reject()
        }
    }
}
