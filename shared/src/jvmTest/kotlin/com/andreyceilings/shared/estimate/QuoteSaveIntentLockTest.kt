package com.andreyceilings.shared.estimate

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class QuoteSaveIntentLockTest {
    @Test fun revisionSaveFromGlobalEstimatesLocksHostAndRetainsExactResult() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            repo.setWorkflowStatus(first.seriesUuid, 1, EstimateWorkflowStatus.APPROVED, 601)
            val base = repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!
            val loaded = LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(true)
            val blocked = object : SqlDriver by driver {
                override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                    if (sql.startsWith("UPDATE price_catalog_state SET seedVersion = seedVersion") && armed.compareAndSet(true, false)) {
                        entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                    }
                    return driver.execute(identifier, sql, parameters, binders)
                }
            }
            val store = MeasurementElementsStore(PrototypeDatabase(blocked), { 1000 }, Dispatchers.Unconfined)
            val m = MeasurementRepository(db).findByUuid(fixture.measurementUuid)!!
            val route = ElementRoute(fixture.projectId, m.id, m.uuid)
            val p = store.revisionQuote(base, route)
            p.acceptRead(p.beginRead(), loaded)
            val captured = p.state.value
            val guard = MeasurementCreateNavigationGuard().also { it.bindQuotes(store) }
            val nav = AppNavigationState().selectTab(AppRoute.ESTIMATES)
            try {
                store.submitQuote(p, fixture.projectId)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertTrue(guard.blocksExit(nav))
                assertEquals(nav, guard.transition(nav, nav.selectTab(AppRoute.DASHBOARD)))
                var exited = false
                guard.requestClose { exited = true }
                assertFalse(exited)
                assertSame(p, store.retainedRevision(first.seriesUuid)!!.first)
                assertSame(p, store.revisionQuote(base, route))
                p.editDiscount("50"); p.confirmDiscount()
                p.acceptRead(p.beginRead(), PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE))
                assertEquals(captured, p.state.value)
                release.countDown()
                val result = withTimeout(10000) { p.saveState.first { !it.saving } }
                assertEquals(first.seriesUuid, result.resultSeriesUuid)
                assertEquals(2, repo.revision(assertNotNull(result.resultRevisionUuid))!!.versionNumber)
                assertEquals(EstimateWorkflowStatus.DRAFT, store.savedQuote(first.seriesUuid, result.resultRevisionUuid)!!.status)
                assertFalse(guard.blocksExit(nav))
            } finally { release.countDown(); store.closeAndAwaitWrites() }
        }
    }

    @Test fun blockedPhysicalWriterKeepsVisibleIntentAndRejectsHostExit() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val measurement = MeasurementRepository(db).findByUuid(fixture.measurementUuid)!!
            val loaded = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(true)
            val blocked = object : SqlDriver by driver {
                override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                    if (sql.startsWith("UPDATE price_catalog_state SET seedVersion = seedVersion") && armed.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                    return driver.execute(identifier, sql, parameters, binders)
                }
            }
            val store = MeasurementElementsStore(PrototypeDatabase(blocked), { 1000 }, Dispatchers.Unconfined)
            val guard = MeasurementCreateNavigationGuard()
            val nav = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
                .openMeasurement(fixture.projectId, measurement.id).openMeasurementQuote()
            guard.quoteSession.navigate(nav)
            val p = guard.quoteSession.acquire(fixture.projectId, measurement.id, measurement.uuid, store)
            p.acceptRead(p.beginRead(), loaded)
            p.editTitle("Captured quote")
            p.editDiscount("10"); p.confirmDiscount()
            val captured = p.state.value
            try {
                store.submitQuote(p, fixture.projectId)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertTrue(p.saveState.value.saving)
                p.select("element:${fixture.elementUuid}", false)
                p.editQuantity(fixture.elementUuid, "15"); p.confirmQuantity(fixture.elementUuid)
                p.resetQuantity(fixture.elementUuid)
                p.editDiscount("50"); p.confirmDiscount(); p.acknowledge(true)
                p.editTitle("Different title")
                assertEquals(captured, p.state.value)
                assertEquals("Captured quote", p.saveState.value.title)
                assertTrue(guard.blocksExit(nav))
                assertEquals(nav, guard.transition(nav, nav.back()))
                assertEquals(nav, guard.transition(nav, nav.selectTab(AppRoute.DASHBOARD)))
                var exited = false
                guard.requestClose { exited = true }
                assertFalse(exited)
                // Rotation/invalidation must not replace the submitted financial view while the worker owns Save.
                p.acceptRead(p.beginRead(), PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE))
                assertEquals(captured, p.state.value)
                release.countDown()
                val result = withTimeout(10000) { p.saveState.first { !it.saving } }
                val saved = assertNotNull(store.savedQuote(assertNotNull(result.resultSeriesUuid)))
                assertEquals(captured.amounts, saved.frozen.amounts)
                assertEquals("Captured quote", saved.frozen.title)
                assertEquals(captured, p.state.value)
                assertFalse(guard.blocksExit(nav))
                p.consumeSaved()
                assertEquals(PricingProblemCode.STALE_SOURCE, p.state.value.unavailable)
            } finally {
                release.countDown()
                store.closeAndAwaitWrites()
                guard.quoteSession.clear()
            }
        }
    }
}
