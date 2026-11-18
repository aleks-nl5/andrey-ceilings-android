package com.andreyceilings.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.andreyceilings.app.ui.screens.writeEstimateDocumentPdf
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.*

class FrozenEstimateDocumentAndroidTest {
    @get:Rule val compose = createComposeRule()

    @SdkSuppress(minSdkVersion = 35)
    @Test fun restoredExactRevisionRendersEditedUnicodePagesAndPreciseMoney() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "frozen-pdf-exact.db"
        val restoredName = "frozen-pdf-exact-restored.db"
        val key = ByteArray(32) { 0x45 }
        val output = File.createTempFile("frozen-estimate", ".pdf", context.cacheDir)
        context.deleteDatabase(databaseName)
        context.deleteDatabase(restoredName)
        try {
            val (series, revision) = createEncryptedPrototypeDriver(context, key, databaseName,
                enableWalForTesting = true, initializePriceCatalog = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val fixture = MeasurementPricingFixture.seed(db)
                val group = PriceGroupRepository(db).create("Додаткові роботи для PDF", 110, "шт.")
                val prices = PriceRepository(db)
                val item = prices.createInGroup("pdf.extra", "Монтаж декоративного освітлення з окремим кріпленням",
                    group, 0.0, 111)
                val itemUuid = assertNotNull(prices.find(item)).uuid
                val elements = MeasurementElementRepository(db)
                repeat(40) { index ->
                    elements.createCatalogSelection(fixture.measurementUuid,
                        itemUuid, ElementQuantity.Exact(1000), 120L + index,
                        approval = ElementApproval.APPROVED, businessRole = BusinessRole.ADDITIONAL_WORK)
                }
                val history = ImmutableEstimateRepository(db)
                val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, fixture), 2026, 600)
                assertTrue(history.selectRevision(first.seriesUuid, first.initialRevisionUuid, 1, 601))
                val measurement = assertNotNull(MeasurementRepository(db).findByUuid(fixture.measurementUuid))
                val edit = MeasurementEditStore(db) { 602 }
                val original = edit.load(fixture.projectId, measurement.id)
                edit.update(original, original.draft.reduce(MeasurementCreateIntent.Dimension("length", "4.12"))
                    .reduce(MeasurementCreateIntent.Dimension("width", "3.34")))
                val price = PriceRepository(db)
                price.updateSalePrice(assertNotNull(price.find(fixture.priceId)), 100.0, 603)
                val second = history.appendIndividual(FrozenRevisionProof.request(db, fixture, first.seriesUuid), 604)
                price.updateSalePrice(assertNotNull(price.find(fixture.priceId)), 200.0, 605)
                history.appendIndividual(FrozenRevisionProof.request(db, fixture, first.seriesUuid), 606)
                val backup = context.getDatabasePath(restoredName).absolutePath.replace("'", "''")
                driver.execute(null, "VACUUM INTO '$backup'", 0)
                first.seriesUuid to second.initialRevisionUuid
            }

            createEncryptedPrototypeDriver(context, key, restoredName, enableWalForTesting = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val history = ImmutableEstimateRepository(db)
                val before = history.individualHistory(series)
                val store = MeasurementElementsStore(db, { 700 })
                try {
                    var captured: EstimateDocumentPages? = null
                    compose.setContent {
                        CompositionLocalProvider(LocalEstimateDocumentExporter provides EstimateDocumentExporter { pages, _, done ->
                            captured = pages
                            done(null)
                        }) {
                            CeilingsTheme(false) { SavedIndividualQuoteDialog(store, series, revision) {} }
                        }
                    }
                    compose.waitUntil(10000) { compose.onAllNodesWithText("Створити PDF").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Створити PDF").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithText("Оформлення").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Оформлення").performClick()
                    val note = hasText("Примітки") and hasSetTextAction()
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
                        .performScrollToNode(note)
                    val paragraphs = (1..100).joinToString("\n") { "Узгоджений текст, рядок $it" }
                    compose.onNode(note).performTextInput(paragraphs)
                    compose.onNodeWithText("До перегляду").performClick()
                    compose.onNodeWithText("Зберегти PDF").performClick()
                    val pages = compose.runOnIdle { assertNotNull(captured) }
                    assertTrue(pages.count > 1)
                    assertTrue(pages.fileName.endsWith("-v2.pdf"))
                    runBlocking {
                        var openedStream = false
                        assertFailsWith<CancellationException> {
                            writeEstimateDocumentPdf(pages, 0 until pages.count, { true }) {
                                openedStream = true
                                output.outputStream()
                            }
                        }
                        assertFalse(openedStream)
                        assertFailsWith<IOException> {
                            writeEstimateDocumentPdf(pages, listOf(0)) { throw IOException("test write failure") }
                        }
                        writeEstimateDocumentPdf(pages, 0 until pages.count) { output.outputStream() }
                    }

                    val text = PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                        assertEquals(pages.count, pdf.pageCount)
                        (0 until pdf.pageCount).joinToString("\n") { index ->
                            pdf.openPage(index).use { page ->
                                assertEquals(595, page.width)
                                assertEquals(842, page.height)
                                page.textContents.joinToString(" ") { it.text }
                            }
                        }
                    }
                    assertTrue(text.contains("Кошторис робіт"), text)
                    assertTrue(text.contains("13,7608"), text)
                    assertTrue(text.contains("13,761"), text)
                    assertTrue(text.contains("1376,10"), text)
                    assertTrue(Regex("Кількість").findAll(text).count() > 1, text)
                    assertTrue(text.contains("Узгоджений текст, рядок 100"), text)
                    assertFalse(text.contains("2752,20"), text)
                    assertFalse(text.contains(revision), text)
                    assertEquals(before, history.individualHistory(series))
                } finally {
                    runBlocking { store.closeAndAwaitWrites() }
                }
            }
        } finally {
            key.fill(0)
            output.delete()
            context.deleteDatabase(databaseName)
            context.deleteDatabase(restoredName)
        }
    }
}
