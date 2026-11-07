package com.andreyceilings.app

import org.junit.Assert.*
import org.junit.Test

class CalculatorNumericInputTest {
    @Test fun leadingDecimalsReachLegacyCalculatorWithoutCoercingInvalidDraftsToZero() {
        for (text in listOf(".5", ",5", "0.5")) assertEquals(0.5, text.toSafeDouble(), 0.0)
        for (text in listOf(".007", ",007", "0.007")) assertEquals(0.007, text.toSafeDouble(), 0.0)
        for (text in listOf("", ".", ",", "0.", "0,", "-.5", "..5", "1..2", ".,5", "NaN", "1e3", ".0007"))
            assertFalse("Invalid draft: $text", text.toSafeDouble().isFinite())
        assertEquals(0.0, "0".toSafeDouble(), 0.0)
    }
}
