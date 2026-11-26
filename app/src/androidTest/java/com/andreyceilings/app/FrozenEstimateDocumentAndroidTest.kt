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
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.andreyceilings.shared.measurement.*
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
                    compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Перегляд PDF").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription("Перегляд PDF").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithText("Оформлення").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Оформлення").performClick()
                    val note = hasText("Примітки") and hasSetTextAction()
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
                        .performScrollToNode(note)
                    val paragraphs = (1..100).joinToString("\n") { "Узгоджений текст, рядок $it" }
                    compose.onNode(note).performTextInput(paragraphs)
                    compose.onNodeWithContentDescription("До перегляду").performClick()
                    compose.onNodeWithContentDescription("Зберегти PDF").performClick()
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

    // Instrumentation is a separate Gradle module; exercise the real internal CMP
    // writer and renderer without widening the production API for test callers.
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
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
                val sequence = nextIndividualSequence(db)
                val repository = ConsolidatedEstimateRepository(db)
                val discount = ConsolidatedDiscount(QuoteDiscountMode.FIXED, 101)
                fun save(selection: List<CreatedEstimateSeries>, head: String? = null): ConsolidatedRevisionIdentity {
                    val refs = selection.map {
                        val exact = assertNotNull(history.exactIndividual(it.seriesUuid, it.initialRevisionUuid))
                        ConsolidatedDocumentRef(exact.frozen.source.measurementUuid, exact.seriesUuid,
                            exact.revisionUuid, exact.frozen.consolidatedSourceProof())
                    }
                    return repository.save(ConsolidatedSave(newRecordUuid(), project.id, project.uuid, head, head,
                        ConsolidatedDraft("Зведений документ", discount, refs)),
                        kotlinx.datetime.LocalDate(2026, 11, 26), if (head == null) 700 else 701)
                }
                // Persist both versions with the real v24 writer. Keep PDF verification
                // independent of project navigation and editor widget labels.
                val shortQuotes = additional.values.take(2)
                val fullQuotes = listOf(first, partial) + additional.values
                val v1 = save(shortQuotes)
                val v2 = save(fullQuotes, v1.revisionUuid)
                assertEquals(v1.seriesUuid, v2.seriesUuid)
                assertEquals(2L, v2.version)
                val small = assertNotNull(repository.read(v1.revisionUuid))
                val full = assertNotNull(repository.read(v2.revisionUuid))
                assertEquals(small.frozen.publicNumber, full.frozen.publicNumber)
                assertEquals(fullQuotes.map { it.initialRevisionUuid }, full.frozen.members.map { it.ref.revisionUuid })
                val total = fullQuotes.sumOf {
                    assertNotNull(history.exactIndividual(it.seriesUuid, it.initialRevisionUuid)).frozen.amounts.finalCents
                } - 101L
                assertEquals(total, full.frozen.amounts.finalCents)
                projects.update(project.id, project.clientId, "Перейменований об'єкт", project.address,
                    project.status, project.notes, 710)
                prices.updateSalePrice(assertNotNull(prices.find(living.priceId)), 999.0, 711)
                val documents = listOf(v1, v2).map {
                    assertNotNull(repository.read(it.revisionUuid)).document()
                }
                val captured = java.util.concurrent.atomic.AtomicReference<List<EstimateDocumentPages>>()
                compose.setContent {
                    CeilingsTheme(false) {
                        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                        val columns = documentMoneyColumnLabels()
                        val labels = ConsolidatedPdfLabels("Зведений кошторис", "%1\$s · v%2\$d",
                            "Об’єкт · %1\$s", "Адреса · %1\$s", "Сума кошторисів · %1\$s",
                            "До оплати · %1\$s", "Додаткова знижка")
                        val options = DocumentPresentationOptions(EstimateDocumentSection.entries.filter { it.editable })
                            .copy(moneyColumnLabels = columns, fixedDiscountLabel = "Фіксована знижка")
                        val pages = androidx.compose.runtime.remember(measurer, columns) {
                            documents.map { layoutConsolidatedEstimateDocument(it, options, measurer, labels) }
                        }
                        androidx.compose.runtime.SideEffect { captured.set(pages) }
                    }
                }
                compose.waitUntil(60_000) { captured.get() != null }
                val rendered = assertNotNull(captured.get())
                fun exportPages(pages: EstimateDocumentPages): List<String> {
                    assertTrue(pages.exportable)
                    runBlocking { writeEstimateDocumentPdf(pages, 0 until pages.count) { output.outputStream() } }
                    return PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                        assertEquals(pages.count, pdf.pageCount)
                        List(pdf.pageCount) { index ->
                            pdf.openPage(index).use { page ->
                                assertEquals(595, page.width)
                                assertEquals(842, page.height)
                                page.textContents.joinToString(" ") { it.text }.filterNot(Char::isWhitespace)
                            }
                        }
                    }
                }
                fun publicNumber(document: CreatedEstimateSeries) = EstimateNumbering.printable(document.documentNumber)
                val smallText = exportPages(rendered[0]).joinToString("")
                assertTrue("Квартира/Центр" in smallText, smallText)
                assertTrue("Позиція" in smallText, smallText)
                assertTrue("Дооплати·" in smallText, smallText)
                assertEquals(2, Regex("Збереженекреслення").findAll(smallText).count(), smallText)
                shortQuotes.forEach { assertTrue("${publicNumber(it)}·v1" in smallText, smallText) }
                assertTrue(rendered[0].fileName.endsWith("-v1.pdf"))
                val pages = rendered[1]
                val renderedPages = exportPages(pages)
                val compactText = renderedPages.joinToString("")
                assertTrue(pages.count > 1)
                assertTrue(renderedPages.all { "Зведенийкошторис" in it }, compactText)
                assertTrue("Квартира/Центр" in compactText && "Позиція" in compactText, compactText)
                assertTrue("${full.frozen.publicNumber}·v2" in compactText, compactText)
                assertTrue("Частковийкошторис" in compactText, compactText)
                assertTrue("Монтажліхтаря" in compactText, compactText)
                assertTrue("Ціна,₴" in compactText, compactText)
                assertTrue("Сума,₴" in compactText, compactText)
                assertTrue(Regex("Кількість").findAll(compactText).count() > 10, compactText)
                assertEquals(10, Regex("Збереженекреслення").findAll(compactText).count(), compactText)
                assertEquals(40, Regex("декоративного").findAll(compactText).count(), compactText)
                val payable = measurementMoney(total).filterNot(Char::isWhitespace)
                assertTrue("Дооплати·$payable" in compactText, compactText)
                assertTrue("Додатковазнижка" in compactText, compactText)
                assertTrue(pages.fileName.startsWith("zvedenyi-koshtorys-") && pages.fileName.endsWith("-v2.pdf"))
                fullQuotes.forEach { assertTrue("${publicNumber(it)}·v1" in compactText, compactText) }
                assertFalse("${publicNumber(first)}·v2" in compactText, compactText)
                assertFalse(first.initialRevisionUuid in compactText, compactText)
                assertFalse(first.seriesUuid in compactText, compactText)
                assertFalse("Спальня" in compactText, compactText)
                assertFalse("Перейменованийоб'єкт" in compactText, compactText)
                assertEquals(before, history.frozenIndividuals())
                assertEquals(sequence, nextIndividualSequence(db))
                assertEquals(full.frozen, repository.read(v2.revisionUuid)?.frozen)
                assertEquals(EstimateWorkflowStatus.DRAFT, repository.read(v2.revisionUuid)?.status)
                verifyFrozenEstimateContents(driver)
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
