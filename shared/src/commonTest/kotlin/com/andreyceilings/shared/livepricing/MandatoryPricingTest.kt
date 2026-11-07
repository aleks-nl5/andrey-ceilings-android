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

    private fun compose(ceilings: List<Ceiling>, includedKeys: Map<String, Set<String>> = emptyMap(),
        frozen: List<VerifiedFrozenRevision> = emptyList(), frozenFirst: Boolean = false): ProjectPricingPreview {
        val catalog = ActiveCatalogSnapshot(ceilings.flatMap { it.catalog.items }.distinctBy { it.uuid })
        val liveRefs = ceilings.map { c -> ProjectSourceSelection.Live(c.source.measurementUuid, "", c.source.measurementUuid,
            c.source.signature, catalog.signature, includedKeys[c.source.measurementUuid]) }
        val frozenRefs = frozen.map { ProjectSourceSelection.Frozen("f:${it.revisionUuid}", it.seriesUuid, it.revisionUuid, it.contentSignature) }
        var selection = ProjectEstimateSelection("p", "q", if (frozenFirst) frozenRefs + liveRefs else liveRefs + frozenRefs)
        val previews = ceilings.associate { c -> c.source.measurementUuid to LivePricingEngine.buildPreview(c.source, catalog,
            BusinessRoleBindings(c.source.signature, c.roles), "q", scopeSignature = selection.scopeSignature) }
        selection = selection.copy(sources = selection.sources.map { if (it is ProjectSourceSelection.Live)
            it.copy(expectedSignature = previews.getValue(it.sourceId).signature) else it })
        return ProjectPricingEngine.previewComposition(selection, previews, frozen.associateBy { it.revisionUuid }, catalog)
    }
    private fun assertBlocked(p: LiveEstimatePreview) {
        assertFalse(p.canFinalizeFull); assertFalse(p.canRequestPartialAcknowledgement)
        assertTrue(p.mandatoryProblems.isNotEmpty())
    }
    private fun assertBlocked(p: ProjectPricingPreview) {
        assertFalse(p.canFinalizeFull); assertFalse(p.canRequestPartialAcknowledgement)
        assertTrue(p.mandatoryProblems.isNotEmpty())
    }

    @Test fun emptyAndMissingBasesNeverCreateSyntheticRowsOrReadyDocuments() {
        for (c in listOf(ceiling(canvas = false, profile = false), ceiling(profile = false), ceiling(canvas = false),
            ceiling(canvas = false, profile = false, extra = true))) {
            assertBlocked(preview(c)); assertBlocked(compose(listOf(c)))
            assertEquals(c.source.elements.size, preview(c).priceableLines.size)
        }
        assertBlocked(compose(emptyList()))
        val cat = ActiveCatalogSnapshot(listOf(ActiveCatalogItem("delivery", "g", "delivery", "Delivery", "послуга",
            QuantityMode.MANUAL, PlacementKind.NONE, 1.0, 1, 1)))
        val work = ProjectWideWork("delivery", "p", "delivery", "delivery", "послуга", 1000,
            PricingBucket.CONFIRMED, cat.signature)
        val projectOnly = ProjectPricingEngine.previewComposition(ProjectEstimateSelection("p", "q", emptyList(), listOf(work)), emptyMap(), catalog = cat)
        assertEquals(100, projectOnly.selectedIncludedTotalCents)
        assertEquals(1, projectOnly.included.size)
        assertBlocked(projectOnly)
        assertTrue(preview(ceiling(profile = false)).mandatoryProblems.any { it.code == PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE })
        assertTrue(preview(ceiling(canvas = false)).mandatoryProblems.any { it.code == PricingProblemCode.MISSING_MANDATORY_CANVAS })
    }

    @Test fun confirmedPairAloneAndGenuineZeroPricesAreReady() {
        for (zero in listOf(false, true)) {
            val c = ceiling(zero = zero)
            val live = preview(c); val selected = compose(listOf(c))
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
            assertBlocked(preview(discussing)); assertBlocked(compose(listOf(discussing)))
            // Explicit row selection is not an approval-state mutation for a mandatory base.
            assertBlocked(compose(listOf(discussing), mapOf("m" to discussing.source.elements.map { elementKey(it.uuid) }.toSet())))
            val badPrice = original.copy(catalog = original.catalog.copy(items = original.catalog.items.map { if (it.uuid == key) it.copy(salePrice = Double.NaN) else it }))
            val deleted = original.copy(catalog = original.catalog.copy(items = original.catalog.items.filter { it.uuid != key }))
            val drift = original.copy(catalog = original.catalog.copy(items = original.catalog.items.map { if (it.uuid == key) it.copy(unit = "custom") else it }))
            val unavailable = original.copy(source = original.source.copy(elements = original.source.elements.map { e ->
                if (e.uuid == key) e.copy(source = (e.source as ElementSource.CatalogSelection).copy(availability = CatalogAvailability.DELETED_OR_MISSING)) else e
            }))
            for (invalid in listOf(badPrice, deleted, drift, unavailable)) {
                assertBlocked(preview(invalid)); assertBlocked(compose(listOf(invalid)))
                assertTrue(preview(invalid).mandatoryProblems.any { it.code == if (role == BusinessRole.CANVAS)
                    PricingProblemCode.INVALID_MANDATORY_CANVAS else PricingProblemCode.INVALID_MANDATORY_PRIMARY_PROFILE })
            }
            val valid = ceiling(extra = true)
            assertBlocked(compose(listOf(valid), mapOf("m" to valid.source.elements.filter { it.uuid != key }.map { elementKey(it.uuid) }.toSet())))
        }
        // Valid captured fine geometry, but its area cannot be represented exactly in milli.
        val c = ceiling(extra = true)
        val fine = validateMeasurementCreate(MeasurementCreateDraft(1, MeasurementPrecision.ONE_MILLIMETER, "Fine", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4.001", "width" to "3.001")), "m")).geometry!!
        val unresolvedArea = c.copy(source = c.source.copy(geometry = fine))
        assertBlocked(preview(unresolvedArea)); assertBlocked(compose(listOf(unresolvedArea)))
    }

    @Test fun optionalOmissionsAllowPartialOnlyAfterEachCeilingHasItsOwnPair() {
        val c = ceiling(extra = true, optionalUnknown = true)
        assertTrue(preview(c).canRequestPartialAcknowledgement)
        assertTrue(compose(listOf(c)).canRequestPartialAcknowledgement)
        assertFalse(compose(listOf(c)).canFinalizeFull)
        val missing = ceiling("other", profile = false)
        assertBlocked(compose(listOf(c, missing)))
        assertTrue(compose(listOf(c, missing)).mandatoryProblems.any {
            it.sourceKey == "measurement:other" && it.code == PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE })
        val validOther = ceiling("other")
        assertTrue(compose(listOf(ceiling(), validOther)).canFinalizeFull)
    }

    @Test fun onlyPotentialOrProjectWorkCannotStandInForConfirmedBases() {
        val c = ceiling().let { it.copy(source = it.source.copy(elements = it.source.elements.map { e -> e.copy(approval = ElementApproval.DISCUSSING) })) }
        assertBlocked(preview(c)); val result = compose(listOf(c)); assertBlocked(result)
        assertEquals(2600, result.potentialTotalCents)
        assertEquals(0, result.selectedIncludedTotalCents)
        assertTrue(result.mandatoryProblems.any { it.code == PricingProblemCode.EMPTY_DOCUMENT })
        val onlyWork = ProjectWideWork("delivery", "p", "delivery", "delivery", "послуга", 1000,
            PricingBucket.CONFIRMED, ActiveCatalogSnapshot(emptyList()).signature)
        assertBlocked(ProjectPricingEngine.previewComposition(ProjectEstimateSelection("p", "q", emptyList(), listOf(onlyWork)), emptyMap()))
    }

    @Test fun frozenNeedsProvenRolesAndKeepsFrozenMoney() {
        val lines = preview(ceiling(zero = true)).priceableLines
        val frozen = VerifiedFrozenRevision("p", "series", "revision", lines, "immutable", true, true)
        assertTrue(compose(emptyList(), frozen = listOf(frozen)).canFinalizeFull)
        assertBlocked(compose(emptyList(), frozen = listOf(frozen.copy(lines = lines.filter { it.businessRole == BusinessRole.CANVAS }))))
        val legacy = compose(emptyList(), frozen = listOf(frozen.copy(lines = lines.map { it.copy(businessRole = null) })))
        assertBlocked(legacy)
        val wrongUnit = frozen.copy(lines = lines.map { if (it.businessRole == BusinessRole.CANVAS) it.copy(unit = "шт.") else it })
        assertBlocked(compose(emptyList(), frozen = listOf(wrongUnit)))
        val wrongOrigin = frozen.copy(lines = lines.map { it.copy(quantityOrigin = QuantityOrigin.DECLARED_EXACT) })
        assertBlocked(compose(emptyList(), frozen = listOf(wrongOrigin)))
        assertTrue(legacy.fatalProblems.any { it.code == PricingProblemCode.PROVENANCE_MISSING })
    }

    @Test fun advisoryPotentialAndFrozenSameProvenanceAreNotDuplicateDocumentCharges() {
        val c = ceiling(extra = true).let { c -> c.copy(source = c.source.copy(elements = c.source.elements.map {
            if (c.roles[it.uuid] == BusinessRole.ADDITIONAL_WORK) it.copy(approval = ElementApproval.DISCUSSING) else it
        })) }
        val offered = preview(c).priceableLines.single { it.businessRole == BusinessRole.ADDITIONAL_WORK }
        val frozen = VerifiedFrozenRevision("p", "series", "revision", listOf(offered.copy(bucket = PricingBucket.CONFIRMED,
            unitPriceCents = 987, totalCents = 987)), "immutable", true, true)
        for (first in listOf(false, true)) {
            val p = compose(listOf(c), frozen = listOf(frozen), frozenFirst = first)
            assertEquals(emptyList(), p.fatalProblems)
            assertTrue(p.canFinalizeFull)
            assertEquals(3587, p.selectedIncludedTotalCents)
            assertEquals(100, p.potentialTotalCents)
            assertEquals(1, p.included.count { it.line.sourceKey == offered.sourceKey && it.selectedForDocument })
            assertEquals(1, p.included.count { it.line.sourceKey == offered.sourceKey && !it.selectedForDocument })
            val unknown = c.copy(source = c.source.copy(elements = c.source.elements.map {
                if (it.uuid == offered.elementUuid) it.copy(quantity = ElementQuantity.Unknown) else it
            }))
            val unresolved = compose(listOf(unknown), frozen = listOf(frozen), frozenFirst = first)
            assertTrue(unresolved.canFinalizeFull)
            assertTrue(unresolved.potentialIncomplete)
            assertEquals(3587, unresolved.selectedIncludedTotalCents)
            assertFalse(unresolved.fatalProblems.any { it.code == PricingProblemCode.PROVENANCE_OVERLAP })
            val explicit = compose(listOf(c), mapOf("m" to c.source.elements.map { elementKey(it.uuid) }.toSet()), listOf(frozen), first)
            assertTrue(explicit.fatalProblems.any { it.code == PricingProblemCode.PROVENANCE_OVERLAP })
            assertFalse(explicit.canFinalizeFull); assertFalse(explicit.canRequestPartialAcknowledgement)
        }
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
