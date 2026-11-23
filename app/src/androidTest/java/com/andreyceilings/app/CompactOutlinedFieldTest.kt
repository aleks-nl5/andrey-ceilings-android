package com.andreyceilings.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.theme.CeilingsTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in compact component contract, not a global Material text-field override. */
class CompactOutlinedFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleLineIs52DpAndMultilineGrowsToPreserveText() {
        var single by mutableStateOf("2,5")
        var notes by mutableStateOf("")
        compose.setContent {
            CeilingsTheme(false) {
                androidx.compose.foundation.layout.Column {
                    CompactOutlinedStringField(single, { single = it }, "Довжина, м",
                        modifier = Modifier.fillMaxWidth().testTag("compact-single"))
                    CompactOutlinedStringField(notes, { notes = it }, "Примітки",
                        modifier = Modifier.fillMaxWidth().testTag("compact-multi"),
                        maxLines = 3)
                }
            }
        }
        val one = compose.onNodeWithTag("compact-single")
        val many = compose.onNodeWithTag("compact-multi")
        val height = 52f * compose.density.density
        assertEquals(height, one.fetchSemanticsNode().size.height.toFloat(), 1f)
        assertEquals(height, many.fetchSemanticsNode().size.height.toFloat(), 1f)
        one.performTextReplacement("4,25")
        one.assertTextContains("4,25")
        many.performTextReplacement("Перший рядок\nДругий рядок\nТретій рядок")
        many.assertTextContains("Третій рядок", substring = true)
        assertTrue(many.fetchSemanticsNode().size.height > one.fetchSemanticsNode().size.height,
            "Three-line notes must expand instead of clipping inside 52 dp")
    }

    @Test fun largeFontCanExpandInsteadOfClipping() {
        var value by mutableStateOf("Довгий вміст")
        compose.setContent {
            CeilingsTheme(true) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    CompactOutlinedStringField(value, { value = it }, "Назва заміру",
                        modifier = Modifier.fillMaxWidth().testTag("compact-large-font"))
                }
            }
        }
        compose.onNodeWithTag("compact-large-font").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("compact-large-font").fetchSemanticsNode().size.height >=
            (52f * compose.density.density).toInt(),
            "Large-font compact field must not shrink below its normal 52 dp contract")
    }
}
