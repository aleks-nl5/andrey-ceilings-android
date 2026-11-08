package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

class TransientQuoteTest {
    @Test fun explicitSaveIntentSurvivesSurfaceRecreationAndRetry() {
        val loaded = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(loaded)
        p.editTitle("  Proposal  ")
        val first = assertNotNull(p.beginSave(1))
        assertEquals("Proposal", first.title)
        assertNull(p.beginSave(1))
        p.acceptRead(p.beginRead(), loaded)
        assertTrue(p.saveState.value.saving)
        assertEquals("  Proposal  ", p.saveState.value.title)
        p.saveFailed("uncertain response")
        val retry = assertNotNull(p.beginSave(1))
        assertEquals(first, retry)
        p.saved("saved-series")
        assertNull(p.beginSave(1))
        assertEquals("saved-series", p.saveState.value.resultSeriesUuid)
        p.consumeSaved()
        assertNotEquals(first.operationUuid, assertNotNull(p.beginSave(1)).operationUuid)
    }

    @Test fun pendingPartialSaveLocksAllInputsAndFailureRevalidatesDeferredSource() {
        val data = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(data)
        p.select("element:o0", false)
        p.acknowledge(true)
        p.editTitle("Partial")
        val captured = p.state.value
        assertNotNull(p.beginSave(1))
        p.select("element:d", true)
        p.editQuantity("c", "15"); p.confirmQuantity("c"); p.resetQuantity("c")
        p.editDiscount("50"); p.confirmDiscount(); p.acknowledge(false); p.editTitle("Other")
        assertEquals(captured, p.state.value)
        p.acceptRead(p.beginRead(), data.copy(catalog = data.catalog.copy(versionToken = "changed")))
        assertEquals(captured, p.state.value)
        p.saveFailed("Stale catalog")
        assertFalse(p.state.value.acknowledged)
        assertNotEquals(captured.fingerprint, p.state.value.fingerprint)
        p.editDiscount("20")
        assertEquals("20", p.state.value.discountText)
        p.editTitle("Retry")
        assertEquals("Retry", p.saveState.value.title)
    }

    @Test fun discountIsRoundedOnceOnAggregateAndCannotOverflowSafeResults() {
        assertEquals(QuoteAmounts(5, 1000, 1, 4), QuoteDiscount.calculate(5, 1000))
        assertEquals(QuoteAmounts(3, 5000, 2, 1), QuoteDiscount.calculate(3, 5000))
        assertEquals(QuoteAmounts(0, 10000, 0, 0), QuoteDiscount.calculate(0, 10000))
        assertEquals(0, QuoteDiscount.calculate(Long.MAX_VALUE, 10000).finalCents)
        assertEquals(0, QuoteDiscount.calculate(Long.MAX_VALUE, 0).discountCents)
        assertEquals(4_611_686_018_427_387_904L, QuoteDiscount.calculate(Long.MAX_VALUE, 5000).discountCents)
        assertEquals(1234, QuoteDiscount.parseBasisPoints("12,34"))
        assertEquals(50, QuoteDiscount.parseBasisPoints(".5"))
        for (bad in listOf("", "0.", "100.01", "-1", "101", "1.001", "1..2", "9999999999999999999999"))
            assertNull(QuoteDiscount.parseBasisPoints(bad), bad)
        assertFailsWith<IllegalArgumentException> { QuoteDiscount.calculate(-1, 0) }
        assertFailsWith<IllegalArgumentException> { QuoteDiscount.calculate(1, 10001) }
    }

    @Test fun initialScopeMandatoryAndDiscussingAreExplicitAndCountedOnce() {
        val p = TransientQuoteFixture.presenter()
        assertEquals(2800, p.state.value.amounts?.subtotalCents)
        assertFalse("element:d" in p.state.value.selected)
        assertTrue(p.state.value.preview!!.canFinalizeFull)
        p.select("element:c", false)
        assertTrue("element:c" in p.state.value.selected)
        p.select("element:d", true); p.select("element:d", true)
        assertEquals(3000, p.state.value.amounts?.subtotalCents)
        assertEquals(ElementApproval.DISCUSSING, p.state.value.source!!.elements.single { it.uuid == "d" }.approval)
        p.select("element:o0", false)
        assertEquals(2800, p.state.value.amounts?.subtotalCents)
        assertTrue(p.state.value.preview!!.isPartial)
        assertEquals(ExclusionReason.USER_EXCLUDED, p.state.value.preview!!.excluded.single { it.sourceKey == "element:o0" }.reason)
    }

    @Test fun partialAcknowledgementTracksAllInputsAndIsNeverImplicit() {
        val p = TransientQuoteFixture.presenter()
        p.select("element:o0", false)
        assertFalse(p.state.value.acknowledged)
        p.acknowledge(true); assertTrue(p.state.value.acknowledged)
        val fingerprint = p.state.value.fingerprint
        p.editDiscount("10"); assertNull(p.state.value.amounts); assertFalse(p.state.value.acknowledged)
        p.confirmDiscount()
        assertEquals(2340, p.state.value.amounts?.finalCents)
        assertNotEquals(fingerprint, p.state.value.fingerprint)
        p.acknowledge(true); p.select("element:d", true); assertFalse(p.state.value.acknowledged)
        p.acknowledge(true); p.editQuantity("c", "13"); assertFalse(p.state.value.acknowledged); assertNull(p.state.value.amounts)
        p.confirmQuantity("c"); assertEquals(2700, p.state.value.amounts?.subtotalCents?.minus(200))
    }

    @Test fun overridesKeepSourceDistinctAndPartialInputNeverFallsBack() {
        val p = TransientQuoteFixture.presenter()
        p.editQuantity("c", ".007"); assertNull(p.state.value.amounts)
        p.confirmQuantity("c")
        val row = p.state.value.lines.single { it.elementUuid == "c" }
        assertEquals(12000, row.sourceQuantityMilli); assertEquals(7, row.effectiveBillableQuantityMilli)
        assertEquals(1, p.state.value.lines.count { it.elementUuid == "c" })
        p.editQuantity("c", "0."); p.confirmQuantity("c"); assertNull(p.state.value.amounts)
        p.resetQuantity("c"); assertEquals(2800, p.state.value.amounts?.subtotalCents)
        p.editQuantity("o0", "1.5"); p.confirmQuantity("o0"); assertNull(p.state.value.amounts)
        p.editQuantity("o0", "2"); p.confirmQuantity("o0"); assertEquals(3000, p.state.value.amounts?.subtotalCents)
        assertEquals(ElementQuantity.Exact(1000), p.state.value.source!!.elements.single { it.uuid == "o0" }.quantity)
    }

    @Test fun eligibleOptionalSubsetIsNarrowAndEngineRejectsForgedOverrides() {
        val data = TransientQuoteFixture.loaded()
        val source = data.source
        val optional = source.elements.single { it.uuid == "o0" }
        assertTrue(BillableOverrideRules.eligible(source, optional))
        for (bad in listOf(optional.copy(quantity = ElementQuantity.Unknown),
            optional.copy(quantity = ElementQuantity.Range(1000, 2000)),
            optional.copy(placedQuantityMilli = 1000, placement = ElementPlacement.APPROXIMATE),
            optional.copy(source = (optional.source as ElementSource.CatalogSelection).copy(placementKind = PlacementKind.POINT)),
            optional.copy(quantity = ElementQuantity.CeilingArea))) {
            assertFalse(BillableOverrideRules.eligible(source, bad))
        }
        val roles = BusinessRoleBindings(source.signature, source.elements.associate { it.uuid to it.businessRole!! })
        val forged = EstimateQuantityOverride("quote", source.projectUuid, "m", "o0", BusinessRole.ADDITIONAL_WORK,
            1500, "шт.", source.signature, data.catalog.signature, true)
        assertTrue(LivePricingEngine.buildPreview(source, data.catalog, roles, "quote", listOf(forged)).fatalProblems.any { it.code == PricingProblemCode.INVALID_OVERRIDE })
    }

    @Test fun mandatoryFailuresBlockFullAndPartialButZeroPriceIsValid() {
        val data = TransientQuoteFixture.loaded()
        val zero = data.copy(catalog = data.catalog.copy(items = data.catalog.items.map { it.copy(salePrice = 0.0) }))
        assertEquals(0, TransientQuoteFixture.presenter(zero).state.value.amounts?.finalCents)
        val variants = listOf(
            data.copy(source = data.source.copy(elements = data.source.elements.filter { it.uuid != "c" })),
            data.copy(source = data.source.copy(elements = data.source.elements.map { if (it.uuid == "c") it.copy(businessRole = null) else it })),
            data.copy(catalog = data.catalog.copy(items = data.catalog.items.filter { it.uuid != "c" })),
            data.copy(source = data.source.copy(elements = data.source.elements + data.source.elements.first().copy(uuid = "duplicate"))))
        variants.forEach {
            val p = TransientQuoteFixture.presenter(it); p.select("element:o0", false)
            assertNull(p.state.value.amounts); assertFalse(p.state.value.canAcknowledge)
        }
    }

    @Test fun unresolvedOptionalHasRealReasonsAndUnselectedDiscussingDoesNotCausePartial() {
        val data = TransientQuoteFixture.loaded()
        val bad = data.copy(source = data.source.copy(elements = data.source.elements.map {
            if (it.uuid == "o0" || it.uuid == "d") it.copy(quantity = ElementQuantity.Unknown) else it
        }))
        val p = TransientQuoteFixture.presenter(bad)
        assertTrue(p.state.value.canAcknowledge)
        assertEquals(2600, p.state.value.amounts?.subtotalCents)
        assertTrue(p.state.value.preview!!.excluded.single { it.sourceKey == "element:o0" }.problems.contains(PricingProblemCode.UNKNOWN_QUANTITY))
        val discussingOnly = data.copy(source = data.source.copy(elements = data.source.elements.filter { it.uuid != "o0" }))
        assertFalse(TransientQuoteFixture.presenter(discussingOnly).state.value.preview!!.isPartial)
    }

    @Test fun staleUnavailableLateResponsesAndClosedSessionsCannotShowOldTotals() {
        val data = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(data)
        p.select("element:o0", false); p.acknowledge(true)
        val old = p.beginRead(); val fresh = p.beginRead()
        assertNull(p.state.value.amounts); assertFalse(p.state.value.acknowledged)
        p.acceptRead(old, data); assertNull(p.state.value.amounts)
        p.acceptRead(fresh, PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE))
        assertNull(p.state.value.amounts)
        val next = p.beginRead(); p.acceptRead(next, data.copy(source = data.source.copy(measurementUuid = "other")))
        assertEquals(PricingProblemCode.STALE_SOURCE, p.state.value.unavailable)
        p.close(); p.acceptRead(p.beginRead(), data); assertNull(p.state.value.amounts)
    }

    @Test fun newBasisRequiresOverrideReconfirmationAndResetsAck() {
        val data = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(data)
        p.editQuantity("c", "13"); p.confirmQuantity("c")
        p.select("element:o0", false); p.acknowledge(true)
        val changed = data.copy(catalog = data.catalog.copy(versionToken = "new"))
        p.acceptRead(p.beginRead(), changed)
        assertNull(p.state.value.amounts); assertFalse(p.state.value.acknowledged)
        p.confirmQuantity("c"); assertEquals(2700, p.state.value.amounts?.subtotalCents)
    }

    @Test fun ineligibleOrDisappearedOverrideMustBeExplicitlyRemovedAfterRefresh() {
        val data = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(data)
        p.editQuantity("o0", "2"); p.confirmQuantity("o0")
        val changed = data.copy(source = data.source.copy(elements = data.source.elements.map {
            if (it.uuid == "o0") it.copy(quantity = ElementQuantity.Unknown) else it
        }))
        p.acceptRead(p.beginRead(), changed)
        assertNull(p.state.value.amounts)
        p.confirmQuantity("o0"); assertNull(p.state.value.amounts)
        p.resetQuantity("o0")
        assertEquals(2600, p.state.value.amounts?.subtotalCents)
        assertTrue(p.state.value.canAcknowledge)
    }

    @Test fun temporarilyUnpricedOptionalDoesNotUndoExplicitExclusion() {
        val data = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(data)
        p.select("element:o0", false)
        val unavailablePrice = data.copy(catalog = data.catalog.copy(items = data.catalog.items.filter { it.uuid != "o" }))
        p.acceptRead(p.beginRead(), unavailablePrice)
        assertEquals(ExclusionReason.UNRESOLVED, p.state.value.preview!!.excluded.single { it.sourceKey == "element:o0" }.reason)
        p.acceptRead(p.beginRead(), data)
        assertFalse("element:o0" in p.state.value.selected)
        assertEquals(ExclusionReason.USER_EXCLUDED, p.state.value.preview!!.excluded.single { it.sourceKey == "element:o0" }.reason)
    }

    @Test fun selectedDiscussingUnavailableCannotBecomeSilentlyReducedReadyFull() {
        val data = TransientQuoteFixture.loaded()
        val variants = listOf(
            data.copy(catalog = data.catalog.copy(items = data.catalog.items.filter { it.uuid != "d" })),
            data.copy(source = data.source.copy(elements = data.source.elements.map { if (it.uuid == "d") it.copy(quantity = ElementQuantity.Unknown) else it })),
            data.copy(source = data.source.copy(elements = data.source.elements.map { if (it.uuid == "d") it.copy(quantity = ElementQuantity.Range(1000, 2000)) else it })),
            data.copy(source = data.source.copy(elements = data.source.elements.filter { it.uuid != "d" })),
            data.copy(catalog = data.catalog.copy(items = data.catalog.items.map { if (it.uuid == "d") it.copy(unit = "м.п.") else it }))
        )
        for (unavailable in variants) {
            val p = TransientQuoteFixture.presenter(data)
            p.select("element:d", true)
            assertEquals(3000, p.state.value.amounts?.subtotalCents)
            repeat(2) {
                p.acceptRead(p.beginRead(), unavailable)
                assertTrue("element:d" in p.state.value.selected)
                assertEquals("Можлива робота", p.state.value.unavailableSelectedDiscussing.single().label)
                assertNull(p.state.value.amounts)
                assertNull(p.state.value.fingerprint)
                assertFalse(p.state.value.preview!!.canFinalizeFull)
                assertFalse(p.state.value.preview!!.canRequestPartialAcknowledgement)
                p.acknowledge(true); assertFalse(p.state.value.acknowledged)
            }
            // Correcting the same stable source restores the user's inclusion, not a smaller quote.
            p.acceptRead(p.beginRead(), data)
            assertEquals(3000, p.state.value.amounts?.subtotalCents)
            assertTrue("element:d" in p.state.value.selected)
            p.acceptRead(p.beginRead(), unavailable)
            // Only an explicit action permits the smaller total.
            p.select("element:d", false)
            assertEquals(2800, p.state.value.amounts?.subtotalCents)
            assertTrue(p.state.value.preview!!.canFinalizeFull)
            assertTrue(p.state.value.unavailableSelectedDiscussing.isEmpty())
        }
    }

    @Test fun selectedDiscussingFailureInvalidatesPartialAckAndCannotRemapByTitle() {
        val data = TransientQuoteFixture.loaded()
        val p = TransientQuoteFixture.presenter(data)
        p.select("element:d", true); p.select("element:o0", false)
        p.acknowledge(true); assertTrue(p.state.value.acknowledged)
        val replacement = data.copy(source = data.source.copy(elements = data.source.elements.map {
            if (it.uuid == "d") it.copy(uuid = "new-d") else it
        }))
        p.acceptRead(p.beginRead(), replacement)
        assertNull(p.state.value.amounts); assertFalse(p.state.value.canAcknowledge)
        assertFalse(p.state.value.acknowledged)
        assertTrue("element:d" in p.state.value.selected)
        assertFalse("element:new-d" in p.state.value.selected)
        p.select("element:d", false)
        assertEquals(2600, p.state.value.amounts?.subtotalCents)
        assertTrue(p.state.value.canAcknowledge); assertFalse(p.state.value.acknowledged)
    }

    @Test fun quoteBackAndRestorationPreserveOnlyRouteIdentity() {
        for (base in listOf(AppNavigationState().selectTab(AppRoute.MEASUREMENTS),
            AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(1))) {
            val details = base.openMeasurement(1, 2)
            val quote = details.openMeasurementQuote()
            assertTrue(quote.measurementQuote); assertEquals(details, quote.back())
            assertEquals(quote, AppNavigationState.restore(quote.snapshot()))
            assertTrue(quote.snapshot().last().startsWith("MEASUREMENT_QUOTE:"))
            assertFalse(quote.selectTab(AppRoute.PRICES).measurementQuote)
        }
        for (tail in listOf("MEASUREMENT_QUOTE:1:0", "MEASUREMENT_QUOTE:01:2", "MEASUREMENT_QUOTE:1:2:bad"))
            assertFalse(AppNavigationState.restore(listOf("MEASUREMENTS", tail)).measurementQuote)
        assertFalse(AppNavigationState.restore(listOf("MEASUREMENTS", "MEASUREMENT_DETAILS:1:2", "MEASUREMENT_QUOTE:1:2")).measurementQuote)
    }

    @Test fun visibilityRespondsToViewportInsteadOfScrollPercentage() {
        assertEquals(0f, quoteSummaryVisibility(1000, 200, 0, 1000))
        assertEquals(.5f, quoteSummaryVisibility(900, 200, 0, 1000))
        assertEquals(1f, quoteSummaryVisibility(800, 200, 0, 1000))
        assertEquals(0f, quoteSummaryVisibility(800, 200, 0, 700))
        assertEquals(1f, quoteSummaryVisibility(0, 1200, 0, 700))
    }
}
