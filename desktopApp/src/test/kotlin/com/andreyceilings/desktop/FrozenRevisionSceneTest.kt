package com.andreyceilings.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
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
    private val sceneDispatcher = DesktopSceneDispatcher()

    @Test fun roundedAreaIsExplicitInLiveAndFrozenQuoteWithoutChangingDrawing() {
        for (dark in listOf(false, true)) JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver); val fixture = MeasurementPricingFixture.seed(db)
            val measurement = MeasurementRepository(db).findByUuid(fixture.measurementUuid)!!
            val edit = MeasurementEditStore(db) { 603 }
            val original = edit.load(fixture.projectId, measurement.id)
            edit.update(original, original.draft.reduce(MeasurementCreateIntent.Dimension("length", "4.12"))
                .reduce(MeasurementCreateIntent.Dimension("width", "3.34")))
            val drawing = MeasurementRepository(db).find(measurement.id)!!.drawingJson
            val prices = PriceRepository(db); prices.updateSalePrice(prices.find(fixture.priceId)!!, 100.0, 604)
            val repo = ImmutableEstimateRepository(db)
            val store = MeasurementElementsStore(db, { 1000 }, Dispatchers.Unconfined)
            val presenter = store.newQuote(fixture.measurementUuid)
            lateinit var command: IndividualEstimateSaveRequest
            try {
                val live = ImageComposeScene(700, 1100, coroutineContext = sceneDispatcher) { CeilingsTheme(dark) {
                    MeasurementQuoteScreen(presenter, store, ElementRoute(fixture.projectId, measurement.id, measurement.uuid), PaddingValues(16.dp), 0.dp) {}
                } }
                try {
                    live.await { presenter.state.value.amounts != null }
                    live.reveal("Розрахункова площа округлена до 0,001 м²")
                    assertTrue(live.text().contains("Площа заміру:"))
                    assertEquals(137610, presenter.state.value.amounts!!.finalCents)
                    assertTrue(repo.frozenIndividuals().isEmpty())
                    command = checkNotNull(presenter.saveRequest(fixture.projectId, "Rounded area"))
                } finally { live.close() }
                val saved = repo.saveIndividual(command, 2026, 1000)
                val frozen = repo.exactIndividual(saved.seriesUuid, saved.initialRevisionUuid)!!
                assertEquals(3, frozen.frozen.formatVersion)
                assertEquals(drawing, frozen.frozen.source.drawingJson)
                var cardMounted by mutableStateOf(true)
                val card = ImageComposeScene(700, 1100, coroutineContext = sceneDispatcher) { CeilingsTheme(dark) {
                    if (cardMounted) SavedIndividualQuoteDialog(store, saved.seriesUuid, saved.initialRevisionUuid) {
                        cardMounted = false
                    }
                } }
                try {
                    card.await { card.text().contains("Статус пропозиції") }
                    card.reveal("Розрахункова площа округлена до 0,001 м²")
                    assertTrue(card.text().contains("Площа заміру:"))
                    assertEquals(137610, frozen.frozen.amounts.finalCents)
                    card.scroll(-100000f)
                    card.reveal("Створити PDF")
                    card.click("Створити PDF")
                    card.await { card.pageText().contains("1376,10") }
                    val pdfText = card.pageText()
                    assertTrue(pdfText.contains("13,7608"), pdfText)
                    assertTrue(pdfText.contains("13,761"), pdfText)
                    assertTrue(pdfText.contains("Сторінка 1 /"), pdfText)
                    assertFalse(card.text().contains("Зберегти PDF"))
                    card.click("Оформлення")
                    card.await { card.text().contains("Оформлення PDF") }
                    card.click("До перегляду")
                    card.await { card.pageText().contains("1376,10") }
                    card.click("Назад", layerTitle = "Попередній перегляд PDF")
                    card.await { !card.text().contains("Попередній перегляд PDF") && card.text().contains("Створити PDF") }
                    card.click("Назад")
                    card.await { !cardMounted && !card.text().contains("Створити PDF") }
                    assertEquals(drawing, MeasurementRepository(db).find(measurement.id)!!.drawingJson)
                } finally {
                    card.close()
                }
            } finally { runBlocking { store.closeAndAwaitWrites() } }
        }
    }

    @Test fun conflictingChoiceKeepsOtherSelectionAndShowsPersistentExplanation() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            fun presenter(base: SavedIndividualQuote? = null) = TransientQuotePresenter(fixture.measurementUuid, revisionBase = base).also {
                it.acceptRead(it.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            }
            val first = repo.saveIndividual(checkNotNull(presenter().saveRequest(fixture.projectId, "Choice card")), 2026, 600)
            val draft = presenter(repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!)
            val second = repo.appendIndividual(AppendIndividualRevisionRequest(checkNotNull(draft.revisionTarget),
                checkNotNull(draft.saveRequest(fixture.projectId, "Choice card"))), 601)
            val original = repo.frozenIndividual(first.seriesUuid)!!.payload
            val store = MeasurementElementsStore(db, { 1000 }, Dispatchers.Unconfined)
            val scene = ImageComposeScene(700, 1100, coroutineContext = sceneDispatcher) { CeilingsTheme(false) {
                SavedIndividualQuoteDialog(store, first.seriesUuid, first.initialRevisionUuid) {}
            } }
            try {
                scene.await { scene.text().contains("Статус пропозиції") }
                scene.reveal("Обрати для роботи"); scene.click("Обрати для роботи"); scene.frame()
                assertTrue(repo.selectRevision(first.seriesUuid, second.initialRevisionUuid, 1, 700))
                scene.click("Обрати")
                scene.await { scene.text().contains("Вибір уже змінено") }
                repeat(3) { scene.frame() }
                assertTrue(scene.text().contains("Вибір уже змінено"))
                assertEquals(second.initialRevisionUuid, repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.selectedRevisionUuid)
                assertEquals(2, repo.exactIndividual(first.seriesUuid, first.initialRevisionUuid)!!.choiceVersion)
                assertTrue(repo.individualHistory(first.seriesUuid).all { it.status == EstimateWorkflowStatus.DRAFT })
                assertEquals(original, repo.frozenIndividual(first.seriesUuid)!!.payload)
            } finally { scene.close(); runBlocking { store.closeAndAwaitWrites() } }
        }
    }

    @Test fun allEntryContextsExposeIndependentAlternativesAndChosenMarker() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val fixture = MeasurementPricingFixture.seed(db)
            val repo = ImmutableEstimateRepository(db)
            fun presenter(base: SavedIndividualQuote? = null) = TransientQuotePresenter(fixture.measurementUuid, revisionBase = base).also {
                it.acceptRead(it.beginRead(), LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
            }
            val first = repo.saveIndividual(checkNotNull(presenter().saveRequest(fixture.projectId, "Alternative")), 2026, 600)
            assertTrue(repo.setWorkflowStatus(first.initialRevisionUuid, 1, EstimateWorkflowStatus.APPROVED, 601))
            assertTrue(repo.selectRevision(first.seriesUuid, first.initialRevisionUuid, 1, 602))
            repeat(2) { index ->
                val latest = repo.frozenIndividual(first.seriesUuid)!!
                val draft = presenter(repo.exactIndividual(first.seriesUuid, latest.revisionUuid)!!)
                repo.appendIndividual(AppendIndividualRevisionRequest(checkNotNull(draft.revisionTarget),
                    checkNotNull(draft.saveRequest(fixture.projectId, "Alternative"))), 603L + index)
            }
            for ((project, measurement) in listOf(null to null, fixture.projectId to null, fixture.projectId to fixture.measurementUuid)) {
                val store = MeasurementElementsStore(db, { 1000 }, Dispatchers.Unconfined)
                val scene = ImageComposeScene(700, 1100, coroutineContext = sceneDispatcher) { CeilingsTheme(false) {
                    SavedIndividualQuoteList(store, project, measurement)
                } }
                try {
                    scene.await { scene.text().contains("варіантів: 3") }
                    val text = scene.text()
                    assertTrue(text.contains("v1 · Погоджено"), text)
                    assertTrue(text.contains("v2 · Чернетка"), text)
                    assertTrue(text.contains("v3 · Чернетка"), text)
                    assertTrue(text.contains("Обрано для роботи"), text)
                } finally { scene.close(); runBlocking { store.closeAndAwaitWrites() } }
            }
        }
    }

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
            val scene = ImageComposeScene(700, 1100, coroutineContext = sceneDispatcher) { CeilingsTheme(false) {
                SavedIndividualQuoteDialog(store, first.seriesUuid) {}
            } }
            try {
                scene.await { entered.count == 0L }
                assertFalse(scene.text().contains("Pinned card"))
                assertFalse(scene.text().contains("Статус пропозиції"))
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
            val scene = ImageComposeScene(700, 1100, coroutineContext = sceneDispatcher) {
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
    private fun ImageComposeScene.frame() {
        repeat(3) {
            sceneDispatcher.render(this)
            Thread.sleep(10)
        }
    }

    private fun ImageComposeScene.nodes(layerTitle: String? = null): List<SemanticsNode> {
        fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        val layers = semanticsOwners.map { flatten(it.rootSemanticsNode) }
        return if (layerTitle == null) layers.flatten() else layers.single { layer ->
            layer.any { node -> node.hasText(layerTitle) }
        }
    }

    private fun SemanticsNode.hasText(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    private fun ImageComposeScene.text() = nodes().joinToString { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { t -> t.text } }
    private fun ImageComposeScene.pageText() =
        nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.joinToString("\n")
    private fun ImageComposeScene.await(condition: () -> Boolean) { repeat(100) { frame(); if (condition()) return }; fail("Revision preview did not settle") }
    private fun ImageComposeScene.scroll(delta: Float) { assertTrue(nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta) == true) }
    private fun ImageComposeScene.reveal(expectedText: String) { repeat(100) { frame(); if (text().contains(expectedText)) return; scroll(200f) }; fail("Revision comparison was not visible: $expectedText; ${text()}; scroll=${nodes().filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.map { "${it.boundsInRoot} ${it.config}" }}") }
    private fun ImageComposeScene.click(text: String, layerTitle: String? = null) {
        val node = nodes(layerTitle).first { it.hasText(text) }
        val action = generateSequence(node) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.first()
        assertTrue(action())
    }
}
