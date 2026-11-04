package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.prices.QuantityMode
import kotlin.test.*

class ElementQuantityRulesTest {
    @Test fun unknownExactRangeAndDerivedRemainDifferent() {
        ElementQuantityRules.validate(ElementQuantity.Unknown, null)
        ElementQuantityRules.validate(ElementQuantity.Exact(6000), "шт.")
        ElementQuantityRules.validate(ElementQuantity.Range(4000, 6000), "шт.")
        ElementQuantityRules.validate(ElementQuantity.Exact(2500), "м.п.", QuantityMode.ENTERED_LENGTH)
        ElementQuantityRules.validate(ElementQuantity.CeilingArea, "м²", QuantityMode.CEILING_AREA)
        ElementQuantityRules.validate(ElementQuantity.CeilingPerimeter, "м.п.", QuantityMode.CEILING_PERIMETER)
        ElementQuantityRules.validate(ElementQuantity.Exact(ElementQuantityRules.MAX_MILLI), "custom")
    }

    @Test fun boundsOverflowWholePiecesAndBasesFailClosed() {
        for (quantity in listOf(ElementQuantity.Exact(0), ElementQuantity.Exact(-1), ElementQuantity.Exact(Long.MIN_VALUE),
            ElementQuantity.Exact(Long.MAX_VALUE), ElementQuantity.Exact(ElementQuantityRules.MAX_MILLI + 1),
            ElementQuantity.Range(6000, 4000), ElementQuantity.Range(4000, 4000), ElementQuantity.Range(0, 6000))) {
            assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(quantity, "custom") }
        }
        for (quantity in listOf(ElementQuantity.Exact(2500), ElementQuantity.Range(4000, 6500))) {
            assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(quantity, "шт.") }
        }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(ElementQuantity.Exact(1000), null) }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(ElementQuantity.Unknown, "  ") }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(ElementQuantity.CeilingArea, "м²") }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(ElementQuantity.Unknown, "м²", QuantityMode.CEILING_AREA) }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> { ElementQuantityRules.validate(ElementQuantity.Exact(1000), "шт.", QuantityMode.ENTERED_LENGTH) }
    }
}
