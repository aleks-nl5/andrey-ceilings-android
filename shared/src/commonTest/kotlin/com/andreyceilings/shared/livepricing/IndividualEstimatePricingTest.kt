package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

class IndividualEstimatePricingTest {
    @Test fun selectedScopeAndBasisAreBoundToExactlyOneLiveMeasurement() {
        val geometry = validateMeasurementCreate(MeasurementCreateDraft(1, MeasurementPrecision.ONE_MILLIMETER, "Room", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")), "m")).geometry!!
        val canvas = ActiveCatalogItem("c", "g", "c", "Canvas", "м²", QuantityMode.CEILING_AREA, PlacementKind.NONE, 1.0, 1, 1)
        val profile = canvas.copy(uuid = "p", code = "p", title = "Profile", unit = "м.п.", quantityMode = QuantityMode.CEILING_PERIMETER)
        fun element(item: ActiveCatalogItem, quantity: ElementQuantity) = MeasurementElement(item.uuid, "m",
            ElementSource.CatalogSelection(item.uuid, item.code, item.quantityMode, item.placementKind, CatalogAvailability.ACTIVE),
            item.title, "", item.unit, ElementApproval.APPROVED, quantity, ElementPlacement.UNPLACED, 0, 1, 1, null)
        val source = LiveEstimateSource("project", "m", geometry, listOf(element(canvas, ElementQuantity.CeilingArea), element(profile, ElementQuantity.CeilingPerimeter)))
        val catalog = ActiveCatalogSnapshot(listOf(canvas, profile))
        var selection = IndividualEstimateSelection("project", "m", "quote", source.signature, catalog.signature, "")
        val live = LivePricingEngine.buildPreview(source, catalog, BusinessRoleBindings(source.signature,
            mapOf("c" to BusinessRole.CANVAS, "p" to BusinessRole.PRIMARY_PROFILE)), "quote", scopeSignature = selection.scopeSignature)
        selection = selection.copy(expectedPreviewSignature = live.signature)
        val preview = IndividualEstimatePricing.previewSelection(selection, live)
        assertTrue(preview.canFinalizeFull)
        assertEquals(2600, preview.selectedIncludedTotalCents)
        assertEquals(preview, IndividualEstimatePricing.previewSelection(selection, live))
        for (bad in listOf(selection.copy(projectUuid = "other"), selection.copy(measurementUuid = "other"),
            selection.copy(basisSourceSignature = "changed"), selection.copy(basisCatalogSignature = "changed"),
            selection.copy(expectedPreviewSignature = "old"), selection.copy(includedSourceKeys = emptySet()))) {
            val result = IndividualEstimatePricing.previewSelection(bad, live)
            assertFalse(result.canFinalizeFull)
            assertFalse(result.canRequestPartialAcknowledgement)
        }
    }
}
