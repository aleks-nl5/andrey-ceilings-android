package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*

/** Deliberately removable experiment. Placed or geometry-linked optional work is read-only. */
object BillableOverrideRules {
    fun eligible(source: LiveEstimateSource, element: MeasurementElement,
        role: BusinessRole? = element.businessRole): Boolean {
        val selection = element.source as? ElementSource.CatalogSelection ?: return false
        if (selection.availability != CatalogAvailability.ACTIVE || element.deletedAt != null) return false
        return when (role) {
            BusinessRole.CANVAS -> selection.quantityMode == QuantityMode.CEILING_AREA &&
                element.quantity == ElementQuantity.CeilingArea && element.unit == "м²"
            BusinessRole.PRIMARY_PROFILE -> selection.quantityMode == QuantityMode.CEILING_PERIMETER &&
                element.quantity == ElementQuantity.CeilingPerimeter && element.unit == "м.п."
            BusinessRole.ADDITIONAL_WORK -> element.quantity is ElementQuantity.Exact &&
                element.unit in setOf("шт.", "компл.", "м.п.", "м²") &&
                (selection.quantityMode == QuantityMode.MANUAL ||
                    selection.quantityMode == QuantityMode.ENTERED_LENGTH && element.unit == "м.п.") &&
                selection.placementKind == PlacementKind.NONE && element.placement == ElementPlacement.UNPLACED &&
                element.placedQuantityMilli == 0L && source.points.none { it.elementUuid == element.uuid } &&
                source.lines.none { it.elementUuid == element.uuid } && source.areas.none { it.elementUuid == element.uuid } &&
                runCatching { ElementQuantityRules.validate(element.quantity, element.unit, selection.quantityMode) }.isSuccess &&
                (element.unit !in setOf("шт.", "компл.") || element.quantity.milli % 1000L == 0L)
            null -> false
        }
    }
}
