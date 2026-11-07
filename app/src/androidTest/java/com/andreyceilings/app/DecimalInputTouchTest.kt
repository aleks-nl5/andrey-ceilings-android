package com.andreyceilings.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.measurement.parseElementMilli
import com.andreyceilings.shared.prices.PriceCatalogRules
import com.andreyceilings.shared.theme.CeilingsTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class DecimalInputTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leadingSeparatorTypingPasteBackspaceAndCaretSurviveValidationAndTheme() {
        var text by mutableStateOf("")
        var dark by mutableStateOf(false)
        var allowDecimal by mutableStateOf(true)
        compose.setContent { CeilingsTheme(dark) { Column {
            DecimalInputField(text, { text = it }, "Число", signed = true, allowDecimalSeparator = allowDecimal)
            val valid = if (allowDecimal) PriceCatalogRules.parseSalePrice(text) != null else parseElementMilli(text, "шт.") != null
            Button(onClick = {}, enabled = valid) { Text("Зберегти") }
        } } }
        val field = compose.onNode(hasSetTextAction())
        // Material3 merges the label into Text; this contract concerns only the editable buffer.
        fun assertInput(expected: String) = field.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; text = "" }
            field.performClick().performTextInput(".")
            assertInput("0.").assertIsFocused()
            compose.onNodeWithText("Зберегти").assertIsNotEnabled()
            field.performTextInput("5")
            assertInput("0.5").assertIsFocused()
            compose.onNodeWithText("Зберегти").assertIsEnabled()
            field.performKeyInput { keyDown(Key.Backspace); keyUp(Key.Backspace) }
            assertInput("0.").assertIsFocused()
            compose.onNodeWithText("Зберегти").assertIsNotEnabled()
            field.performTextInput("5")
            compose.runOnIdle { assertEquals(0.5, PriceCatalogRules.parseSalePrice(text)) }
            field.performTextReplacement(".007") // Paste through the real Compose text-input service.
            assertInput("0.007").assertIsFocused()
            compose.onNodeWithText("Зберегти").assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(7L, parseElementMilli(text, "м.п."))
                assertNull(PriceCatalogRules.parseSalePrice(text))
                assertNull(parseElementMilli(text, "шт."))
            }
            field.performTextClearance()
            field.performTextInput(",")
            assertInput("0,").assertIsFocused()
            field.performTextInput("5")
            assertInput("0,5")
            field.performTextClearance()
            compose.onNodeWithContentDescription("Вставити десяткову кому").performClick()
            assertInput("0,").assertIsFocused()
            field.performTextInput("5")
            assertInput("0,5")
            field.performTextReplacement("05")
            field.performTextInputSelection(TextRange(0, 1))
            compose.onNodeWithContentDescription("Вставити десяткову кому").performClick()
            assertInput("0,5").assertIsFocused()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2)))
            field.performTextClearance()
            field.performTextInput("-")
            field.performTextInput(".")
            assertInput("-0.")
            field.performTextInput("5")
            assertInput("-0.5").assertIsFocused()
            field.performTextReplacement("..5")
            assertInput("0.5")
            compose.runOnIdle { assertEquals(500L, parseElementMilli(text, "м.п.")) }
            field.performTextReplacement("2.5")
            assertInput("2.5").assertIsFocused()
            field.performTextReplacement("0")
            field.performTextInput(".0.0.5")
            assertInput("0.005").assertIsFocused()
            field.performTextReplacement("1a2,3x")
            assertInput("12,3").assertIsFocused()
            field.performTextReplacement("12.5")
            field.performTextInputSelection(TextRange(0, 2))
            field.performTextInput("abc")
            assertInput("12.5").assertIsFocused()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 2)))
        }
        compose.runOnIdle { allowDecimal = false; text = "" }
        compose.onNodeWithContentDescription("Вставити десяткову кому").assertDoesNotExist()
        field.performTextInput(".5")
        assertInput("5")
        compose.onNodeWithText("Зберегти").assertIsEnabled()
    }
}
