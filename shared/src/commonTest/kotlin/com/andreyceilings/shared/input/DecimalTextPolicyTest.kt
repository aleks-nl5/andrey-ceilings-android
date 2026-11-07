package com.andreyceilings.shared.input

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.PriceCatalogRules
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class DecimalTextPolicyTest {
    @Test fun editingIsNotCommitAndSignedSyntaxIsExplicit() {
        for (signed in listOf(false, true)) {
            val policy = DecimalTextPolicy(3, signed)
            for (text in listOf("", ".", ",", "0.", "0,")) {
                assertEquals(DecimalTextState.PARTIAL, policy.state(text), text)
                assertNull(policy.committed(text))
            }
            for (text in listOf(".5", ",5", ".007", ",007", ".0", "2,5", "0.5", "0"))
                assertEquals(DecimalTextState.COMPLETE, policy.state(text), text)
            for (text in listOf("..5", "1..2", ".,5", "+1", "--1", "NaN", "1e2", "1 2", ".0007"))
                assertEquals(DecimalTextState.INVALID, policy.state(text), text)
        }
        for (text in listOf("-", "-.", "-,")) {
            assertEquals(DecimalTextState.PARTIAL, DecimalTextPolicy(3, true).state(text))
            assertNull(DecimalTextPolicy(3, true).committed(text))
            assertEquals(DecimalTextState.INVALID, DecimalTextPolicy(3).state(text))
        }
        assertEquals("-0.007", DecimalTextPolicy(3, true).committed("-,007"))
        assertEquals(DecimalTextState.INVALID, DecimalTextPolicy(0).state(".5"))
        assertEquals(DecimalTextState.INVALID, DecimalTextPolicy(2).state(".007"))
    }

    @Test fun unfinishedOrMalformedTextCannotBecomeADomainValue() {
        for (text in listOf("", ".", ",", "-.", "-,", "0.", "0,", "..5", "1..2", ".,5", ".0007")) {
            assertNull(parseElementMilli(text, "м.п."), text)
            assertNull(parsePointMetres(text), text)
            assertNull(PriceCatalogRules.parseSalePrice(text), text)
            assertFailsWith<IllegalArgumentException>(text) {
                measurementLengthMm(text, MeasurementPrecision.ONE_MILLIMETER)
            }
        }
    }

    @Test fun geometryPrecisionAndCircleEvenMillimetresRemainDomainConstraints() {
        for (text in listOf(".007", ",007", "0.007")) {
            assertEquals(7L, measurementLengthMm(text, MeasurementPrecision.ONE_MILLIMETER))
            assertFailsWith<IllegalArgumentException> { measurementLengthMm(text, MeasurementPrecision.FIVE_MILLIMETERS) }
            assertFailsWith<IllegalArgumentException> {
                AreaShape.circleFromDiameter(PointPositionMm(0, 0), parsePointMetres(text)!!)
            }
        }
        assertEquals(500L, measurementLengthMm(".5", MeasurementPrecision.TEN_CENTIMETERS))
        assertFailsWith<IllegalArgumentException> { measurementLengthMm(".000", MeasurementPrecision.ONE_MILLIMETER) }
        assertFailsWith<IllegalArgumentException> { measurementLengthMm("1000.001", MeasurementPrecision.ONE_MILLIMETER) }
    }

    @Test fun separatorReplacesSelectionAndPreservesCaretInsteadOfAppending() {
        assertEquals(TextFieldValue(",", TextRange(1)), insertDecimalSeparator(TextFieldValue("")))
        assertEquals(TextFieldValue("-,", TextRange(2)), insertDecimalSeparator(TextFieldValue("-", TextRange(1))))
        assertEquals(TextFieldValue(",5", TextRange(1)), insertDecimalSeparator(TextFieldValue("05", TextRange(0, 1))))
        assertEquals(TextFieldValue("1,25", TextRange(2)), insertDecimalSeparator(TextFieldValue("1.25", TextRange(2, 1))))
        assertNull(insertDecimalSeparator(TextFieldValue(".5", TextRange(2))))
    }
}
