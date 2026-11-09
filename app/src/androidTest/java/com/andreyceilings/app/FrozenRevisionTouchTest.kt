package com.andreyceilings.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class FrozenRevisionTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelThenCreateCurrentRevisionAndOpenExactV1() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "revision-touch-v17.db"
        val key = ByteArray(32) { 47 }
        context.deleteDatabase(name)
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
        history.setWorkflowStatus(first.seriesUuid, 1, EstimateWorkflowStatus.APPROVED, 601)
        val original = history.frozenIndividual(first.seriesUuid)!!.payload
        val store = MeasurementElementsStore(db, { 1000 })
        var mounted by mutableStateOf(true)
        fun lastList() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
        try {
            compose.setContent { if (mounted) CeilingsTheme(false) { SavedIndividualQuoteDialog(store, first.seriesUuid) {} } }
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Створити нову версію") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            lastList().performScrollToNode(hasText("Створити нову версію"))
            compose.onNodeWithText("Створити нову версію").assertIsEnabled().performClick()
            compose.onNodeWithText("До кошторису").performClick()
            assertEquals(1, history.revisions(first.seriesUuid).size)
            assertEquals("APPROVED", history.frozenIndividual(first.seriesUuid)!!.status)
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Створити нову версію") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            lastList().performScrollToNode(hasText("Створити нову версію"))
            compose.onNodeWithText("Створити нову версію").performClick()
            compose.waitUntil(10000) { store.retainedRevision(first.seriesUuid)?.first?.state?.value?.amounts != null }
            lastList().performScrollToNode(hasText("Зберегти нову версію"))
            compose.onNodeWithText("Зберегти нову версію").assertIsEnabled().performClick()
            compose.waitUntil(10000) { history.revisions(first.seriesUuid).size == 2 }
            val latest = history.frozenIndividual(first.seriesUuid)!!
            assertEquals("DRAFT", latest.status)
            assertEquals(12000, latest.totalCents) // No implicit v1 override or discount.
            compose.waitUntil(10000) { compose.onAllNodesWithText("${decodeFrozenIndividualEstimate(latest.payload).publicNumber} · v2").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Назад").onLast().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("До кошторису").fetchSemanticsNodes().isEmpty() }
            // The original selected revision remains v1 after latest advances.
            lastList().performScrollToNode(hasText("${decodeFrozenIndividualEstimate(original).publicNumber} · v1"))
            compose.onNodeWithText("${decodeFrozenIndividualEstimate(original).publicNumber} · v1").assertExists()
            assertEquals(original, db.frozenEstimateQueries.selectFrozenV1(first.seriesUuid).executeAsOne().payload)
            assertEquals(1, history.frozenIndividuals().size)
            assertEquals(2, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
        } finally {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            runBlocking { store.closeAndAwaitWrites() }
            driver.close(); key.fill(0); context.deleteDatabase(name)
        }
    }
}
