package com.andreyceilings.app

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
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
        try {
            compose.setContent { if (mounted) CeilingsTheme(false) {
                MeasurementQuoteScreen(presenter, store, ElementRoute(fixture.projectId, measurement.id, measurement.uuid),
                    PaddingValues(16.dp), 0.dp, {})
            } }
            compose.waitUntil(10000) { presenter.state.value.amounts != null }
            val titleField = hasSetTextAction() and hasText("Назва кошторису")
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(titleField)
            compose.onNode(titleField).assertTextContains("Room")
                .performTextReplacement("Proposal")
            compose.onNodeWithText("Зберегти кошторис").performScrollTo().performClick()
            compose.waitUntil(10000) { presenter.saveState.value.resultSeriesUuid != null }
            val id = presenter.saveState.value.resultSeriesUuid!!
            val history = ImmutableEstimateRepository(db)
            val before = history.frozenIndividual(id)!!
            assertEquals("Proposal", com.andreyceilings.shared.estimate.decodeFrozenIndividualEstimate(before.payload).title)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
                .performScrollToNode(hasText("Погоджено"))
            compose.onNodeWithText("Погоджено").performClick()
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
