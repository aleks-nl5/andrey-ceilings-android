package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.serialization.Serializable

@Serializable
data class WorkspacePlacementDraft(
    val elements: Map<String, MeasurementElement> = emptyMap(),
    val points: Map<String, PointPlacement> = emptyMap(),
    val replacedMaterials: Map<String, ElementVersion> = emptyMap(),
    val removedWorks: Map<String, ElementVersion> = emptyMap(),
    val editedWorks: Map<String, MeasurementElement> = emptyMap(),
    val removedPoints: Map<String, PointPlacement> = emptyMap()
) {
    val isEmpty: Boolean get() = elements.isEmpty() && points.isEmpty() && replacedMaterials.isEmpty() &&
        removedWorks.isEmpty() && editedWorks.isEmpty() && removedPoints.isEmpty()
}

internal fun ElementCatalogItem.toWorkspaceElement(
    uuid: String,
    measurementUuid: String,
    quantity: ElementQuantity
) = MeasurementElement(
    uuid, measurementUuid,
    ElementSource.CatalogSelection(priceItemUuid = this.uuid, code = code, quantityMode = mode,
        placementKind = placementKind, availability = CatalogAvailability.ACTIVE),
    title, "", unit, ElementApproval.DISCUSSING, quantity, ElementPlacement.UNPLACED,
    0, 0, 0, deletedAt = null, businessRole = BusinessRole.ADDITIONAL_WORK
)

internal fun ElementCatalogItem.supportsWorkspacePlacement(): Boolean = when (placementKind) {
    PlacementKind.POINT -> mode == QuantityMode.MANUAL && unit == "шт."
    PlacementKind.LINE -> mode in setOf(QuantityMode.MANUAL, QuantityMode.ENTERED_LENGTH) && unit == "м.п."
    PlacementKind.AREA -> mode == QuantityMode.MANUAL && unit in setOf("шт.", "м²")
    PlacementKind.NONE -> false
}

internal fun ElementCatalogItem.materialRole(): BusinessRole? = when {
    placementKind != PlacementKind.NONE -> null
    mode == QuantityMode.CEILING_AREA && unit == "м²" -> BusinessRole.CANVAS
    mode == QuantityMode.CEILING_PERIMETER && unit == "м.п." -> BusinessRole.PRIMARY_PROFILE
    else -> null
}
internal fun BusinessRole.isWorkspaceMaterial(): Boolean =
    this == BusinessRole.CANVAS || this == BusinessRole.PRIMARY_PROFILE

internal fun QuantityMode.workspaceQuantity(): ElementQuantity = when (this) {
    QuantityMode.CEILING_AREA -> ElementQuantity.CeilingArea
    QuantityMode.CEILING_PERIMETER -> ElementQuantity.CeilingPerimeter
    else -> ElementQuantity.Unknown
}
