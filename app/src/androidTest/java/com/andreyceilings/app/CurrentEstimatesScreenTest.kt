package com.andreyceilings.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.app.ui.screens.EstimatesContent
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.measurement.measurementMoney
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class CurrentEstimatesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyV1AndMixedLegacyRenderIdenticalCurrentDocumentsAndEmptyStateUsesV1() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "current-v1-list.db"
        val key = ByteArray(32) { 48 }
        context.deleteDatabase(name)
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val history = ImmutableEstimateRepository(db)
        val presenter = TransientQuotePresenter(fixture.measurementUuid)
        presenter.acceptRead(presenter.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
        val saved = history.saveIndividual(assertNotNull(presenter.saveRequest(fixture.projectId, "Current proposal")), 2026, 1000)
        val money = measurementMoney(decodeFrozenIndividualEstimate(history.frozenIndividual(saved.seriesUuid)!!.payload).amounts.finalCents)
        val store = MeasurementElementsStore(db, { 1100 })
        var generation by mutableIntStateOf(0)
        var mounted by mutableStateOf(true)
        try {
            compose.setContent { if (mounted) CeilingsTheme(false) { androidx.compose.runtime.key(generation) { EstimatesContent(store, {}) } } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Current proposal").fetchSemanticsNodes().size == 1 }
            compose.onAllNodesWithText("Збережених кошторисів ще немає").assertCountEquals(0)
            compose.onAllNodesWithText(money).assertCountEquals(1)
            fun currentTexts() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text].map { value -> value.text } }
            val onlyV1 = currentTexts()
            val legacy = EstimateRepository(db)
            fun insertLegacy(title: String) = legacy.createReturningId(fixture.projectId, title, "PVC", "standard", "simple",
                4.0, 3.0, 12.0, 14.0, 9999.0, 0.0, 0.0, 0.0, 9999.0, createdAt = 1001)
            val linked = insertLegacy("Legacy linked test")
            db.businessSchemaQueries.linkLegacyEstimateToSeries(saved.seriesUuid, linked)
            insertLegacy("Legacy independent test")
            compose.runOnIdle { generation++ }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Current proposal").fetchSemanticsNodes().size == 1 }
            assertEquals(onlyV1, currentTexts())
            compose.onAllNodesWithText("Legacy linked test").assertCountEquals(0)
            compose.onAllNodesWithText("Legacy independent test").assertCountEquals(0)
            compose.onAllNodesWithText("Разом за збереженими кошторисами", substring = true).assertCountEquals(0)
            // Legacy-only data does not suppress the current-v1 empty state; nothing is physically removed.
            history.softDeleteSeries(saved.seriesUuid, 1200)
            compose.runOnIdle { generation++ }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Збережених кошторисів ще немає").fetchSemanticsNodes().size == 1 }
            assertEquals(2, legacy.count())
            assertEquals(1, history.revisions(saved.seriesUuid).size)
        } finally {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            runBlocking { store.closeAndAwaitWrites() }
            driver.close(); key.fill(0); context.deleteDatabase(name)
        }
    }
}
