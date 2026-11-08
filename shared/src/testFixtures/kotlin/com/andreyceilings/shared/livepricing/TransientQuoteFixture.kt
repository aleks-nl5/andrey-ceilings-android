package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*

object TransientQuoteFixture {
    fun loaded(optionalCount: Int = 1): PricingReadResult.Loaded {
        val geometry = checkNotNull(validateMeasurementCreate(MeasurementCreateDraft(1, MeasurementPrecision.ONE_MILLIMETER,
            "Кухня", "", MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")), "m")).geometry)
        val canvas = ActiveCatalogItem("c", "g", "c", "Полотно", "м²", QuantityMode.CEILING_AREA, PlacementKind.NONE, 1.0, 1, 1)
        val profile = canvas.copy(uuid = "p", code = "p", title = "Профіль", unit = "м.п.", quantityMode = QuantityMode.CEILING_PERIMETER)
        val optional = canvas.copy(uuid = "o", code = "o", title = "Додаткова робота", unit = "шт.", quantityMode = QuantityMode.MANUAL, salePrice = 2.0)
        val discussing = optional.copy(uuid = "d", code = "d", title = "Можлива робота")
        fun element(item: ActiveCatalogItem, q: ElementQuantity, role: BusinessRole,
            approval: ElementApproval = ElementApproval.APPROVED, uuid: String = item.uuid) = MeasurementElement(uuid, "m",
            ElementSource.CatalogSelection(item.uuid, item.code, item.quantityMode, item.placementKind, CatalogAvailability.ACTIVE),
            item.title, "", item.unit, approval, q, ElementPlacement.UNPLACED, 0, 1, 1, null, role)
        val source = LiveEstimateSource("project", "m", geometry,
            listOf(element(canvas, ElementQuantity.CeilingArea, BusinessRole.CANVAS),
                element(profile, ElementQuantity.CeilingPerimeter, BusinessRole.PRIMARY_PROFILE),
                element(discussing, ElementQuantity.Exact(1000), BusinessRole.ADDITIONAL_WORK, ElementApproval.DISCUSSING)) +
                (0 until optionalCount).map { element(optional, ElementQuantity.Exact(1000), BusinessRole.ADDITIONAL_WORK, uuid = "o$it") })
        return PricingReadResult.Loaded(source, ActiveCatalogSnapshot(listOf(canvas, profile, optional, discussing)))
    }

    fun presenter(data: PricingReadResult.Loaded = loaded()) = TransientQuotePresenter(data.source.measurementUuid).also {
        it.acceptRead(it.beginRead(), data)
    }
}
