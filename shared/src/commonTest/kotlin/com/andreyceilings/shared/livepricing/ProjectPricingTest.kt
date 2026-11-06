package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

class ProjectPricingTest {
    private val item = ActiveCatalogItem("i", "g", "work", "work", "шт.", QuantityMode.MANUAL, PlacementKind.NONE, 1.0, 1, 1)
    private val catalog = ActiveCatalogSnapshot(listOf(item))
    private fun source(id: Int): LiveEstimateSource {
        val geometry = validateMeasurementCreate(MeasurementCreateDraft(1, MeasurementPrecision.ONE_MILLIMETER, "Room", "",
            MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")), "m$id")).geometry!!
        val e = MeasurementElement("e$id", "m$id", ElementSource.CatalogSelection("i", "work", QuantityMode.MANUAL, PlacementKind.NONE, CatalogAvailability.ACTIVE),
            "work", "", "шт.", ElementApproval.APPROVED, ElementQuantity.Exact(1000L * id), ElementPlacement.UNPLACED, 0, 1, 1, null)
        return LiveEstimateSource("p", "m$id", geometry, listOf(e, e.copy(uuid = "potential$id", approval = ElementApproval.DISCUSSING)))
    }
    private fun setup(ids: List<Int>, subset: Set<String>? = null, priceCatalog: ActiveCatalogSnapshot = catalog): Pair<ProjectEstimateSelection, Map<String, LiveEstimatePreview>> {
        var selection = ProjectEstimateSelection("p", "q", ids.map { ProjectSourceSelection.Live("s$it", "", subset) })
        val previews = ids.associate { id ->
            val s = source(id)
            "s$id" to LivePricingEngine.buildPreview(s, priceCatalog, BusinessRoleBindings(s.signature, s.elements.associate { it.uuid to BusinessRole.ADDITIONAL_WORK }),
                "q", scopeSignature = selection.scopeSignature)
        }
        selection = selection.copy(sources = selection.sources.map { (it as ProjectSourceSelection.Live).copy(expectedSignature = previews.getValue(it.sourceId).signature) })
        return selection to previews
    }

    @Test fun explicitOneFourAllAndUnselectedSourcesDoNotAffectTotals() {
        for (ids in listOf(listOf(1), listOf(1, 2, 3, 4), (1..8).toList(), listOf(2, 7))) {
            val (selection, previews) = setup(ids)
            val aggregate = ProjectPricingEngine.previewComposition(selection, previews + ("unselected" to setup(listOf(99)).second.getValue("s99")))
            assertEquals(emptyList(), aggregate.fatalProblems)
            assertEquals(ids.sum() * 100L, aggregate.confirmedTotalCents)
            assertEquals(aggregate.confirmedTotalCents, aggregate.potentialTotalCents)
            assertEquals(aggregate.confirmedTotalCents, aggregate.selectedIncludedTotalCents) // potential is not selected by default
            assertEquals(aggregate.confirmedTotalCents, aggregate.included.filter { it.line.bucket == PricingBucket.CONFIRMED }.sumOf { it.line.totalCents })
            assertEquals(aggregate, ProjectPricingEngine.previewComposition(selection, previews))
            assertEquals(ids.map { "s$it" }, aggregate.included.map { it.sourceId }.distinct())
        }
    }

    @Test fun explicitRowExclusionPartialAndScopeToken() {
        val (selection, previews) = setup(listOf(1), setOf("element:potential1"))
        val result = ProjectPricingEngine.previewComposition(selection, previews)
        assertTrue(result.isPartial)
        assertFalse(result.canFinalizeFull)
        assertEquals(0, result.confirmedTotalCents)
        assertEquals(100, result.potentialTotalCents)
        assertEquals(100, result.selectedIncludedTotalCents) // explicit potential selection, still a separate bucket
        assertTrue(result.canRequestPartialAcknowledgement)
        assertEquals(ExclusionReason.USER_EXCLUDED, result.excluded.single().reason)
        val changed = selection.copy(sources = listOf((selection.sources.single() as ProjectSourceSelection.Live).copy(includedSourceKeys = null)))
        assertNotEquals(selection.scopeSignature, changed.scopeSignature)
        assertFalse(ProjectPricingEngine.previewComposition(changed, previews).canFinalizeFull)
        assertFalse(ProjectPricingEngine.previewComposition(selection.copy(projectUuid = "other"), previews).canFinalizeFull)
        assertFalse(ProjectPricingEngine.previewComposition(selection, emptyMap()).canFinalizeFull)
    }

    @Test fun projectWorkAddedExactlyOnceAndPriceChangesInvalidateIt() {
        val initial = setup(listOf(1)).first
        val work = ProjectWideWork("delivery", "p", "i", "work", "шт.", 2000, PricingBucket.CONFIRMED, catalog.signature)
        val selection = initial.copy(projectWorks = listOf(work))
        val s = source(1)
        val p = LivePricingEngine.buildPreview(s, catalog, BusinessRoleBindings(s.signature, s.elements.associate { it.uuid to BusinessRole.ADDITIONAL_WORK }), "q", scopeSignature = selection.scopeSignature)
        val selected = selection.copy(sources = listOf(ProjectSourceSelection.Live("s1", p.signature)))
        val result = ProjectPricingEngine.previewComposition(selected, mapOf("s1" to p), catalog = catalog)
        assertEquals(emptyList(), result.fatalProblems)
        assertEquals(300, result.confirmedTotalCents)
        val duplicate = ProjectPricingEngine.previewComposition(selected.copy(projectWorks = listOf(work, work)), mapOf("s1" to p), catalog = catalog)
        assertTrue(duplicate.fatalProblems.any { it.code == PricingProblemCode.DUPLICATE_SOURCE })
        assertFalse(ProjectPricingEngine.previewComposition(selected, mapOf("s1" to p), catalog = catalog.copy(items = listOf(item.copy(salePrice = 2.0)))).canFinalizeFull)
    }

    @Test fun frozenExactRevisionSubsetDoesNotRepriceAndRejectsMissingCrossProjectAndCoverage() {
        val line = setup(listOf(1)).second.getValue("s1").priceableLines.first()
        val revisions = (1..6).associate { id -> "r$id" to VerifiedFrozenRevision("p", "series$id", "r$id",
            listOf(line.copy(measurementUuid = "m$id", sourceKey = "element:e$id", elementUuid = "e$id", totalCents = 100)), "frozen$id", true, true) }
        val selection = ProjectEstimateSelection("p", "q", listOf(2, 5).map { ProjectSourceSelection.Frozen("f$it", "series$it", "r$it", "frozen$it") })
        val result = ProjectPricingEngine.previewComposition(selection, emptyMap(), revisions, catalog.copy(items = listOf(item.copy(salePrice = 999.0))))
        assertTrue(result.canFinalizeFull)
        assertEquals(200, result.confirmedTotalCents)
        assertEquals(listOf("f2", "f5"), result.included.map { it.sourceId })
        assertFalse(ProjectPricingEngine.previewComposition(selection, emptyMap(), emptyMap()).canFinalizeFull)
        for (changed in listOf(revisions.getValue("r2").copy(projectUuid = "other"), revisions.getValue("r2").copy(isLeaf = false),
            revisions.getValue("r2").copy(provenanceVerified = false), revisions.getValue("r2").copy(seriesUuid = "wrong"))) {
            val p = ProjectPricingEngine.previewComposition(selection, emptyMap(), revisions + ("r2" to changed))
            assertFalse(p.canFinalizeFull)
            assertFalse(p.canRequestPartialAcknowledgement)
        }
        val duplicated = selection.copy(sources = selection.sources + ProjectSourceSelection.Frozen("another-id", "series2", "r2", "frozen2"))
        assertTrue(ProjectPricingEngine.previewComposition(duplicated, emptyMap(), revisions).fatalProblems.any { it.code == PricingProblemCode.DUPLICATE_SOURCE })
        val overlap = revisions + ("r5" to revisions.getValue("r5").copy(lines = revisions.getValue("r2").lines))
        assertTrue(ProjectPricingEngine.previewComposition(selection, emptyMap(), overlap).fatalProblems.any { it.code == PricingProblemCode.PROVENANCE_OVERLAP })
        // Same ceiling, different explicit element identity is allowed.
        val sameCeiling = revisions + ("r5" to revisions.getValue("r5").copy(lines = listOf(line.copy(sourceKey = "element:other", elementUuid = "other", measurementUuid = "m2"))))
        assertTrue(ProjectPricingEngine.previewComposition(selection, emptyMap(), sameCeiling).canFinalizeFull)
    }

    @Test fun staleSourceSelectionCannotReuseOldReadinessOrTokens() {
        val (selection, previews) = setup(listOf(1))
        val changed = setup(listOf(1), priceCatalog = catalog.copy(items = listOf(item.copy(salePrice = 2.0))))
        assertFalse(ProjectPricingEngine.previewComposition(selection, changed.second).canFinalizeFull)
        assertNotEquals(ProjectPricingEngine.previewComposition(selection, previews).signature,
            ProjectPricingEngine.previewComposition(changed.first, changed.second).signature)
        assertFalse(ProjectPricingEngine.previewComposition(selection.copy(sources = selection.sources + selection.sources.single()), previews).canFinalizeFull)
    }
    @Test fun mixedFrozenAndLiveKeepExplicitOriginsAndRejectMixedLiveCatalogs() {
        val (liveSelection, previews) = setup(listOf(1, 2))
        val frozen = VerifiedFrozenRevision("p", "series", "revision", listOf(previews.getValue("s1").priceableLines.first().copy(
            measurementUuid = "history", elementUuid = "history", sourceKey = "element:history")), "frozen", true, true)
        val selection = liveSelection.copy(sources = liveSelection.sources + ProjectSourceSelection.Frozen("old", "series", "revision", "frozen"))
        val repriced = selection.sources.filterIsInstance<ProjectSourceSelection.Live>().associate { selected ->
            val id = selected.sourceId.removePrefix("s").toInt()
            val source = source(id)
            selected.sourceId to LivePricingEngine.buildPreview(source, catalog,
                BusinessRoleBindings(source.signature, source.elements.associate { it.uuid to BusinessRole.ADDITIONAL_WORK }),
                "q", scopeSignature = selection.scopeSignature)
        }
        val refs = selection.copy(sources = selection.sources.map { if (it is ProjectSourceSelection.Live) it.copy(expectedSignature = repriced.getValue(it.sourceId).signature) else it })
        val result = ProjectPricingEngine.previewComposition(refs, repriced, mapOf("revision" to frozen), catalog)
        assertTrue(result.canFinalizeFull)
        assertEquals(400, result.confirmedTotalCents)
        assertEquals(PricingLineOrigin.Frozen("series", "revision"), result.included.last().origin)
        val current = catalog.copy(items = listOf(item.copy(salePrice = 2.0)))
        assertFalse(ProjectPricingEngine.previewComposition(refs, repriced, mapOf("revision" to frozen), current).canFinalizeFull)
        val second = source(2)
        val newPreview = LivePricingEngine.buildPreview(second, current,
            BusinessRoleBindings(second.signature, second.elements.associate { it.uuid to BusinessRole.ADDITIONAL_WORK }), "q", scopeSignature = refs.scopeSignature)
        val mixed = repriced + ("s2" to newPreview)
        val mixedRefs = refs.copy(sources = refs.sources.map { if (it is ProjectSourceSelection.Live && it.sourceId == "s2") it.copy(expectedSignature = newPreview.signature) else it })
        assertTrue(ProjectPricingEngine.previewComposition(mixedRefs, mixed, mapOf("revision" to frozen)).fatalProblems.any { it.code == PricingProblemCode.STALE_CATALOG })
    }

    @Test fun aggregateOverflowIsFatalEvenForOtherwiseValidFrozenRows() {
        val base = setup(listOf(1)).second.getValue("s1").priceableLines.first()
        val revisions = (1..2).associate { id -> "r$id" to VerifiedFrozenRevision("p", "s$id", "r$id",
            listOf(base.copy(sourceKey = "element:large$id", elementUuid = "large$id", sourceQuantityMilli = 9_000_000_000_000_000L,
                effectiveBillableQuantityMilli = 9_000_000_000_000_000L, unitPriceCents = 1_000_000L, totalCents = 9_000_000_000_000_000_000L)),
            "token$id", true, true) }
        val selection = ProjectEstimateSelection("p", "q", (1..2).map { ProjectSourceSelection.Frozen("f$it", "s$it", "r$it", "token$it") })
        val preview = ProjectPricingEngine.previewComposition(selection, emptyMap(), revisions)
        assertTrue(preview.fatalProblems.any { it.code == PricingProblemCode.OVERFLOW })
        assertFalse(preview.canFinalizeFull)
        assertFalse(preview.canRequestPartialAcknowledgement)
    }

    @Test fun corruptedFrozenMoneyIsTypedFatalRatherThanAnExceptionOrZeroPrice() {
        val base = setup(listOf(1)).second.getValue("s1").priceableLines.first()
        val selection = ProjectEstimateSelection("p", "q", listOf(ProjectSourceSelection.Frozen("f", "s", "r", "token")))
        for (line in listOf(base.copy(totalCents = -1), base.copy(unitPriceCents = -1), base.copy(effectiveBillableQuantityMilli = 0),
            base.copy(sourceKey = "ambiguous"))) {
            val source = VerifiedFrozenRevision("p", "s", "r", listOf(line), "token", true, true)
            val p = ProjectPricingEngine.previewComposition(selection, emptyMap(), mapOf("r" to source))
            assertFalse(p.canFinalizeFull)
            assertFalse(p.canRequestPartialAcknowledgement)
            assertTrue(p.fatalProblems.isNotEmpty())
            assertTrue(p.included.isEmpty())
        }
    }

}
