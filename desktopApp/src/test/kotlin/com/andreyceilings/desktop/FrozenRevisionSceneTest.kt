package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
    private fun ImageComposeScene.reveal(expectedText: String) { repeat(100) { frame(); if (text().contains(expectedText)) return; scroll(600f) }; fail("Revision comparison was not visible") }
    private fun ImageComposeScene.click(text: String) {
        val node = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == text } }
        val action = generateSequence(node) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.first()
        assertTrue(action())
    }
}
