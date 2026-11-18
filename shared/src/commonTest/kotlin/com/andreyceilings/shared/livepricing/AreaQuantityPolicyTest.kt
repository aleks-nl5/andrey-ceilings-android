package com.andreyceilings.shared.livepricing

import kotlin.test.*

class AreaQuantityPolicyTest {
    @Test fun capturedAreaRoundsHalfUpBeforeMoneyWithoutChangingManualQuantityPrecision() {
        for ((area, milli) in listOf(13.7608 to 13761L, 13.6863 to 13686L, 13.7365 to 13737L,
            13.760499 to 13760L, 13.7604995 to 13760L, 13.7605005 to 13761L, 13.760500 to 13761L, 13.760501 to 13761L,
            13.446 to 13446L, 13.694 to 13694L, 13.760800000000001 to 13761L, 4_000_000.0 to 4_000_000_000L)) {
            assertEquals(milli, PricingMath.capturedAreaMilli(area), "$area")
        }
        assertEquals(137610, PricingMath.lineCents(10000, PricingMath.capturedAreaMilli(13.7608)!!))
        assertNull(PricingMath.capturedMilli(13.7608)) // Unchanged strict manual/perimeter contract.
        for (area in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 0.000499, 4_000_000.1))
            assertNull(PricingMath.capturedAreaMilli(area), "$area")
    }
}
