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
    @Test fun confirmedPdfShareCommitsAfterItsSurfaceIsDisposedAndRespectsExactStatus() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            val draft = assertNotNull(repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
            val store = MeasurementElementsStore(db, { 1000 }, Dispatchers.Unconfined)
            val surface = CoroutineScope(Job())
            try {
                val registered = CompletableDeferred<((Boolean) -> Unit) -> Unit>()
                surface.launch {
                    registered.complete { done -> store.markQuoteShareDispatched(draft, done) }
                    awaitCancellation()
                }
                val confirmed = registered.await()
                surface.cancel()
                suspend fun dispatch(): Boolean {
                    val finished = CompletableDeferred<Boolean>()
                    confirmed { finished.complete(it) }
                    return withTimeout(10000) { finished.await() }
                }
                assertTrue(dispatch())
                val sent = assertNotNull(repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
                assertEquals(EstimateWorkflowStatus.SENT, sent.status)
                assertFalse(dispatch())
                assertTrue(repo.setWorkflowStatus(sent.revisionUuid, sent.statusVersion, EstimateWorkflowStatus.APPROVED, 1001))
                val approved = assertNotNull(repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
                assertFalse(dispatch())
                val unchanged = CompletableDeferred<Boolean>()
                store.markQuoteShareDispatched(approved) { unchanged.complete(it) }
                assertTrue(unchanged.await())
                assertEquals(approved, repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
            } finally {
                surface.cancel()
                store.closeAndAwaitWrites()
            }
        }
    }

    @Test fun leavingViaAnotherRouteRetainsBothDraftsUntilExitIsAccepted() {
        val projectId = 17L
        val measurementId = 23L
        val model = MeasurementEditModel(null, MeasurementEditDocument(measurementId, 1,
            MeasurementCreateDraft(projectId, com.andreyceilings.shared.calculation.MeasurementPrecision.ONE_MILLIMETER, "Room")))
        model.accept(MeasurementCreateIntent.Name("Changed room"))
        val nav = AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openCeilingWorkspace(projectId, measurementId)
        val destination = nav.selectTab(AppRoute.DASHBOARD)
        val guard = MeasurementCreateNavigationGuard().also { it.bindEdit(model) }
        var quoteConfirmation: (() -> Unit)? = null
        guard.bindQuoteExit { proceed -> quoteConfirmation = proceed; true }
        var delivered: AppNavigationState? = null
        assertEquals(nav, guard.transition(nav, destination) { delivered = it })
        assertNotNull(quoteConfirmation)
        assertFalse(model.state.value.exitRequested)
        assertNull(delivered)
        assertEquals(nav, guard.transition(nav, destination) { delivered = it })
        assertNotNull(quoteConfirmation).invoke()
        assertTrue(model.state.value.exitRequested)
        model.continueEditing()
        assertTrue(model.state.value.dirty)
        assertNull(delivered)
        assertEquals(nav, guard.transition(nav, destination) { delivered = it })
        assertNotNull(quoteConfirmation).invoke()
        model.discardAndExit()
        assertEquals(destination, delivered)
    }

    @Test fun quoteDriftSeparatesDrawingAndPriceChangesFromProvenance() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val saved = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            val frozen = assertNotNull(repo.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid)).frozen
            val current = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            assertFalse(frozen.hasPricingDrift(current))
            val metadataOnly = current.copy(source = current.source.copy(
                versions = current.source.versions + mapOf("projectUpdatedAt" to "999", "receipt" to "new"),
                elements = current.source.elements.map { it.copy(updatedAt = it.updatedAt + 5, note = "Note") }
            ), catalog = current.catalog.copy(items = current.catalog.items.map { it.copy(updatedAt = it.updatedAt + 1) }))
            assertNotEquals(current.source.signature, metadataOnly.source.signature)
            assertFalse(frozen.hasPricingDrift(metadataOnly))
            val pricedId = frozen.rows.first().line.catalogItemUuid
            val changed = current.catalog.copy(items = current.catalog.items.map {
                if (it.uuid == pricedId) it.copy(salePrice = it.salePrice + 1) else it
            })
            assertTrue(frozen.hasPricingDrift(current.copy(catalog = changed)))
            val measurement = assertNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
            val document = MeasurementEditStore(db) { 800 }.load(fixture.projectId, measurement.id)
            val geometry = checkNotNull(validateMeasurementCreate(document.draft.reduce(
                MeasurementCreateIntent.Dimension("length", "5"))).geometry)
            assertTrue(frozen.hasPricingDrift(current.copy(source = current.source.copy(geometry = geometry))))
        }
    }

    @Test fun revisionSaveFromGlobalEstimatesLocksHostAndRetainsExactResult() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val first = repo.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
            repo.setWorkflowStatus(first.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 601)
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
            val guard = MeasurementCreateNavigationGuard().also { it.bindQuotes(store) }
            val nav = AppNavigationState().selectTab(AppRoute.MEASUREMENTS)
                .openCeilingWorkspace(fixture.projectId, measurement.id)
            val workspace = WorkspaceQuoteSession()
            val p = store.newQuote(measurement.uuid)
            workspace.presenter = p
            workspace.measurementVersion = measurement.updatedAt
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
                workspace.closeDraft(store)
            }
        }
    }
}
