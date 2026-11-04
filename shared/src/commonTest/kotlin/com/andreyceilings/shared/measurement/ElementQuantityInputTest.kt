package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.prices.QuantityMode
import kotlin.test.*

class ElementQuantityInputTest {
    @Test fun exactIntegerMilliRoundTripAndBothSeparators() {
        for ((text, expected) in listOf("2.5" to 2500L, "2,5" to 2500L, "0,125" to 125L,
            "6" to 6000L, "9000000000000" to ElementQuantityRules.MAX_MILLI, "0002,500" to 2500L)) {
            assertEquals(expected, parseElementMilli(text, "м.п."))
            assertEquals(expected, parseElementMilli(formatElementMilli(expected), "м.п."))
        }
        for (value in listOf(1L, 125L, 1000L, 2500L, 9_000_000_000_000_000L))
            assertEquals(value, parseElementMilli(formatElementMilli(value), "власна одиниця"))
        assertEquals("4–6 шт.", formatElementQuantity(ElementQuantity.Range(4000, 6000), "шт."))
        assertEquals("Потрібно уточнити", formatElementQuantity(ElementQuantity.Unknown, null))
        assertEquals("За площею стелі", formatElementQuantity(ElementQuantity.CeilingArea, "м²"))
    }
    @Test fun invalidNeverRoundsAndDraftKeepsIntermediateText() {
        for (text in listOf("", "0", "-1", "+1", "NaN", "1e3", "1,0001", "1.2,3", "2,", "2.",
            "9000000000000,001", "9223372036854775808", "9999999999999999999999999999", "1 000", ".5"))
            assertNull(parseElementMilli(text, "м.п."), text)
        assertNull(parseElementMilli("2,5", "шт."))
        assertEquals(6000L, parseElementMilli("6,000", "шт."))
        for (text in listOf("2,", "2.")) {
            val draft = ElementQuantityInput(ElementQuantityKind.EXACT, exact = text)
            assertEquals(text, draft.exact)
            assertNull(draft.quantity("м.п."))
        }
        assertNull(ElementQuantityInput(ElementQuantityKind.EXACT, "1").quantity(null))
        for ((low, high) in listOf("4" to "4", "6" to "4", "0" to "6"))
            assertNull(ElementQuantityInput(ElementQuantityKind.RANGE, minimum = low, maximum = high).quantity("шт."))
        assertEquals(ElementQuantity.CeilingPerimeter, ElementQuantityInput().quantity("м.п.", QuantityMode.CEILING_PERIMETER))
        assertNull(ElementQuantityInput().quantity("шт.", QuantityMode.CEILING_AREA))
    }
    @Test fun wishUnitThreeIntentsAndLinkDoesNotCoerce() {
        val wish = MeasurementElement("e", "m", ElementSource.Wish, "Name", "", "шт.", ElementApproval.DISCUSSING,
            ElementQuantity.Exact(6000), ElementPlacement.UNPLACED, 0, 1, 1, null)
        val draft = ElementEditorDraft.edit(wish)
        assertEquals(WishUnitChange.Keep, draft.unitChange())
        assertEquals(WishUnitChange.Set("м.п."), draft.copy(unit = " м.п. ").unitChange())
        assertEquals(WishUnitChange.Clear, draft.copy(unit = "").unitChange())
        assertNotNull(draft.copy(unit = "").validationError)
        assertNull(draft.copy(unit = "", input = ElementQuantityInput()).validationError)
        val length = ElementCatalogItem("i", "g", "Length", "length", "м.п.", QuantityMode.ENTERED_LENGTH)
        val link = ElementEditorDraft.edit(wish, ElementEditorAction.LINK).copy(selection = length)
        assertFalse(link.compatibleKeep)
        assertNotNull(link.validationError)
        assertEquals(ElementQuantity.Exact(6000), link.original?.quantity)
        assertNull(link.copy(replaceQuantity = true,
            input = ElementQuantityInput(ElementQuantityKind.EXACT, exact = "2,5")).validationError)
        val derived = link.copy(selection = length.copy(mode = QuantityMode.CEILING_PERIMETER))
        assertNotNull(derived.validationError)
        assertNull(derived.copy(replaceQuantity = true).validationError)
    }
}
