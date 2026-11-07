package com.andreyceilings.shared.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*

class NumericEditFilterTest {
    @Test fun pasteFiltersLettersAndRepeatedSeparators() {
        for ((raw, expected) in listOf("12abc" to "12", "1a2,3x" to "12,3", "1.2.3" to "1.23", "1,2.3" to "1,23", "0,,5" to "0,5", ".007" to "0.007")) {
            val result = filterNumericEdit(TextFieldValue(""), TextFieldValue(raw, TextRange(raw.length)))
            assertEquals(expected, result.text, raw)
            assertEquals(TextRange(expected.length), result.selection, raw)
        }
        val signed = filterNumericEdit(TextFieldValue(""), TextFieldValue("-a.5", TextRange(4)), signed = true)
        assertEquals("-0.5", signed.text)
        assertEquals("5", filterNumericEdit(TextFieldValue(""), TextFieldValue("-.5", TextRange(3)), decimal = false).text)
        val existingSign = TextFieldValue("-5", TextRange(0))
        assertEquals(existingSign, filterNumericEdit(existingSign, TextFieldValue("2-5", TextRange(1)), signed = true))
    }

    @Test fun invalidReplacementPreservesSelectionAndDeletionRemainsPossible() {
        val before = TextFieldValue("12.5", TextRange(0, 2))
        assertEquals(before, filterNumericEdit(before, TextFieldValue("abc.5", TextRange(3))))
        assertEquals("3.5", filterNumericEdit(before, TextFieldValue("a3x.5", TextRange(3))).text)
        assertEquals("0.5", filterNumericEdit(before, TextFieldValue(".5", TextRange(0))).text)
        val empty = TextFieldValue("")
        assertEquals(empty, filterNumericEdit(empty, TextFieldValue("abc", TextRange(3))))
        val signedAll = TextFieldValue("-0.5", TextRange(0, 4))
        assertEquals("0.5", filterNumericEdit(signedAll, TextFieldValue("..5", TextRange(3)), signed = true).text)
        assertEquals(signedAll, filterNumericEdit(signedAll, TextFieldValue("abc", TextRange(3)), signed = true))
    }

    @Test fun deletionAndFullReplacementKeepLeadingZeroAndAllowClearing() {
        val deleted = filterNumericEdit(TextFieldValue("0.5", TextRange(1)), TextFieldValue(".5", TextRange(0), TextRange(0, 2)))
        assertEquals(TextFieldValue("0.5", TextRange(1), TextRange(1, 3)), deleted)
        assertEquals(TextFieldValue("0.5", TextRange(3)), filterNumericEdit(
            TextFieldValue("2.5", TextRange(3)), TextFieldValue(".5", TextRange(2))))
        assertEquals(TextFieldValue("-0.5", TextRange(2), TextRange(2, 4)), filterNumericEdit(
            TextFieldValue("-0.5", TextRange(2)), TextFieldValue("-.5", TextRange(1), TextRange(1, 3)), signed = true))
        assertEquals(TextFieldValue("", TextRange(0)), filterNumericEdit(
            TextFieldValue("0.5", TextRange(0, 3)), TextFieldValue("", TextRange(0))))
    }

    @Test fun separatorsSignsAndUnicodeRespectTheUnselectedText() {
        val selectedSeparator = TextFieldValue("12.5", TextRange(2, 3))
        assertEquals("12,5", filterNumericEdit(selectedSeparator, TextFieldValue("12,5", TextRange(3))).text)
        val selectedDigit = TextFieldValue("12.5", TextRange(0, 1))
        assertEquals(selectedDigit, filterNumericEdit(selectedDigit, TextFieldValue(",2.5", TextRange(1))))
        val pasted = "а😀+1e2\t3"
        assertEquals("123", filterNumericEdit(TextFieldValue(""), TextFieldValue(pasted, TextRange(pasted.length))).text)
        assertEquals("12", filterNumericEdit(TextFieldValue(""), TextFieldValue("-1-2", TextRange(4))).text)
        assertEquals("-12", filterNumericEdit(TextFieldValue(""), TextFieldValue("-1-2", TextRange(4)), signed = true).text)
    }

    @Test fun compositionAndCaretMapAcrossDiscardedCharacters() {
        val next = TextFieldValue("1a2,3x", TextRange(5, 1), TextRange(0, 6))
        val result = filterNumericEdit(TextFieldValue(""), next)
        assertEquals("12,3", result.text)
        assertEquals(TextRange(4, 1), result.selection)
        assertEquals(TextRange(0, 4), result.composition)
        val composing = TextFieldValue("-0.5", TextRange(4), TextRange(0, 4))
        val replacement = filterNumericEdit(composing, TextFieldValue("..5", TextRange(3), TextRange(0, 3)), signed = true)
        assertEquals("0.5", replacement.text)
    }

    @Test fun separatorActionMapsCompositionAcrossSelection() {
        val value = TextFieldValue("1234", TextRange(1, 3), TextRange(0, 4))
        val inserted = assertNotNull(insertDecimalSeparator(value))
        assertEquals("1,4", inserted.text)
        assertEquals(TextRange(2), inserted.selection)
        assertEquals(TextRange(0, 3), inserted.composition)
        val atStart = assertNotNull(insertDecimalSeparator(TextFieldValue("12", TextRange(0), TextRange(0, 2))))
        assertEquals(TextRange(1, 3), atStart.composition)
    }
}
