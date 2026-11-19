package com.andreyceilings.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.andreyceilings.app.ui.screens.EstimatePrintAdapter
import com.andreyceilings.app.ui.screens.PrintWriteResult
import com.andreyceilings.app.ui.screens.writeEstimateDocumentPdf
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.objects.ObjectsClientsRows
import com.andreyceilings.shared.objects.ObjectsClientsScreen
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
                    val mounted = mutableStateOf(true)
                    var disposed = false
                    compose.setContent {
                        if (mounted.value) CompositionLocalProvider(LocalEstimateDocumentExporter provides EstimateDocumentExporter { pages, _, done ->
                            captured = pages
                            done(null)
                        }) {
                            DisposableEffect(Unit) { onDispose { disposed = true } }
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
                        var closedStream = false
                        assertFailsWith<IOException> {
                            writeEstimateDocumentPdf(pages, listOf(0)) {
                                object : OutputStream() {
                                    override fun write(value: Int) { throw IOException("test destination write failure") }
                                    override fun close() { closedStream = true }
                                }
                            }
                        }
                        assertTrue(closedStream)
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

                    val adapter = compose.runOnIdle {
                        EstimatePrintAdapter(pages).also { mounted.value = false }
                    }
                    compose.runOnIdle { assertTrue(disposed) }
                    assertPrintLifecycle(adapter, output)
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

    @SdkSuppress(minSdkVersion = 35)
    @Test fun selectedRoomsExportOnePdfWithExactFrozenVersions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "consolidated-pdf.db"
        val key = ByteArray(32) { 0x63 }
        val output = File.createTempFile("consolidated-pdf", ".pdf", context.cacheDir)
        context.deleteDatabase(databaseName)
        try {
            createEncryptedPrototypeDriver(context, key, databaseName, enableWalForTesting = true,
                initializePriceCatalog = true).use { driver ->
                val db = PrototypeDatabase(driver)
                val living = MeasurementPricingFixture.seed(db, roomName = "Вітальня")
                val kitchen = MeasurementPricingFixture.seed(db, living.projectId, "Кухня")
                MeasurementPricingFixture.seed(db, living.projectId, "Спальня")
                val group = PriceGroupRepository(db).create("Позиції для тесту зведеного PDF", 110, "шт.")
                val prices = PriceRepository(db)
                val item = prices.createInGroup("consolidated.extra",
                    "Монтаж декоративного освітлення з окремим ізольованим кріпленням", group, 0.0, 111)
                val itemUuid = assertNotNull(prices.find(item)).uuid
                val elements = MeasurementElementRepository(db)
                repeat(40) { index ->
                    elements.createCatalogSelection(living.measurementUuid, itemUuid, ElementQuantity.Exact(1000),
                        120L + index, approval = ElementApproval.APPROVED, businessRole = BusinessRole.ADDITIONAL_WORK)
                }
                elements.createWish(kitchen.measurementUuid, "Монтаж ліхтаря", 560, approval = ElementApproval.APPROVED)
                val history = ImmutableEstimateRepository(db)
                val first = history.saveIndividual(FrozenIndividualEstimateProof.command(db, living), 2026, 600)
                val latest = history.appendIndividual(FrozenRevisionProof.request(db, living, first.seriesUuid), 601)
                assertTrue(history.selectRevision(first.seriesUuid, latest.initialRevisionUuid, 1, 602))
                val partial = history.saveIndividual(FrozenIndividualEstimateProof.command(db, kitchen, partial = true), 2026, 603)
                val additional = (1..8).associate { index ->
                    val name = "Кімната ${index.toString().padStart(2, '0')}"
                    val room = MeasurementPricingFixture.seed(db, living.projectId, name)
                    name to history.saveIndividual(FrozenIndividualEstimateProof.command(db, room, discount = "$index"),
                        2026, 610L + index)
                }
                val projects = ProjectRepository(db)
                val project = assertNotNull(projects.find(living.projectId))
                projects.update(project.id, project.clientId, "Квартира / Центр", project.address, project.status, project.notes, 630)
                val before = history.frozenIndividuals()
                val sequence = db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence
                val objects = ObjectsClientsStore(db) { 700 }
                val store = objects.measurementDetails.elements
                try {
                    var captured: EstimateDocumentPages? = null
                    val mounted = mutableStateOf(true)
                    compose.setContent {
                        if (mounted.value) CompositionLocalProvider(LocalEstimateDocumentExporter provides EstimateDocumentExporter { pages, _, done ->
                            captured = pages
                            done(null)
                        }) {
                            CeilingsTheme(false) {
                                ObjectsClientsScreen(
                                    rows = ObjectsClientsRows(emptyList(), emptyList()),
                                    detailsProjectId = living.projectId, detailsStore = objects,
                                    onOpenProject = {}, onCloseProject = {}, databaseAvailable = true,
                                    onSaveClient = { _, _ -> }, onSaveProject = { _, _ -> },
                                    onDeleteClient = {}, onDeleteProject = {}, onDial = {},
                                    onExternalAction = { _, _ -> }
                                )
                            }
                        }
                    }
                    compose.waitUntil(10000) { compose.onAllNodesWithText("Зведений кошторис").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Зведений кошторис").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithText("Переглянути зведений PDF").fetchSemanticsNodes().isNotEmpty() }
                    val preview = compose.onNodeWithText("Переглянути зведений PDF")
                    preview.assertIsNotEnabled()
                    val list = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
                    val noQuote = hasText("Спальня") and isToggleable()
                    list.performScrollToNode(noQuote)
                    compose.onNode(noQuote).assertIsNotEnabled()
                    fun publicNumber(document: CreatedEstimateSeries) = EstimateNumbering.printable(document.documentNumber)
                    fun waitForPreview(room: String) {
                        val enabled = hasText("Переглянути зведений PDF") and isEnabled()
                        try {
                            compose.waitUntil(60_000) { compose.onAllNodes(enabled).fetchSemanticsNodes().isNotEmpty() }
                        } catch (timeout: ComposeTimeoutException) {
                            val dialog = compose.onAllNodes(isRoot(), useUnmergedTree = true).onLast().printToString()
                            fail("Preview unavailable after choosing $room\n$dialog", timeout)
                        }
                    }
                    fun choose(room: String, document: CreatedEstimateSeries) {
                        list.performScrollToNode(hasText(room))
                        compose.onNode(hasText(room) and isToggleable()).performClick()
                        preview.assertIsNotEnabled()
                        val exact = hasText("${publicNumber(document)} · v1", substring = true) and isSelectable()
                        list.performScrollToNode(exact)
                        compose.onNode(exact).assertIsNotSelected().performClick()
                        compose.onNode(exact).assertIsSelected()
                        waitForPreview(room)
                    }
                    choose("Вітальня", first)
                    choose("Кухня", partial)
                    for ((name, quote) in additional) choose(name, quote)
                    preview.performClick()
                    compose.waitUntil(60_000) { compose.onAllNodesWithText("Зберегти PDF").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Зберегти PDF").performClick()
                    compose.waitUntil(60_000) { compose.runOnIdle { captured != null } }
                    val pages = compose.runOnIdle { assertNotNull(captured) }
                    runBlocking { writeEstimateDocumentPdf(pages, 0 until pages.count) { output.outputStream() } }
                    val text = PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                        assertEquals(pages.count, pdf.pageCount)
                        assertTrue(pdf.pageCount > 11)
                        (0 until pdf.pageCount).joinToString("\n") { index ->
                            pdf.openPage(index).use { page ->
                                assertEquals(595, page.width)
                                assertEquals(842, page.height)
                                page.textContents.joinToString(" ") { it.text }.also { pageText ->
                                    // PdfRenderer can split a single printed heading into separate glyph runs.
                                    val compactText = pageText.filterNot(Char::isWhitespace)
                                    assertTrue("Зведенийкошторис" in compactText, pageText)
                                    if (index == 0) {
                                        assertTrue("Квартира/Центр" in compactText, pageText)
                                        assertFalse(publicNumber(first) in compactText || publicNumber(partial) in compactText, pageText)
                                    }
                                }
                            }
                        }
                    }
                    val compactText = text.filterNot(Char::isWhitespace)
                    assertTrue("${publicNumber(first)}·v1" in compactText, text)
                    assertTrue("${publicNumber(partial)}·v1" in compactText, text)
                    assertTrue("Частковийкошторис" in compactText, text)
                    assertTrue("Монтажліхтаря" in compactText, text)
                    assertTrue("Загаломдооплати" in compactText, text)
                    assertTrue(Regex("Кількість").findAll(compactText).count() >= 3, text)
                    val total = (listOf(first, partial) + additional.values).sumOf {
                        assertNotNull(history.exactIndividual(it.seriesUuid, it.initialRevisionUuid)).frozen.amounts.finalCents
                    }
                    assertTrue(measurementMoney(total).filterNot(Char::isWhitespace) in compactText, text)
                    assertEquals("zvedenyi-koshtorys-Квартира-Центр.pdf", pages.fileName)
                    additional.values.forEach { assertTrue("${publicNumber(it)}·v1" in compactText, text) }
                    assertFalse("${publicNumber(first)}·v2" in compactText, text)
                    assertFalse(first.initialRevisionUuid in compactText, text)
                    assertFalse(first.seriesUuid in compactText, text)
                    assertFalse("Спальня" in compactText, text)
                    compose.onNodeWithText("Назад").performClick()
                    compose.onNodeWithText("Скасувати").performClick()
                    compose.runOnIdle { mounted.value = false }
                    compose.waitForIdle()
                    assertEquals(before, history.frozenIndividuals())
                    assertEquals(sequence, db.estimateHistoryQueries.selectEstimateNumberSettings().executeAsOne().nextSequence)
                } finally {
                    runBlocking { store.closeAndAwaitWrites() }
                }
            }
        } finally {
            key.fill(0)
            output.delete()
            context.deleteDatabase(databaseName)
        }
    }

    private fun assertPrintLifecycle(adapter: EstimatePrintAdapter, output: File) = runBlocking {
        suspend fun write(
            scenario: String,
            signal: CancellationSignal = CancellationSignal(),
            accessMode: Int = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
            finishDuringWrite: Boolean = false,
            cancelDuringWrite: Boolean = false
        ): PrintWriteResult {
            val results = mutableListOf<PrintWriteResult>()
            val (job, fd) = withContext(Dispatchers.Main) {
                val destination = ParcelFileDescriptor.open(output, accessMode)
                val descriptor = destination.fileDescriptor
                val write = adapter.writePages(arrayOf(PageRange(0, 0)), destination, signal) { results.add(it) }
                if (cancelDuringWrite) signal.cancel()
                if (finishDuringWrite) adapter.onFinish()
                write to descriptor
            }
            assertTrue(withTimeoutOrNull(30_000) { job.join(); true } == true,
                "$scenario: print write did not terminate")
            assertFalse(fd.valid(), "$scenario: print destination is still open")
            assertEquals(1, results.size, "$scenario: print write must complete exactly once")
            return results.single()
        }

        try {
            assertEquals(PrintWriteResult.Finished(listOf(PageRange(0, 0))), write("After preview disposal"))
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                assertEquals(1, it.pageCount)
            }
            assertEquals(PrintWriteResult.Cancelled, write("Already cancelled", CancellationSignal().apply { cancel() }))
            assertEquals(PrintWriteResult.Cancelled, write("Cancelled during write", cancelDuringWrite = true))
            assertEquals(PrintWriteResult.Failed, write("Read-only destination", accessMode = ParcelFileDescriptor.MODE_READ_ONLY))
            assertEquals(PrintWriteResult.Cancelled, write("Finished during write", finishDuringWrite = true))
            assertEquals(PrintWriteResult.Cancelled, write("Late request after finish"))
        } finally {
            withContext(Dispatchers.Main) { adapter.onFinish() }
        }
    }

}
