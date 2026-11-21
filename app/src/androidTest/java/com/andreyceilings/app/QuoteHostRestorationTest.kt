package com.andreyceilings.app

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.prices.QuantityMode
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Exercises the production Android Saver + ViewModel owner + real quote screen/SQLCipher reader.
 * Registry recreation retains the live ViewModel; a new process has a fresh ViewModelStore.
 */
@OptIn(ExperimentalTestApi::class)
class QuoteHostRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Owner : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    @Test fun quoteRouteSurvivesRecreationButFreshViewModelCannotRestoreFinancialSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name = "quote-host-restore.db"
        val key = ByteArray(32) { 117 }
        context.deleteDatabase(name)
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val measurement = MeasurementRepository(db).findByUuid(fixture.measurementUuid)!!
        val prices = PriceRepository(db)
        val optionalPrice = prices.createInGroup("quote.restore.work", "Обговорювана робота",
            prices.find(fixture.priceId)!!.groupUuid, 2.0, 107, unit = "шт.", quantityMode = QuantityMode.MANUAL)
        val discussing = MeasurementElementRepository(db).createCatalogSelection(fixture.measurementUuid,
            prices.find(optionalPrice)!!.uuid, ElementQuantity.Exact(1000), 108,
            approval = ElementApproval.DISCUSSING, businessRole = BusinessRole.ADDITIONAL_WORK)
        val store = MeasurementElementsStore(db, { 200 })
        var owner by mutableStateOf(Owner())
        var mounted by mutableStateOf(true)
        // Read from the instrumentation thread and written by Compose's SideEffect.
        // Snapshot-backed references avoid cross-thread visibility races.
        var current by mutableStateOf<TransientQuotePresenter?>(null)
        var currentNavigation by mutableStateOf<AppNavigationState?>(null)
        val initial = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
            .openMeasurement(fixture.projectId, measurement.id).openMeasurementQuote()
        val restoration = StateRestorationTester(compose)
        // Pricing begins as an asynchronous SQLCipher read. The previous 10s wall-clock
        // limit occasionally expired on a loaded AVD without a test assertion failure.
        // Give a bounded startup allowance, but fail promptly for invalid/failed pricing.
        fun awaitQuoteReady(phase: String, previous: TransientQuotePresenter? = null,
            requireQuoteRoute: Boolean = false) {
            try {
                compose.waitUntil(30_000) {
                    val active = current
                    val snapshot = active?.state?.value
                    (previous == null || active !== previous) &&
                        (!requireQuoteRoute || currentNavigation?.measurementQuote == true) &&
                        (snapshot?.amounts != null || snapshot?.unavailable != null ||
                            (snapshot != null && !snapshot.loading))
                }
            } catch (failure: ComposeTimeoutException) {
                val snapshot = current?.state?.value
                throw AssertionError(
                    "$phase: pricing did not settle within 30s; presenter=${current != null}, " +
                        "newPresenter=${previous == null || current !== previous}, " +
                        "quoteRoute=${currentNavigation?.measurementQuote}, loading=${snapshot?.loading}, " +
                        "unavailable=${snapshot?.unavailable}, source=${snapshot?.source != null}, " +
                        "preview=${snapshot?.preview != null}, amounts=${snapshot?.amounts != null}, " +
                        "lines=${snapshot?.lines?.size}", failure)
            }
            compose.runOnIdle {
                val state = checkNotNull(current) { "$phase: presenter was never acquired" }.state.value
                assertFalse(state.loading, "$phase: pricing still loading")
                assertNull(state.unavailable, "$phase: pricing returned unavailable")
                assertNotNull(state.amounts,
                    "$phase: calculation invalid; source=${state.source != null}, " +
                        "lines=${state.lines.size}, fatal=${state.preview?.fatalProblems}, " +
                        "mandatory=${state.preview?.mandatoryProblems}")
            }
        }
        try {
            restoration.setContent {
                if (mounted) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    val session: MeasurementEditorSession = viewModel()
                    var navigation by rememberSaveable(stateSaver = navigationStateSaver) { mutableStateOf(initial) }
                    SideEffect {
                        session.navigationGuard.quoteSession.navigate(navigation)
                        currentNavigation = navigation
                    }
                    CeilingsTheme(false) {
                        if (navigation.measurementQuote) {
                            val presenter = remember(session) { session.navigationGuard.quoteSession.acquire(
                                fixture.projectId, measurement.id, fixture.measurementUuid, store) }
                            SideEffect { current = presenter }
                            MeasurementQuoteScreen(presenter, store,
                                ElementRoute(fixture.projectId, measurement.id, fixture.measurementUuid),
                                PaddingValues(16.dp), 0.dp, { navigation = navigation.back() })
                        } else Text("Деталі заміру")
                    }
                }
            }
            awaitQuoteReady("initial quote")
            val living = compose.runOnIdle { checkNotNull(current).also {
                it.select("element:${discussing.uuid}", true)
                it.editQuantity(fixture.elementUuid, "13"); it.confirmQuantity(fixture.elementUuid)
                it.editDiscount("10"); it.confirmDiscount()
                assertEquals(11880, it.state.value.amounts?.finalCents)
                assertEquals(initial.snapshot(), currentNavigation!!.snapshot())
            } }

            // Same Android ViewModelStore models configuration recreation, not process death.
            restoration.emulateSavedInstanceStateRestore()
            awaitQuoteReady("configuration restore", requireQuoteRoute = true)
            compose.onNodeWithText("Попередній кошторис").assertExists()
            compose.runOnIdle {
                assertSame(living, current)
                assertEquals("10", current!!.state.value.discountText)
                assertEquals("13", current!!.state.value.edits[fixture.elementUuid]?.text)
                assertTrue("element:${discussing.uuid}" in current!!.state.value.selected)
                assertEquals(11880, current!!.state.value.amounts?.finalCents)
            }

            // Financial state is absent from the registry. Replacing the process's ViewModelStore
            // and restoring the same route starts a new composition with the original source.
            compose.runOnIdle { owner.viewModelStore.clear(); owner = Owner() }
            restoration.emulateSavedInstanceStateRestore()
            awaitQuoteReady("new ViewModelStore", previous = living, requireQuoteRoute = true)
            compose.onNodeWithText("Попередній кошторис").assertExists()
            compose.runOnIdle {
                assertTrue(currentNavigation!!.measurementQuote)
                assertNotEquals(living.sessionId, current!!.sessionId)
                assertNotEquals(living.compositionUuid, current!!.compositionUuid)
                assertEquals("0", current!!.state.value.discountText)
                assertTrue(current!!.state.value.edits.isEmpty())
                assertFalse("element:${discussing.uuid}" in current!!.state.value.selected)
                assertEquals(12000, current!!.state.value.amounts?.finalCents)
                assertNull(living.state.value.amounts)
            }
        } finally {
            compose.runOnIdle { mounted = false; owner.viewModelStore.clear() }
            compose.waitForIdle()
            runBlocking { store.closeAndAwaitWrites() }
            driver.close(); key.fill(0); context.deleteDatabase(name)
        }
    }
}
