package com.andreyceilings.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
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
        compose.setContent { CeilingsTheme(dark) { Column {
            DecimalInputField(text, { text = it }, "Число", signed = true)
            Text(if (PriceCatalogRules.parseSalePrice(text) == null) "Невалідна ціна" else "Валідна ціна")
        } } }
        val field = compose.onNode(hasSetTextAction())
        // Material3 merges the label into Text; this contract concerns only the editable buffer.
        fun assertInput(expected: String) = field.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; text = "" }
            field.performClick().performTextInput(".")
            assertInput(".").assertIsFocused()
            field.performTextInput("5")
            assertInput(".5").assertIsFocused()
            field.performKeyInput { keyDown(Key.Backspace); keyUp(Key.Backspace) }
            assertInput(".").assertIsFocused()
            field.performTextInput("5")
            compose.runOnIdle { assertEquals(0.5, PriceCatalogRules.parseSalePrice(text)) }
            field.performTextReplacement(".007") // Paste through the real Compose text-input service.
            compose.runOnIdle {
                assertEquals(7L, parseElementMilli(text, "м.п."))
                assertNull(PriceCatalogRules.parseSalePrice(text))
                assertNull(parseElementMilli(text, "шт."))
            }
            field.performTextClearance()
            compose.onNodeWithContentDescription("Вставити десяткову кому").performClick()
            assertInput(",").assertIsFocused()
            field.performTextInput("5")
            assertInput(",5")
            field.performTextReplacement("05")
            field.performTextInputSelection(TextRange(0, 1))
            compose.onNodeWithContentDescription("Вставити десяткову кому").performClick()
            assertInput(",5").assertIsFocused()
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1)))
            field.performTextReplacement("-.")
            assertInput("-.")
            field.performTextInput("5")
            assertInput("-.5").assertIsFocused()
            field.performTextReplacement("..5")
            assertInput("..5")
            compose.runOnIdle { assertNull(parseElementMilli(text, "м.п.")) }
        }
    }
}
