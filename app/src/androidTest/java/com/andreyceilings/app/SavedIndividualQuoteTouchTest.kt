package com.andreyceilings.app

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class SavedIndividualQuoteTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitSaveOpensFrozenV1AndStatusDoesNotReviseMoney() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "saved-individual-touch.db"
        val key = ByteArray(32) { 46 }
        context.deleteDatabase(name)
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val measurement = MeasurementRepository(db).findByUuid(fixture.measurementUuid)!!
        val store = MeasurementElementsStore(db, { 1000 })
        val presenter = store.newQuote(fixture.measurementUuid)
        var mounted by mutableStateOf(true)
        lateinit var dismissTextInput: () -> Unit
        try {
            compose.setContent { if (mounted) CeilingsTheme(false) {
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                SideEffect { dismissTextInput = { focus.clearFocus(force = true); keyboard?.hide() } }
                MeasurementQuoteScreen(presenter, store, ElementRoute(fixture.projectId, measurement.id, measurement.uuid),
                    PaddingValues(16.dp), 0.dp, {})
            } }
            compose.waitUntil(10000) { presenter.state.value.amounts != null }
            val titleField = hasSetTextAction() and hasText("Назва кошторису")
            val quoteList = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            quoteList.performScrollToNode(titleField)
            compose.onNode(titleField).assertTextContains("Room").performTextReplacement("Proposal")
            compose.runOnIdle {
                assertEquals("Proposal", presenter.saveState.value.title)
                dismissTextInput()
            }
            val save = hasText("Зберегти кошторис") and hasClickAction()
            quoteList.performScrollToNode(save)
            compose.onNode(save).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
            compose.runOnIdle {
                val submitted = presenter.saveState.value
                assertTrue(submitted.saving || submitted.resultSeriesUuid != null || submitted.error != null,
                    "The Save touch did not submit the quote. Amount available: ${presenter.state.value.amounts != null}")
            }
            compose.waitUntil(30_000) {
                presenter.saveState.value.let { it.resultSeriesUuid != null || it.error != null }
            }
            val saved = presenter.saveState.value
            val id = assertNotNull(saved.resultSeriesUuid, saved.error ?: "Quote save did not return a series UUID")
            val history = ImmutableEstimateRepository(db)
            val before = history.frozenIndividual(id)!!
            assertEquals("Proposal", com.andreyceilings.shared.estimate.decodeFrozenIndividualEstimate(before.payload).title)
            // Save completion precedes the dialog's asynchronous immutable/history reads.
            // Retry the lookup itself while its list contains only Back + progress; then
            // require a real enabled status action before sending the pointer click.
            compose.waitUntil(10000) {
                runCatching {
                    compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
                        .performScrollToNode(hasText("Погоджено"))
                    compose.onNodeWithText("Погоджено").assertIsEnabled()
                    true
                }.getOrDefault(false)
            }
            compose.onNodeWithText("Погоджено").assertIsEnabled().performClick()
            compose.waitUntil(10000) { history.frozenIndividual(id)?.status == "APPROVED" }
            assertEquals(before.payload, history.frozenIndividual(id)!!.payload)
            assertEquals(1, history.revisions(id).size)
            assertEquals(1, history.frozenIndividuals().size)
            compose.runOnIdle { store.submitQuote(presenter, fixture.projectId) }
            assertEquals(1, history.frozenIndividuals().size)
        } finally {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            runBlocking { store.closeAndAwaitWrites() }
            driver.close(); key.fill(0); context.deleteDatabase(name)
        }
    }
}
