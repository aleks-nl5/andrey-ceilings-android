package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

class LivePricingTest {
    private fun geometry(length: Long = 4000, width: Long = 3000, precision: Double = 0.001) =
        FrozenPolygonGeometryContract.capture("rectangle", CeilingShapeTemplates.template("rectangle")!!.build(mapOf("length" to length, "width" to width)).vertices,
            mapOf("length" to length, "width" to width), precision)

    private fun item(id: String, unit: String = "шт.", mode: QuantityMode = QuantityMode.MANUAL,
        placement: PlacementKind = PlacementKind.NONE, price: Double = 10.0) =
        ActiveCatalogItem(id, "group", "code.$id", id, unit, mode, placement, price, 1, 1)

    private fun element(item: ActiveCatalogItem, quantity: ElementQuantity = ElementQuantity.Exact(1000),
        id: String = item.uuid, approval: ElementApproval = ElementApproval.APPROVED) = MeasurementElement(id, "m",
        ElementSource.CatalogSelection(item.uuid, item.code, item.quantityMode, item.placementKind, CatalogAvailability.ACTIVE),
        id, "", item.unit, approval, quantity, ElementPlacement.UNPLACED, 0, 1, 1, null)

    private fun roles(source: LiveEstimateSource, mapping: Map<String, BusinessRole> = source.elements.filter { it.source is ElementSource.CatalogSelection }
        .associate { it.uuid to BusinessRole.ADDITIONAL_WORK }) = BusinessRoleBindings(runCatching { source.signature }.getOrDefault("invalid-geometry"), mapping)
    private fun preview(source: LiveEstimateSource, catalog: ActiveCatalogSnapshot, mapping: Map<String, BusinessRole>? = null,
        composition: String = "quote", overrides: List<EstimateQuantityOverride> = emptyList()) =
        LivePricingEngine.buildPreview(source, catalog, roles(source, mapping ?: roles(source).roles), composition, overrides)

    @Test fun rectangleAllElementsExactlyOnceSeparateBucketsNoSyntheticLines() {
        val canvas = item("canvas", "м²", QuantityMode.CEILING_AREA, price = 290.0)
        val profile = item("profile", "м.п.", QuantityMode.CEILING_PERIMETER, price = 50.0)
        val lights = item("lights", placement = PlacementKind.POINT)
        val extra = item("extra", "м.п.", QuantityMode.ENTERED_LENGTH, PlacementKind.LINE)
        val area = item("area", "м²", placement = PlacementKind.AREA)
        val items = listOf(canvas, profile, lights, extra, area)
        val source = LiveEstimateSource("p", "m", geometry(), listOf(element(canvas, ElementQuantity.CeilingArea),
            element(profile, ElementQuantity.CeilingPerimeter), element(lights, ElementQuantity.Exact(3000)),
            element(extra, ElementQuantity.Exact(1234)), element(area, ElementQuantity.Exact(2500), approval = ElementApproval.DISCUSSING)),
            points = listOf(PointPlacement("point", "lights", 1000, 1000, 1, 1, 1, null)),
            lines = listOf(LinePlacement("line", "extra", PointPositionMm(1000, 1000), PointPositionMm(2000, 1000), 1, 1, 1)),
            areas = listOf(AreaFeature("area1", "m", AreaShape.rectangle(PointPositionMm(500, 500), PointPositionMm(1000, 1000)), 1, 1, 1, "area"),
                AreaFeature("void", "m", AreaShape.Circle(PointPositionMm(2000, 2000), 100), 1, 1, 1, role = AreaRole.VOID_MARKER)))
        val mapping = roles(source).roles + mapOf("canvas" to BusinessRole.CANVAS, "profile" to BusinessRole.PRIMARY_PROFILE)
        val result = preview(source, ActiveCatalogSnapshot(items), mapping)
        assertEquals(emptyList(), result.fatalProblems)
        assertEquals(5, result.priceableLines.size)
        assertEquals(12000, result.priceableLines.single { it.elementUuid == "canvas" }.sourceQuantityMilli)
        assertEquals(14000, result.priceableLines.single { it.elementUuid == "profile" }.sourceQuantityMilli)
        assertEquals(1234, result.priceableLines.single { it.elementUuid == "extra" }.sourceQuantityMilli)
        assertEquals(422234, result.confirmedTotalCents)
        assertEquals(2500, result.potentialTotalCents)
        assertTrue(result.canFinalizeFull)
        assertEquals(result, preview(source.copy(elements = source.elements.reversed(), points = source.points.reversed()), ActiveCatalogSnapshot(items.reversed()), mapping))
        val empty = preview(source.copy(elements = emptyList(), points = emptyList(), lines = emptyList(), areas = emptyList()), ActiveCatalogSnapshot(items))
        assertTrue(empty.priceableLines.isEmpty())
        assertEquals(0, empty.confirmedTotalCents)
    }

    @Test fun localOmissionsPartialCandidateAndPotentialIncomplete() {
        val i = item("ok")
        val missing = item("missing")
        val source = LiveEstimateSource("p", "m", geometry(), listOf(element(i), element(missing),
            element(i, ElementQuantity.Unknown, "unknown"), element(i, ElementQuantity.Range(1000, 2000), "range", ElementApproval.DISCUSSING),
            element(i, id = "wish").copy(source = ElementSource.Wish)))
        val result = preview(source, ActiveCatalogSnapshot(listOf(i)))
        assertEquals(1000, result.confirmedTotalCents)
        assertEquals(4, result.unresolvedItems.size)
        assertFalse(result.canFinalizeFull)
        assertTrue(result.canRequestPartialAcknowledgement)
        assertTrue(result.potentialIncomplete)
        assertEquals(listOf(PricingProblemCode.CATALOG_MISSING), result.unresolvedItems.single { it.elementUuid == "missing" }.reasons)
        val onlyPotentialUnresolved = preview(source.copy(elements = source.elements.filter { it.uuid in listOf("ok", "range") }), ActiveCatalogSnapshot(listOf(i)))
        assertTrue(onlyPotentialUnresolved.canFinalizeFull)
        assertFalse(onlyPotentialUnresolved.canRequestPartialAcknowledgement)
    }

    @Test fun roleBindingsAreRequiredAndUniquenessIncludesDiscussing() {
        val c = item("c", "м²", QuantityMode.CEILING_AREA)
        val e = element(c, ElementQuantity.CeilingArea)
        val s = LiveEstimateSource("p", "m", geometry(), listOf(e))
        val catalog = ActiveCatalogSnapshot(listOf(c))
        assertEquals(PricingProblemCode.ROLE_UNDETERMINED, LivePricingEngine.buildPreview(s, catalog).fatalProblems.single().code)
        assertTrue(preview(s, catalog, mapOf("c" to BusinessRole.CANVAS)).canFinalizeFull)
        val duplicate = s.copy(elements = listOf(e, e.copy(uuid = "c2", approval = ElementApproval.DISCUSSING)))
        val p = preview(duplicate, catalog, mapOf("c" to BusinessRole.CANVAS, "c2" to BusinessRole.CANVAS))
        assertTrue(p.fatalProblems.any { it.code == PricingProblemCode.DUPLICATE_MAIN_CANVAS })
        assertFalse(p.canRequestPartialAcknowledgement)
        val profile = item("p", "м.п.", QuantityMode.CEILING_PERIMETER)
        val s2 = s.copy(elements = listOf(element(profile, ElementQuantity.CeilingPerimeter), element(profile, ElementQuantity.CeilingPerimeter, "p2")))
        assertTrue(preview(s2, ActiveCatalogSnapshot(listOf(profile)), mapOf("p" to BusinessRole.PRIMARY_PROFILE, "p2" to BusinessRole.PRIMARY_PROFILE))
            .fatalProblems.any { it.code == PricingProblemCode.DUPLICATE_PRIMARY_PROFILE })
        assertFalse(LivePricingEngine.buildPreview(s, catalog, roles(s).copy(basisSourceSignature = "old")).canFinalizeFull)
    }

    @Test fun exactMoneyUnitZeroAndOverflowMatrix() {
        assertEquals(1, PricingMath.lineCents(1, 500)) // 0.005 currency -> one cent
        assertEquals(0, PricingMath.lineCents(1, 499))
        assertEquals(29, PricingMath.salePriceCents(0.29))
        assertEquals(1, PricingMath.salePriceCents(0.01))
        assertNull(PricingMath.salePriceCents(0.005))
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 1_000_000_000.0, 0.29000000000000004)) assertNull(PricingMath.salePriceCents(value))
        assertEquals(0, PricingMath.lineCents(0, ElementQuantityRules.MAX_MILLI))
        assertEquals(9_000_000_000_000_000, PricingMath.lineCents(1000, ElementQuantityRules.MAX_MILLI))
        assertFailsWith<PricingOverflow> { PricingMath.lineCents(PricingMath.MAX_PRICE_CENTS, ElementQuantityRules.MAX_MILLI) }
        assertFailsWith<PricingOverflow> { PricingMath.add(Long.MAX_VALUE, 1) }
        for (unit in listOf("шт.", "компл.", "послуга", "custom")) {
            val i = item("i", unit, price = 0.0)
            val s = LiveEstimateSource("p", "m", geometry(), listOf(element(i)))
            val result = preview(s, ActiveCatalogSnapshot(listOf(i)))
            assertEquals(unit != "custom", result.canFinalizeFull)
            if (unit != "custom") assertEquals(0, result.priceableLines.single().totalCents)
            assertFalse(preview(s.copy(elements = listOf(element(i, ElementQuantity.Exact(1500)))), ActiveCatalogSnapshot(listOf(i))).canFinalizeFull)
        }
    }

    @Test fun invalidMetadataQuantityGeometryReviewAndChildTokens() {
        val i = item("light", placement = PlacementKind.POINT)
        val s = LiveEstimateSource("p", "m", geometry(), listOf(element(i)))
        val catalog = ActiveCatalogSnapshot(listOf(i))
        for (changed in listOf(i.copy(code = "new"), i.copy(unit = "м.п."), i.copy(quantityMode = QuantityMode.ENTERED_LENGTH), i.copy(placementKind = PlacementKind.NONE))) {
            assertTrue(preview(s, ActiveCatalogSnapshot(listOf(changed))).unresolvedItems.single().reasons.contains(PricingProblemCode.METADATA_DRIFT))
        }
        assertFalse(preview(s.copy(geometry = s.geometry.copy(areaSquareMeters = 99.0)), catalog).canFinalizeFull)
        assertFalse(preview(s.copy(elements = listOf(element(i, ElementQuantity.Exact(-1)))), catalog).canFinalizeFull)
        val p = PointPlacement("point", "light", 1000, 1000, 1, 1, 1, null)
        val withPoint = s.copy(points = listOf(p))
        assertNotEquals(s.signature, withPoint.signature)
        val review = withPoint.copy(points = listOf(p.copy(mounting = p.mounting.copy(needsReview = true))))
        assertNotEquals(withPoint.signature, review.signature)
        assertTrue(preview(review, catalog).unresolvedItems.single().reasons.contains(PricingProblemCode.PLACEMENT_REVIEW))
        assertNotEquals(catalog.signature, catalog.copy(items = listOf(i.copy(groupUpdatedAt = 2))).signature)
    }

    @Test fun capturedPrecisionPolygonAndSubMilliAreaAreNeverSilentlyRounded() {
        val c = item("c", "м²", QuantityMode.CEILING_AREA)
        val e = element(c, ElementQuantity.CeilingArea)
        val polygon = FrozenPolygonGeometryContract.capture("polygon", listOf(CeilingPointMm(0, 0), CeilingPointMm(4000, 0), CeilingPointMm(3000, 3000), CeilingPointMm(0, 3000)), emptyMap(), 0.001)
        val s = LiveEstimateSource("p", "m", polygon, listOf(e))
        assertEquals(10500, preview(s, ActiveCatalogSnapshot(listOf(c))).priceableLines.single().sourceQuantityMilli)
        val fine = s.copy(geometry = geometry(4001, 3001))
        assertTrue(preview(fine, ActiveCatalogSnapshot(listOf(c))).unresolvedItems.single().reasons.contains(PricingProblemCode.QUANTITY_PRECISION))
        val coarse = s.copy(geometry = geometry(4100, 3100, 0.1))
        assertEquals(12710, preview(coarse, ActiveCatalogSnapshot(listOf(c))).priceableLines.single().sourceQuantityMilli)
    }

    @Test fun billableOverridesAreScopedValidatedAndDoNotMutateSource() {
        val c = item("c", "м²", QuantityMode.CEILING_AREA)
        val source = LiveEstimateSource("p", "m", geometry(4600, 4000), listOf(element(c, ElementQuantity.CeilingArea)))
        val cat = ActiveCatalogSnapshot(listOf(c))
        val binding = mapOf("c" to BusinessRole.CANVAS)
        val override = EstimateQuantityOverride("quote", "p", "m", "c", BusinessRole.CANVAS, 19000, "м²", source.signature, cat.signature, true)
        val result = preview(source, cat, binding, overrides = listOf(override))
        assertTrue(result.canFinalizeFull)
        assertEquals(18400, result.priceableLines.single().sourceQuantityMilli)
        assertEquals(19000, result.priceableLines.single().effectiveBillableQuantityMilli)
        assertEquals(19000, result.confirmedTotalCents)
        assertEquals(ElementQuantity.CeilingArea, source.elements.single().quantity)
        assertEquals(QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE, result.priceableLines.single().quantityOrigin)
        for (bad in listOf(override.copy(compositionUuid = "other"), override.copy(projectUuid = "other"), override.copy(measurementUuid = "other"),
            override.copy(measurementElementUuid = "other"), override.copy(expectedBusinessRole = BusinessRole.ADDITIONAL_WORK), override.copy(quantityMilli = 0),
            override.copy(quantityMilli = -1), override.copy(unit = "м.п."), override.copy(basisSourceSignature = "old"),
            override.copy(basisCatalogSignature = "old"), override.copy(explicitlyConfirmed = false))) {
            val p = preview(source, cat, binding, overrides = listOf(bad))
            assertFalse(p.canFinalizeFull)
            assertFalse(p.canRequestPartialAcknowledgement)
        }
        assertFalse(preview(source, cat, binding, overrides = listOf(override, override)).canFinalizeFull)
        assertFalse(preview(source, cat.copy(items = listOf(c.copy(salePrice = 11.0))), binding, overrides = listOf(override)).canFinalizeFull)
        assertEquals(18400, preview(source, cat, binding).confirmedTotalCents)
        assertEquals(20000, preview(source, cat, binding, "other", listOf(override.copy(compositionUuid = "other", basisScopeSignature = "other", quantityMilli = 20000))).confirmedTotalCents)
    }
    @Test fun profileOverrideAllowsExplicitDecreaseAndRejectsMoneyOverflow() {
        val i = item("profile", "м.п.", QuantityMode.CEILING_PERIMETER)
        val s = LiveEstimateSource("p", "m", geometry(4000, 3125), listOf(element(i, ElementQuantity.CeilingPerimeter)))
        val c = ActiveCatalogSnapshot(listOf(i))
        val role = mapOf("profile" to BusinessRole.PRIMARY_PROFILE)
        val override = EstimateQuantityOverride("quote", "p", "m", "profile", BusinessRole.PRIMARY_PROFILE, 15000,
            "м.п.", s.signature, c.signature, true)
        val p = preview(s, c, role, overrides = listOf(override))
        assertEquals(14250, p.priceableLines.single().sourceQuantityMilli)
        assertEquals(15000, p.priceableLines.single().effectiveBillableQuantityMilli)
        assertTrue(preview(s, c, role, overrides = listOf(override.copy(quantityMilli = 14000))).canFinalizeFull)
        val expensive = c.copy(items = listOf(i.copy(salePrice = 999_999_999.99)))
        val overflow = preview(s, expensive, role, overrides = listOf(override.copy(quantityMilli = ElementQuantityRules.MAX_MILLI,
            basisCatalogSignature = expensive.signature)))
        assertTrue(overflow.fatalProblems.any { it.code == PricingProblemCode.OVERFLOW })
        assertFalse(overflow.canRequestPartialAcknowledgement)
    }

    @Test fun partialExactPlacementsAreVisibleAndDuplicateElementNeverChargesTwice() {
        val i = item("light", placement = PlacementKind.POINT)
        val e = element(i, ElementQuantity.Exact(2000)).copy(placement = ElementPlacement.EXACT, placedQuantityMilli = 1000)
        val s = LiveEstimateSource("p", "m", geometry(), listOf(e))
        val c = ActiveCatalogSnapshot(listOf(i))
        assertTrue(PricingProblemCode.PLACEMENT_PARTIAL in preview(s, c).priceableLines.single().reviewFlags)
        val duplicate = preview(s.copy(elements = listOf(e, e)), c)
        assertFalse(duplicate.canFinalizeFull)
        assertEquals(1, duplicate.priceableLines.size)
        assertTrue(duplicate.fatalProblems.any { it.code == PricingProblemCode.INVALID_SOURCE })
    }

}
