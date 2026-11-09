package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.*
import kotlin.test.*

class FrozenRevisionSceneTest {
    @Test fun savedCardPublishesOnlyAfterHistoryAndActionsAreReady() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            val p = TransientQuotePresenter(fixture.measurementUuid)
            p.acceptRead(p.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            val first = repo.saveIndividual(checkNotNull(p.saveRequest(fixture.projectId, "Pinned card")), 2026, 600)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(true)
            val paused = object : SqlDriver by driver {
                override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                    parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                    if (sql.contains("ORDER BY r.versionNumber DESC") && armed.compareAndSet(true, false)) {
                        entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                    }
                    return driver.executeQuery(identifier, sql, mapper, parameters, binders)
                }
            }
            val store = MeasurementElementsStore(PrototypeDatabase(paused), { 1000 }, Dispatchers.Unconfined)
            val scene = ImageComposeScene(700, 1100) { CeilingsTheme(false) {
                SavedIndividualQuoteDialog(store, first.seriesUuid) {}
            } }
            try {
                scene.await { entered.count == 0L }
                assertFalse(scene.text().contains("Pinned card"))
                assertFalse(scene.text().contains("Поточний статус серії"))
                release.countDown()
                scene.await { scene.text().contains("Pinned card") }
                scene.reveal("Погоджено")
                scene.click("Погоджено")
                scene.await { repo.frozenIndividual(first.seriesUuid)?.status == "APPROVED" }
                assertEquals(1, repo.revisions(first.seriesUuid).size)
            } finally {
                release.countDown(); scene.close(); runBlocking { store.closeAndAwaitWrites() }
            }
        }
    }

    @Test fun sharedRevisionPreviewComparesFrozenValuesWithoutCopyAndCancelsWithoutWrites() {
        for (dark in listOf(false, true)) JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val history = ImmutableEstimateRepository(db)
            val old = TransientQuotePresenter(fixture.measurementUuid)
            old.acceptRead(old.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            old.editDiscount("10"); old.confirmDiscount()
            val first = history.saveIndividual(checkNotNull(old.saveRequest(fixture.projectId, "Old frozen")), 2026, 600)
            val base = checkNotNull(history.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
            val store = MeasurementElementsStore(db, { 1000 }, Dispatchers.Unconfined)
            val m = checkNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
            val route = ElementRoute(fixture.projectId, m.id, m.uuid)
            val presenter = store.revisionQuote(base, route)
            var back = false
            val scene = ImageComposeScene(700, 1100) {
                CeilingsTheme(dark) { MeasurementQuoteScreen(presenter, store, route, PaddingValues(16.dp), 0.dp) { back = true } }
            }
            try {
                scene.await { presenter.state.value.amounts != null }
                assertEquals("0", presenter.state.value.discountText)
                assertEquals(12000, presenter.state.value.amounts!!.finalCents)
                assertTrue(scene.text().contains("Нова версія v2"))
                scene.reveal("Порівняти креслення")
                scene.click("Порівняти креслення"); scene.frame()
                assertTrue(scene.text().contains("Збережене креслення v1"))
                scene.click("Приховати порівняння креслень"); scene.frame()
                scene.reveal("Перенести знижку 10% — потім підтвердити")
                assertTrue(scene.text().contains("Було:"))
                assertTrue(scene.text().contains("Зараз:"))
                scene.click("Перенести знижку 10% — потім підтвердити")
                scene.frame()
                assertFalse(presenter.state.value.discountConfirmed)
                assertNull(presenter.state.value.amounts)
                scene.constraints = Constraints.fixed(320, 600); scene.frame()
                scene.scroll(-100000f); scene.frame()
                scene.click("До кошторису")
                assertTrue(back)
                assertEquals(1, history.revisions(first.seriesUuid).size)
                assertEquals(base, history.exactIndividual(first.seriesUuid, first.initialRevisionUuid))
                assertSame(presenter, store.revisionQuote(base, route))
            } finally { scene.close(); runBlocking { store.closeAndAwaitWrites() } }
        }
    }
    private var time = 0L
    private fun ImageComposeScene.frame() { repeat(3) { time += 250_000_000; Snapshot.sendApplyNotifications(); render(time).close(); Thread.sleep(10) } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }
    private fun ImageComposeScene.text() = nodes().joinToString { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { t -> t.text } }
    private fun ImageComposeScene.await(condition: () -> Boolean) { repeat(100) { frame(); if (condition()) return }; fail("Revision preview did not settle") }
    private fun ImageComposeScene.scroll(delta: Float) { assertTrue(nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta) == true) }
    private fun ImageComposeScene.reveal(expectedText: String) { repeat(100) { frame(); if (text().contains(expectedText)) return; scroll(200f) }; fail("Revision comparison was not visible: $expectedText; ${text()}") }
    private fun ImageComposeScene.click(text: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == text } }
        val action = generateSequence(node) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.first()
        assertTrue(action())
    }
}
