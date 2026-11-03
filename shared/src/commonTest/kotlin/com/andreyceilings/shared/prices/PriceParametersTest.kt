package com.andreyceilings.shared.prices

import kotlin.test.*

class PriceParametersTest {
    @Test fun starterMatchesDemoPriceListAt83fece8() {
        val fingerprint = StarterPriceCatalog.items.joinToString("\n") {
            "${it.code}|${it.title}|${it.category}|${it.unit}|${it.price}"
        }.hashCode()
        assertEquals(-1924211895, fingerprint, "Original 22 code/title/category/unit/price tuples must match")
    }

    @Test fun quantityBasisRequiresAnAppropriateUnitAndPlacementIsIndependent() {
        for (mode in QuantityMode.entries) {
            val valid = when (mode) {
                QuantityMode.MANUAL -> "довільна одиниця"
                QuantityMode.CEILING_AREA -> "м²"
                QuantityMode.CEILING_PERIMETER, QuantityMode.ENTERED_LENGTH -> "м.п."
            }
            PriceParameters.validate(valid, mode)
            assertFails { PriceParameters.validate(" ", mode) }
            if (mode != QuantityMode.MANUAL) assertFails { PriceParameters.validate("шт.", mode) }
        }
        assertEquals(22, StarterPriceCatalog.items.size)
        assertEquals(22, StarterPriceCatalog.items.map { it.code }.distinct().size)
        assertEquals(5, StarterPriceCatalog.groupNames.size)
    }
}
