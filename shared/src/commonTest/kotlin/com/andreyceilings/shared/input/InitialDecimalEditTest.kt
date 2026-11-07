package com.andreyceilings.shared.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.andreyceilings.shared.measurement.parseElementMilli
import com.andreyceilings.shared.measurement.parsePointMetres
import com.andreyceilings.shared.prices.PriceCatalogRules
import kotlin.test.*

class InitialDecimalEditTest {
    @Test fun firstSeparatorAndPasteGainOneDisplayZero() {
        for (raw in listOf(".", ",", ".5", ",5", ".007", ",007")) {
            val next = TextFieldValue(raw, TextRange(raw.length))
            val edited = normalizeInitialDecimalEdit(TextFieldValue(""), next)
            assertEquals("0$raw", edited.text)
            assertEquals(TextRange(raw.length + 1), edited.selection)
            assertSame(edited, normalizeInitialDecimalEdit(TextFieldValue(""), edited))
        }
        val signed = normalizeInitialDecimalEdit(TextFieldValue("-", TextRange(1)), TextFieldValue("-.", TextRange(2)), true)
        assertEquals(TextFieldValue("-0.", TextRange(3)), signed)
        for (raw in listOf("-.", "-,", "-.007", "-,007")) {
            val next = TextFieldValue(raw, TextRange(raw.length))
            assertSame(next, normalizeInitialDecimalEdit(TextFieldValue(""), next))
            assertEquals("-0${raw.drop(1)}", normalizeInitialDecimalEdit(TextFieldValue(""), next, true).text)
        }
    }

    @Test fun replacementAndCommaActionMapTheActualSelection() {
        val whole = TextFieldValue("0.5", TextRange(0, 3))
        assertEquals(TextFieldValue("0.5", TextRange(3)),
            normalizeInitialDecimalEdit(whole, TextFieldValue(".5", TextRange(2))))
        val prefix = TextFieldValue("05", TextRange(1, 0))
        assertEquals(TextFieldValue("0,5", TextRange(2)),
            normalizeInitialDecimalEdit(prefix, insertDecimalSeparator(prefix)!!))
        assertEquals(TextFieldValue("0,", TextRange(2)),
            normalizeInitialDecimalEdit(TextFieldValue(""), insertDecimalSeparator(TextFieldValue(""))!!))
        assertEquals(TextFieldValue("0.5", TextRange(2)),
            normalizeInitialDecimalEdit(TextFieldValue("5", TextRange(0)), TextFieldValue(".5", TextRange(1))))
    }

    @Test fun selectionAndImeCompositionShiftWithoutBeingDropped() {
        val next = TextFieldValue(".25", TextRange(3, 0), TextRange(0, 3))
        assertEquals(TextFieldValue("0.25", TextRange(4, 1), TextRange(1, 4)),
            normalizeInitialDecimalEdit(TextFieldValue(""), next))
        val signed = TextFieldValue("-.25", TextRange(4, 0), TextRange(1, 4))
        assertEquals(TextFieldValue("-0.25", TextRange(5, 0), TextRange(2, 5)),
            normalizeInitialDecimalEdit(TextFieldValue(""), signed, true))
        val spanning = TextFieldValue("-.", TextRange(2), TextRange(0, 2))
        assertEquals(TextRange(0, 3), normalizeInitialDecimalEdit(TextFieldValue(""), spanning, true).composition)
    }

    @Test fun deletionRestoresZeroAndCaretMovementDoesNotChangeText() {
        assertEquals(TextFieldValue("0.5", TextRange(1)), normalizeInitialDecimalEdit(
            TextFieldValue("0.5", TextRange(1)), TextFieldValue(".5", TextRange(0))))
        assertEquals(TextFieldValue("-0.5", TextRange(2)), normalizeInitialDecimalEdit(
            TextFieldValue("-0.5", TextRange(2)), TextFieldValue("-.5", TextRange(1)), true))
        for ((before, next) in listOf(
            TextFieldValue("2.5", TextRange(3)) to TextFieldValue("2.5", TextRange(1)),
            TextFieldValue("0.5", TextRange(3), TextRange(0, 3)) to
                TextFieldValue("0.5", TextRange(2), TextRange(1, 3)),
            TextFieldValue("2.5", TextRange(2, 3)) to TextFieldValue("2.7", TextRange(3)),
            TextFieldValue("0.", TextRange(2)) to TextFieldValue("0.5", TextRange(3)))) {
            assertSame(next, normalizeInitialDecimalEdit(before, next, true))
        }
    }

    @Test fun displayNormalizationDoesNotCommitPartialOrRelaxDomainPrecision() {
        for (raw in listOf(".", ",", "-.", "-,")) {
            val text = normalizeInitialDecimalEdit(TextFieldValue(""), TextFieldValue(raw), true).text
            assertEquals(DecimalTextState.PARTIAL, DecimalTextPolicy(3, true).state(text))
            assertNull(parsePointMetres(text))
            assertNull(parseElementMilli(text, "м.п."))
            assertNull(PriceCatalogRules.parseSalePrice(text))
        }
        val milli = normalizeInitialDecimalEdit(TextFieldValue(""), TextFieldValue(".007")).text
        assertEquals(7L, parseElementMilli(milli, "м.п."))
        assertNull(parseElementMilli(milli, "шт."))
        assertNull(PriceCatalogRules.parseSalePrice(milli))
        assertEquals(7L, parseElementMilli(".007", "м.п."), "API parsers still accept a leading separator")
    }
}
