package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

class MandatoryPricingTest {
    private data class Ceiling(val source: LiveEstimateSource, val catalog: ActiveCatalogSnapshot,
        val roles: Map<String, BusinessRole>)

    private fun ceiling(id: String = "m", canvas: Boolean = true, profile: Boolean = true,
        extra: Boolean = false, potentialBase: BusinessRole? = null, optionalUnknown: Boolean = false,
        zero: Boolean = false): Ceiling {
        val geometry = validateMeasurementCreate(MeasurementCreateDraft(1, MeasurementPrecision.ONE_MILLIMETER, "Room", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")), id)).geometry!!
        val items = mutableListOf<ActiveCatalogItem>()
        val elements = mutableListOf<MeasurementElement>()
        val roles = mutableMapOf<String, BusinessRole>()
        fun add(role: BusinessRole, unit: String, mode: QuantityMode, quantity: ElementQuantity) {
            val key = "${role.name}:$id"
            val item = ActiveCatalogItem(key, "g", key, key, unit, mode, PlacementKind.NONE, if (zero) 0.0 else 1.0, 1, 1)
            items += item
            roles[key] = role
            elements += MeasurementElement(key, id, ElementSource.CatalogSelection(key, key, mode, PlacementKind.NONE, CatalogAvailability.ACTIVE),
                key, "", unit, if (role == potentialBase) ElementApproval.DISCUSSING else ElementApproval.APPROVED,
                quantity, ElementPlacement.UNPLACED, 0, 1, 1, null)
        }
        if (canvas) add(BusinessRole.CANVAS, "м²", QuantityMode.CEILING_AREA, ElementQuantity.CeilingArea)
        if (profile) add(BusinessRole.PRIMARY_PROFILE, "м.п.", QuantityMode.CEILING_PERIMETER, ElementQuantity.CeilingPerimeter)
        if (extra) add(BusinessRole.ADDITIONAL_WORK, "шт.", QuantityMode.MANUAL, if (optionalUnknown) ElementQuantity.Unknown else ElementQuantity.Exact(1000))
        return Ceiling(LiveEstimateSource("p", id, geometry, elements), ActiveCatalogSnapshot(items), roles)
    }
    private fun preview(c: Ceiling) = LivePricingEngine.buildPreview(c.source, c.catalog,
        BusinessRoleBindings(c.source.signature, c.roles), "q")

    private fun individual(c: Ceiling, includedKeys: Set<String>? = null): IndividualEstimatePreview {
        var selection = IndividualEstimateSelection("p", c.source.measurementUuid, "q", c.source.signature,
            c.catalog.signature, "", includedKeys)
        val live = LivePricingEngine.buildPreview(c.source, c.catalog, BusinessRoleBindings(c.source.signature, c.roles),
            "q", scopeSignature = selection.scopeSignature)
        selection = selection.copy(expectedPreviewSignature = live.signature)
        return IndividualEstimatePricing.previewSelection(selection, live)
    }
    private fun assertBlocked(p: LiveEstimatePreview) {
        assertFalse(p.canFinalizeFull); assertFalse(p.canRequestPartialAcknowledgement)
        assertTrue(p.mandatoryProblems.isNotEmpty())
    }
    private fun assertBlocked(p: IndividualEstimatePreview) {
        assertFalse(p.canFinalizeFull); assertFalse(p.canRequestPartialAcknowledgement)
        assertTrue(p.mandatoryProblems.isNotEmpty())
    }

    @Test fun emptyAndMissingBasesNeverCreateSyntheticRowsOrReadyIndividualDocuments() {
        for (c in listOf(ceiling(canvas = false, profile = false), ceiling(profile = false), ceiling(canvas = false),
            ceiling(canvas = false, profile = false, extra = true))) {
            assertBlocked(preview(c)); assertBlocked(individual(c))
            assertEquals(c.source.elements.size, preview(c).priceableLines.size)
        }
        assertTrue(preview(ceiling(profile = false)).mandatoryProblems.any { it.code == PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE })
        assertTrue(preview(ceiling(canvas = false)).mandatoryProblems.any { it.code == PricingProblemCode.MISSING_MANDATORY_CANVAS })
    }

    @Test fun confirmedPairAloneAndGenuineZeroPricesAreReady() {
        for (zero in listOf(false, true)) {
            val c = ceiling(zero = zero)
            val live = preview(c); val selected = individual(c)
            assertTrue(live.canFinalizeFull); assertTrue(selected.canFinalizeFull)
            assertFalse(live.canRequestPartialAcknowledgement); assertFalse(selected.canRequestPartialAcknowledgement)
            assertEquals(2, selected.included.count { it.selectedForDocument })
            assertEquals(if (zero) 0 else 2600, selected.selectedIncludedTotalCents)
        }
    }

    @Test fun discussingUnpricedDeletedUnresolvedOrExcludedBaseBlocksPartialToo() {
        for (role in listOf(BusinessRole.CANVAS, BusinessRole.PRIMARY_PROFILE)) {
            val key = "${role.name}:m"
            val original = ceiling(extra = true, optionalUnknown = true)
            val discussing = ceiling(extra = true, potentialBase = role)
            assertBlocked(preview(discussing)); assertBlocked(individual(discussing))
            // Explicit row selection is not an approval-state mutation for a mandatory base.
            assertBlocked(individual(discussing, discussing.source.elements.map { elementKey(it.uuid) }.toSet()))
            val badPrice = original.copy(catalog = original.catalog.copy(items = original.catalog.items.map { if (it.uuid == key) it.copy(salePrice = Double.NaN) else it }))
            val deleted = original.copy(catalog = original.catalog.copy(items = original.catalog.items.filter { it.uuid != key }))
            val drift = original.copy(catalog = original.catalog.copy(items = original.catalog.items.map { if (it.uuid == key) it.copy(unit = "custom") else it }))
            val unavailable = original.copy(source = original.source.copy(elements = original.source.elements.map { e ->
                if (e.uuid == key) e.copy(source = (e.source as ElementSource.CatalogSelection).copy(availability = CatalogAvailability.DELETED_OR_MISSING)) else e
            }))
            for (invalid in listOf(badPrice, deleted, drift, unavailable)) {
                assertBlocked(preview(invalid)); assertBlocked(individual(invalid))
                assertTrue(preview(invalid).mandatoryProblems.any { it.code == if (role == BusinessRole.CANVAS)
                    PricingProblemCode.INVALID_MANDATORY_CANVAS else PricingProblemCode.INVALID_MANDATORY_PRIMARY_PROFILE })
            }
            val valid = ceiling(extra = true)
            assertBlocked(individual(valid, valid.source.elements.filter { it.uuid != key }.map { elementKey(it.uuid) }.toSet()))
        }
        // Valid captured fine geometry, but its area cannot be represented exactly in milli.
        val c = ceiling(extra = true)
        val fine = validateMeasurementCreate(MeasurementCreateDraft(1, MeasurementPrecision.ONE_MILLIMETER, "Fine", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4.001", "width" to "3.001")), "m")).geometry!!
        val unresolvedArea = c.copy(source = c.source.copy(geometry = fine))
        assertBlocked(preview(unresolvedArea)); assertBlocked(individual(unresolvedArea))
    }

    @Test fun optionalOmissionsAllowPartialOnlyAfterEachCeilingHasItsOwnPair() {
        val c = ceiling(extra = true, optionalUnknown = true)
        assertTrue(preview(c).canRequestPartialAcknowledgement)
        assertTrue(individual(c).canRequestPartialAcknowledgement)
        assertFalse(individual(c).canFinalizeFull)
        val missing = ceiling("other", profile = false)
        assertTrue(individual(c).canRequestPartialAcknowledgement)
        assertBlocked(individual(missing))
        assertTrue(individual(missing).mandatoryProblems.any {
            it.sourceKey == "measurement:other" && it.code == PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE })
        val validOther = ceiling("other")
        assertTrue(individual(ceiling()).canFinalizeFull)
        assertTrue(individual(validOther).canFinalizeFull)
    }

    @Test fun onlyPotentialCannotStandInForConfirmedBases() {
        val c = ceiling().let { it.copy(source = it.source.copy(elements = it.source.elements.map { e -> e.copy(approval = ElementApproval.DISCUSSING) })) }
        assertBlocked(preview(c)); val result = individual(c); assertBlocked(result)
        assertEquals(2600, result.potentialTotalCents)
        assertEquals(0, result.selectedIncludedTotalCents)
        assertTrue(result.fatalProblems.any { it.code == PricingProblemCode.EMPTY_DOCUMENT })
    }

    @Test fun individualRowSelectionKeepsPotentialAdvisoryAndOptionalExclusionsExplicit() {
        val c = ceiling(extra = true, potentialBase = BusinessRole.ADDITIONAL_WORK)
        val advisory = individual(c)
        assertTrue(advisory.canFinalizeFull)
        assertEquals(2600, advisory.confirmedTotalCents)
        assertEquals(100, advisory.potentialTotalCents)
        assertEquals(2600, advisory.selectedIncludedTotalCents)
        val explicit = individual(c, c.source.elements.map { elementKey(it.uuid) }.toSet())
        assertTrue(explicit.canFinalizeFull)
        assertEquals(2700, explicit.selectedIncludedTotalCents)
        assertEquals(advisory.potentialTotalCents, explicit.potentialTotalCents)
        assertEquals(ElementApproval.DISCUSSING, c.source.elements.last().approval)

        val approved = ceiling(extra = true)
        val baseKeys = approved.source.elements.take(2).map { elementKey(it.uuid) }.toSet()
        val partial = individual(approved, baseKeys)
        assertFalse(partial.canFinalizeFull)
        assertTrue(partial.canRequestPartialAcknowledgement)
        assertEquals(ExclusionReason.USER_EXCLUDED, partial.excluded.single().reason)
        assertEquals(2600, partial.selectedIncludedTotalCents)
        val invalid = individual(approved, baseKeys + "element:missing")
        assertTrue(invalid.fatalProblems.any { it.code == PricingProblemCode.INVALID_SELECTION })
        assertFalse(invalid.canRequestPartialAcknowledgement)
    }

    @Test fun duplicateOrUndeterminedMainRolesStayBlockedEvenWithTheOtherBasePresent() {
        for (role in listOf(BusinessRole.CANVAS, BusinessRole.PRIMARY_PROFILE)) {
            val c = ceiling()
            val e = c.source.elements.single { c.roles[it.uuid] == role }
            val duplicate = e.copy(uuid = "duplicate", approval = ElementApproval.DISCUSSING)
            val invalid = c.copy(source = c.source.copy(elements = c.source.elements + duplicate), roles = c.roles + (duplicate.uuid to role))
            val p = preview(invalid)
            assertBlocked(p)
            assertTrue(p.fatalProblems.any { it.code == if (role == BusinessRole.CANVAS)
                PricingProblemCode.DUPLICATE_MAIN_CANVAS else PricingProblemCode.DUPLICATE_PRIMARY_PROFILE })
            val undetermined = preview(c.copy(roles = c.roles - e.uuid))
            assertBlocked(undetermined)
            assertTrue(undetermined.fatalProblems.any { it.code == PricingProblemCode.ROLE_UNDETERMINED })
        }
    }

}
