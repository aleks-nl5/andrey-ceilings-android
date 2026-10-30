package com.andreyceilings.shared.estimate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstimateRecalculationPreviewTest {
    private fun before(code: String = "canvas.pvc", uuid: String? = null) =
        ImmutableEstimateLine(code, "Стара назва", "м²", 3.0, 100.0, 300.0, uuid)

    private fun price(code: String = "canvas.pvc", uuid: String = "price-1", amount: Double = 120.0,
                      unit: String = "м²", title: String = "Нова назва") =
        CurrentEstimatePrice(code, uuid, title, unit, amount, updatedAt = 12L)

    private fun preview(rows: List<ImmutableEstimateLine> = listOf(before()),
                        prices: List<CurrentEstimatePrice> = listOf(price())) =
        EstimateRecalculation.preview("revision-1", 1, rows.sumOf { it.lineTotal }, rows, prices)

    @Test fun priceAndTitleChangeAreVisibleWithoutMutatingSource() {
        val frozen = before()
        val result = preview(listOf(frozen))
        assertTrue(result.canConfirm)
        assertEquals(300.0, result.originalTotal)
        assertEquals(360.0, result.proposedTotal)
        assertEquals(60.0, result.delta)
        assertEquals("Нова назва", result.lines.single().after?.title)
        assertEquals("Стара назва", frozen.title)
        assertEquals(100.0, frozen.unitPrice)
    }

    @Test fun missingPriceFailsClosed() {
        val result = preview(prices = emptyList())
        assertFalse(result.canConfirm)
        assertNull(result.proposedTotal)
        assertEquals(setOf(RecalculationProblem.MISSING_PRICE), result.problems)
    }

    @Test fun doesNotChooseOneOfAmbiguousCatalogCodes() {
        val result = preview(prices = listOf(price(uuid = "a"), price(uuid = "b")))
        assertFalse(result.canConfirm)
        assertEquals(RecalculationProblem.AMBIGUOUS_PRICE, result.lines.single().problem)
    }

    @Test fun matchingUuidCannotHideSemanticCodeChange() {
        val result = preview(
            rows = listOf(before(uuid = "price-2")),
            prices = listOf(
                price(uuid = "price-1"),
                price(code = "canvas.other_material", uuid = "price-2", amount = 140.0)
            )
        )
        assertFalse(result.canConfirm)
        assertNull(result.proposedTotal)
        assertEquals(RecalculationProblem.CHANGED_CODE, result.lines.single().problem)
    }

    @Test fun matchingUuidAndCodeAllowPriceAndDisplayTitleChange() {
        val result = preview(
            rows = listOf(before(uuid = "price-2")),
            prices = listOf(
                price(uuid = "price-1", code = "profile.other"),
                price(code = "canvas.pvc", uuid = "price-2", amount = 140.0)
            )
        )
        assertTrue(result.canConfirm)
        assertEquals(420.0, result.proposedTotal)
        assertEquals("canvas.pvc", result.lines.single().after?.stableCode)
        assertEquals("Нова назва", result.lines.single().after?.title)
    }

    @Test fun missingUuidCannotBeReplacedBySameNameOrCode() {
        val result = preview(
            rows = listOf(before(uuid = "deleted-uuid")),
            prices = listOf(price(title = "Стара назва"))
        )
        assertFalse(result.canConfirm)
        assertEquals(RecalculationProblem.MISSING_PRICE, result.lines.single().problem)
    }

    @Test fun unitChangeBlocksPreview() {
        val result = preview(prices = listOf(price(unit = "шт.")))
        assertFalse(result.canConfirm)
        assertEquals(RecalculationProblem.CHANGED_UNIT, result.lines.single().problem)
    }

    @Test fun historicalRoundingAndLegacyRowsCannotBeRepricedSilently() {
        val rows = listOf(before(), ImmutableEstimateLine("system.rounding", "Округлення", "посл.", 1.0, 1.0, 1.0))
        val result = preview(rows)
        assertFalse(result.canConfirm)
        assertEquals(setOf(RecalculationProblem.HISTORICAL_ADJUSTMENT), result.problems)
        assertEquals(301.0, result.originalTotal)
    }

    @Test fun invalidPriceAndInconsistentSnapshotBlockPreview() {
        val invalid = preview(prices = listOf(price(amount = Double.NaN)))
        assertFalse(invalid.canConfirm)
        assertEquals(RecalculationProblem.INVALID_PRICE, invalid.lines.single().problem)
        val mismatch = EstimateRecalculation.preview("rev", 1, 400.0, listOf(before()), listOf(price()))
        assertFalse(mismatch.canConfirm)
        assertTrue(RecalculationProblem.INCONSISTENT_SNAPSHOT in mismatch.problems)
    }

    @Test fun unchangedPriceStillUsesFrozenQuantity() {
        val result = preview(prices = listOf(price(amount = 100.0, title = "Стара назва")))
        assertEquals(300.0, result.proposedTotal)
        assertEquals(0.0, result.delta)
        assertEquals(3.0, result.lines.single().after?.quantity)
    }
}
