package com.andreyceilings.app

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.MeasurementQuoteContent
import com.andreyceilings.shared.theme.CeilingsTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class TransientQuoteTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualQuoteInputHidesOldTotalsAndSupportsBothThemes() {
        val presenter = TransientQuoteFixture.presenter()
        var dark by mutableStateOf(false)
        compose.setContent {
            CeilingsTheme(dark) {
                val state by presenter.state.collectAsState()
                MeasurementQuoteContent(state, {}, {}, presenter::select, presenter::editQuantity,
                    presenter::confirmQuantity, presenter::resetQuantity, presenter::editDiscount,
                    presenter::confirmDiscount, presenter::acknowledge)
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; presenter.resetQuantity("c") }
            compose.onNodeWithText("Попередній кошторис").assertExists()
            val field = compose.onNode(hasSetTextAction() and hasText("Розрахункова кількість, м²"))
            field.performScrollTo().performTextClearance().performTextInput(".")
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("0.")))
            compose.runOnIdle { assertNull(presenter.state.value.amounts) }
            compose.onNodeWithText("Підтвердити кількість").assertIsNotEnabled()
            field.performTextInput("5")
            compose.onNodeWithText("Підтвердити кількість").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(1650, presenter.state.value.amounts?.subtotalCents)
                assertEquals(12000, presenter.state.value.lines.single { it.elementUuid == "c" }.sourceQuantityMilli)
            }
            compose.onNodeWithText("Зберегти").assertDoesNotExist()
        }
    }
}
