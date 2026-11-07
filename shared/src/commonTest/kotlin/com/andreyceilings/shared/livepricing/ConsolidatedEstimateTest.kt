package com.andreyceilings.shared.livepricing

import kotlin.test.*

class ConsolidatedEstimateTest {
    /** Test fixtures stand in for the future immutable-store verifier; v14 never issues this proof. */
    private fun verified(room: String, revision: String = "r-$room", subtotal: Long = 1234,
        drawing: String = "{\"savedRoom\":\"$room\",\"version\":1}"): FrozenIndividualRevision = certify(FrozenIndividualRevision(
        "p", room, "s-$room", revision,
        FrozenIndividualDocument("Saved $room", "2026-$room", listOf(FrozenEstimateRow("row-$room", "Saved work", "custom saved unit",
            1500, 1, subtotal, "Frozen detail")), FrozenDrawingSnapshot("drawing-v3", drawing), subtotal,
            detailsJson = "{\"partial\":true,\"omitted\":[\"saved omission\"]}"), "source-$room", "catalog-at-save"))

    private fun certify(source: FrozenIndividualRevision) = source.copy(verification = FrozenRevisionVerification(
        source.projectUuid, source.measurementUuid, source.seriesUuid, source.revisionUuid, source.contentSignature))
    private fun ref(source: FrozenIndividualRevision) = FrozenRoomRevisionRef(source.measurementUuid, source.seriesUuid,
        source.revisionUuid, source.contentSignature)
    private fun selection(vararg sources: FrozenIndividualRevision) = ConsolidatedEstimateSelection("p", sources.map { it.measurementUuid }, sources.map(::ref))
    private fun aggregate(selection: ConsolidatedEstimateSelection, vararg sources: FrozenIndividualRevision) =
        ConsolidatedEstimateEngine.buildPreview(selection, sources.associateBy { it.revisionUuid })
    private fun assertBlocked(preview: ConsolidatedEstimatePreview, code: ConsolidatedProblemCode) {
        assertFalse(preview.canCompose)
        assertNull(preview.totalCents)
        assertTrue(preview.problems.any { it.code == code }, preview.problems.toString())
    }

    @Test fun twoSelectedRoomsOfThreeIgnoreTheUnselectedRoomWithoutAnEstimate() {
        val kitchen = verified("kitchen", subtotal = 12001)
        val corridor = verified("corridor", subtotal = 3099)
        val selected = selection(kitchen, corridor)
        val preview = aggregate(selected, corridor, kitchen) // storage order does not select/order rooms
        assertTrue(preview.canCompose)
        assertEquals(15100, preview.totalCents)
        assertEquals("UAH", preview.currency)
        assertEquals(listOf("kitchen", "corridor"), preview.sections.map { it.measurementUuid })
        assertEquals(kitchen.document, preview.sections[0].document)
        assertEquals(corridor.document, preview.sections[1].document)
        assertEquals(preview, aggregate(selected, kitchen, corridor, verified("third").copy(verification = null)))
        assertFalse(preview.selectedMeasurementUuids.contains("third"))
    }

    @Test fun emptyOrSelectedMissingRevisionBlocksButUnselectedMissingNeverGates() {
        val a = verified("a")
        assertBlocked(aggregate(ConsolidatedEstimateSelection("p", emptyList(), emptyList())), ConsolidatedProblemCode.EMPTY_SELECTION)
        assertBlocked(aggregate(selection(a)), ConsolidatedProblemCode.REVISION_UNAVAILABLE)
        assertBlocked(aggregate(selection(a).copy(selectedMeasurementUuids = listOf("a", "missing")), a), ConsolidatedProblemCode.MISSING_ROOM_REVISION)
        assertTrue(aggregate(selection(a), a).canCompose)
        assertBlocked(aggregate(selection(a).copy(selectedMeasurementUuids = emptyList()), a), ConsolidatedProblemCode.UNSELECTED_ROOM)
    }

    @Test fun exactRevisionOwnershipIndividualProvenanceAndIntegrityAreRequired() {
        val a = verified("a")
        val selected = selection(a)
        assertBlocked(aggregate(selected.copy(projectUuid = "other"), a), ConsolidatedProblemCode.CROSS_PROJECT)
        assertBlocked(aggregate(selected, a.copy(deleted = true)), ConsolidatedProblemCode.REVISION_UNAVAILABLE)
        assertBlocked(aggregate(selected, a.copy(verification = null)), ConsolidatedProblemCode.PROVENANCE_MISSING)
        assertBlocked(aggregate(selected, a.copy(isIndividualLeaf = false)), ConsolidatedProblemCode.NOT_INDIVIDUAL_REVISION)
        val refs = listOf(ref(a).copy(seriesUuid = "wrong"))
        assertBlocked(aggregate(selected.copy(revisions = refs), a), ConsolidatedProblemCode.STALE_REVISION)
        assertBlocked(aggregate(selected.copy(revisions = listOf(ref(a).copy(expectedContentSignature = "stale"))), a), ConsolidatedProblemCode.STALE_REVISION)
        val changed = a.copy(document = a.document.copy(subtotalCents = 9999))
        assertBlocked(aggregate(selected, changed), ConsolidatedProblemCode.STALE_REVISION)
        // Replacing the requested fingerprint alone cannot repair a mutated immutable record's old verifier receipt.
        assertBlocked(aggregate(selection(changed), changed), ConsolidatedProblemCode.PROVENANCE_MISSING)
        val other = verified("other")
        assertBlocked(aggregate(selected, a.copy(verification = other.verification)), ConsolidatedProblemCode.PROVENANCE_MISSING)
    }

    @Test fun duplicateRevisionOrRoomAndTwoDocumentsOfOneRoomCannotCoverAnotherRoom() {
        val a = verified("a")
        val a2 = verified("a", "alternative-a")
        assertBlocked(aggregate(selection(a, a), a), ConsolidatedProblemCode.DUPLICATE_REVISION)
        assertBlocked(aggregate(selection(a, a2), a, a2), ConsolidatedProblemCode.DUPLICATE_ROOM)
        val ambiguous = ConsolidatedEstimateSelection("p", listOf("a", "b"), listOf(ref(a), ref(a2)))
        val preview = aggregate(ambiguous, a, a2)
        assertBlocked(preview, ConsolidatedProblemCode.AMBIGUOUS_ROOM_REVISION)
        assertTrue(preview.problems.any { it.code == ConsolidatedProblemCode.MISSING_ROOM_REVISION && it.measurementUuid == "b" })
        val sameRevisionDifferentRooms = selection(a).copy(selectedMeasurementUuids = listOf("a", "b"), revisions = listOf(ref(a), ref(a).copy(measurementUuid = "b")))
        assertBlocked(aggregate(sameRevisionDifferentRooms, a), ConsolidatedProblemCode.DUPLICATE_REVISION)
    }

    @Test fun choosingAnotherFrozenRevisionChangesOnlyThatRoomNeverSilentlyLatest() {
        val old = verified("a", "old-a", 120)
        val newer = verified("a", "new-a", 456, "{\"savedDrawing\":\"new\"}")
        val other = verified("b", subtotal = 70)
        val before = aggregate(selection(old, other), old, newer, other)
        val after = aggregate(selection(newer, other), old, newer, other)
        assertEquals(190, before.totalCents)
        assertEquals(526, after.totalCents)
        assertEquals("old-a", before.sections[0].revisionUuid)
        assertNotEquals(before.sections[0].document.drawing, after.sections[0].document.drawing)
        assertEquals(before.sections[1], after.sections[1])
        assertEquals(before, aggregate(selection(old, other), newer, old, other))
    }

    @Test fun mutableMeasurementAndCatalogOnlyProduceSeparateSelectedRoomWarnings() {
        val a = verified("a", subtotal = 1)
        val b = verified("b", subtotal = 2)
        val before = aggregate(selection(a, b), a, b)
        val unchanged = CurrentMeasurementPricingBasis("p", "b", b.sourceSignatureAtSave, b.catalogSignatureAtSave)
        val current = mapOf("a" to CurrentMeasurementPricingBasis("p", "a", "edited-measurement-and-drawing", "edited-price-catalog"),
            "b" to unchanged, "unselected" to CurrentMeasurementPricingBasis("p", "unselected", "changed", "changed"))
        val warnings = frozenSourceWarnings(before, current)
        assertEquals(listOf(FrozenSourceWarning("a", a.revisionUuid, listOf(FrozenSourceChange.MEASUREMENT_CHANGED, FrozenSourceChange.CATALOG_CHANGED))), warnings)
        assertEquals(before, aggregate(selection(a, b), a, b))
        assertTrue(before.canCompose) // Printing the chosen old revisions is still allowed.
        assertEquals(3, before.totalCents)
        assertEquals(a.document.drawing.payload, before.sections[0].document.drawing.payload)
        val unavailable = frozenSourceWarnings(before, mapOf("b" to unchanged))
        assertEquals(listOf(FrozenSourceChange.CURRENT_BASIS_UNAVAILABLE), unavailable.single().changes)
        assertEquals("a", unavailable.single().measurementUuid)
    }

    @Test fun sumsStoredSubtotalsExactlyWithoutUnitQuantityGeometryOrMandatoryRoleRevalidation() {
        val historical = verified("old-policy", subtotal = 777)
        // Stored row money deliberately differs from today's unit-price * quantity formula.
        // The document has custom units and no live CANVAS/PRIMARY_PROFILE classifications.
        assertEquals(777, historical.document.rows.single().totalCents)
        val preview = aggregate(selection(historical), historical)
        assertTrue(preview.canCompose)
        assertEquals(777, preview.totalCents)
        assertEquals(historical.document, preview.sections.single().document)
        assertEquals(historical.document.detailsJson, preview.sections.single().document.detailsJson)
        val zero = verified("zero", subtotal = 0)
        assertTrue(aggregate(selection(zero), zero).canCompose)
        assertEquals(0, aggregate(selection(zero), zero).totalCents)
        val huge = verified("huge", subtotal = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, aggregate(selection(huge), huge).totalCents)
        assertBlocked(aggregate(selection(huge, historical), huge, historical), ConsolidatedProblemCode.OVERFLOW)
    }

    @Test fun missingFrozenDrawingInvalidTotalAndMixedCurrenciesFailClosed() {
        val a = verified("a")
        val missing = certify(a.copy(document = a.document.copy(drawing = FrozenDrawingSnapshot("v3", ""))))
        assertBlocked(aggregate(selection(missing), missing), ConsolidatedProblemCode.FROZEN_SNAPSHOT_UNAVAILABLE)
        val negative = certify(a.copy(document = a.document.copy(subtotalCents = -1)))
        assertBlocked(aggregate(selection(negative), negative), ConsolidatedProblemCode.INVALID_FROZEN_TOTAL)
        val eur = verified("b").let { certify(it.copy(document = it.document.copy(currency = "EUR"))) }
        assertBlocked(aggregate(selection(a, eur), a, eur), ConsolidatedProblemCode.CURRENCY_MISMATCH)
        assertEquals("EUR", aggregate(selection(eur), eur).currency)
    }

    @Test fun outputKeepsTheSelectedFrozenSnapshotDetachedFromMutableBuilderLists() {
        val a = verified("a")
        val rows = a.document.rows.toMutableList()
        val source = certify(a.copy(document = a.document.copy(rows = rows)))
        val preview = aggregate(selection(source), source)
        rows.clear()
        assertEquals(1, preview.sections.single().document.rows.size)
        assertEquals(1234, preview.totalCents)
        assertBlocked(aggregate(selection(a), source), ConsolidatedProblemCode.STALE_REVISION)
    }
}
