package com.andreyceilings.app

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.*

class QuoteHostRestorationTest {
    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun quoteRouteSurvivesRecreationButFreshViewModelCannotRestoreFinancialSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "quote-host-restore.db"
        val key = ByteArray(32) { 117 }
        val originalOwner = Owner()
        val freshOwner = Owner()
        context.deleteDatabase(name)
        try {
            createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true,
                initializePriceCatalog = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val measurement = assertNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
                val prices = PriceRepository(db)
                val optionalPrice = prices.createInGroup("quote.restore.work", "Обговорювана робота",
                    assertNotNull(prices.find(fixture.priceId)).groupUuid, 2.0, 107,
                    unit = "шт.", quantityMode = QuantityMode.MANUAL)
                val discussing = MeasurementElementRepository(db).createCatalogSelection(fixture.measurementUuid,
                    assertNotNull(prices.find(optionalPrice)).uuid, ElementQuantity.Exact(1000), 108,
                    approval = ElementApproval.DISCUSSING, businessRole = BusinessRole.ADDITIONAL_WORK)
                val store = MeasurementElementsStore(db, { 200 })
                val route = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
                    .openMeasurement(fixture.projectId, measurement.id).openMeasurementQuote()

                // The editor host updates the route before the quote starts a read.
                // Keep those operations ordered without depending on Compose effect scheduling.
                suspend fun acquire(owner: Owner, navigation: AppNavigationState): TransientQuotePresenter =
                    withContext(Dispatchers.Main) {
                        val session = ViewModelProvider(owner)[MeasurementEditorSession::class.java]
                            .navigationGuard.quoteSession
                        session.navigate(navigation)
                        session.acquire(fixture.projectId, measurement.id, fixture.measurementUuid, store)
                    }

                suspend fun readPricing(presenter: TransientQuotePresenter) {
                    val generation = withContext(Dispatchers.Main) { presenter.beginRead() }
                    val pricing = assertIs<PricingReadResult.Loaded>(
                        LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
                    withContext(Dispatchers.Main) { presenter.acceptRead(generation, pricing) }
                    assertFalse(presenter.state.value.loading)
                    assertNotNull(presenter.state.value.amounts)
                }

                try {
                    val living = acquire(originalOwner, route)
                    readPricing(living)
                    withContext(Dispatchers.Main) {
                        living.select("element:${discussing.uuid}", true)
                        living.editQuantity(fixture.elementUuid, "13")
                        living.confirmQuantity(fixture.elementUuid)
                        living.editDiscount("10")
                        living.confirmDiscount()
                    }
                    assertEquals(11880L, living.state.value.amounts?.finalCents)

                    val saved = with(navigationStateSaver) {
                        assertNotNull(SaverScope { it is ArrayList<*> }.save(route))
                    }
                    assertEquals(route.snapshot(), saved)
                    val state = Bundle().apply { putStringArrayList("navigation", saved) }
                    fun restoredRoute() = assertNotNull(
                        navigationStateSaver.restore(assertNotNull(state.getStringArrayList("navigation"))))

                    val recreated = acquire(originalOwner, restoredRoute())
                    assertSame(living, recreated)
                    assertEquals("10", recreated.state.value.discountText)
                    assertEquals("13", recreated.state.value.edits[fixture.elementUuid]?.text)
                    assertTrue("element:${discussing.uuid}" in recreated.state.value.selected)
                    assertEquals(11880L, recreated.state.value.amounts?.finalCents)

                    withContext(Dispatchers.Main) { originalOwner.viewModelStore.clear() }
                    val restored = restoredRoute()
                    assertTrue(restored.measurementQuote)
                    val fresh = acquire(freshOwner, restored)
                    readPricing(fresh)
                    assertNotEquals(living.sessionId, fresh.sessionId)
                    assertNotEquals(living.compositionUuid, fresh.compositionUuid)
                    assertEquals("0", fresh.state.value.discountText)
                    assertTrue(fresh.state.value.edits.isEmpty())
                    assertFalse("element:${discussing.uuid}" in fresh.state.value.selected)
                    assertEquals(12000L, fresh.state.value.amounts?.finalCents)
                    assertNull(living.state.value.amounts)
                } finally {
                    withContext(Dispatchers.Main) {
                        originalOwner.viewModelStore.clear()
                        freshOwner.viewModelStore.clear()
                    }
                    store.closeAndAwaitWrites()
                }
            }
        } finally {
            key.fill(0)
            context.deleteDatabase(name)
        }
    }
}
