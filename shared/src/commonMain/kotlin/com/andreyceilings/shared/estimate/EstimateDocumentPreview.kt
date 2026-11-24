package com.andreyceilings.shared.estimate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

enum class EstimateDocumentAction(val label: String) {
    SAVE("Зберегти PDF"), SHARE("Поділитися"), PRINT("Друк")
}

/** The host owns file permissions and system UI. Completion without a message includes cancellation. */
fun interface EstimateDocumentExporter {
    fun export(pages: EstimateDocumentPages, action: EstimateDocumentAction, onComplete: (String?) -> Unit)

    /** A chooser launch alone is insufficient to report observed selection of a share target. */
    fun exportWithShareConfirmation(
        pages: EstimateDocumentPages, action: EstimateDocumentAction, onComplete: (String?) -> Unit,
        onShareDispatched: () -> Unit
    ) = export(pages, action, onComplete)
}

val LocalEstimateDocumentExporter = staticCompositionLocalOf<EstimateDocumentExporter?> { null }

@Composable
internal fun EstimateDocumentPreview(
    store: MeasurementElementsStore,
    seriesUuid: String,
    revisionUuid: String,
    onClose: () -> Unit
) = key(store, seriesUuid, revisionUuid) {
    var quote by remember { mutableStateOf<SavedIndividualQuote?>(null) }
    EstimateDocumentPreviewDialog(
        load = {
            quote = checkNotNull(store.savedQuote(seriesUuid, revisionUuid))
            checkNotNull(store.estimateDocument(seriesUuid, revisionUuid))
        },
        layout = { document, options, measurer -> layoutEstimateDocument(document, options, measurer) },
        onClose = onClose,
        shareDispatched = { quote?.let { store.markQuoteShareDispatched(it) } ?: false }
    )
}

internal class EstimatePreviewLoadFailure(message: String) : IllegalStateException(message)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T : Any> EstimateDocumentPreviewDialog(
    load: suspend () -> T,
    layout: (T, DocumentPresentationOptions, TextMeasurer) -> EstimateDocumentPages,
    onClose: () -> Unit,
    beforeExport: (suspend () -> Unit)? = null,
    editRoomSections: Boolean = true,
    shareDispatched: (suspend () -> Boolean)? = null
) {
    val exporter = LocalEstimateDocumentExporter.current
    val measurer = rememberTextMeasurer()
    val shareConflict = stringResource(Res.string.p5d_share_status_conflict)
    val fixedDiscountLabel = stringResource(Res.string.p5d_pdf_fixed_discount_label)
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<T?>(null) }
    var loading by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val horizontalScroll = rememberScrollState()
    var options by remember {
        mutableStateOf(DocumentPresentationOptions(EstimateDocumentSection.entries.filter {
            if (editRoomSections) it != EstimateDocumentSection.ISSUER && it != EstimateDocumentSection.PHOTOS
            else it.editable
        }))
    }
    LaunchedEffect(Unit) {
        try {
            document = load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: EstimatePreviewLoadFailure) {
            message = failure.message
        } catch (_: Exception) {
            message = "Не вдалося прочитати збережені дані. Перевірте доступ до кошторисів."
        } finally {
            loading = false
        }
    }
    // Resolve the page snapshot only while previewing. Keystrokes in the editor do not repaginate.
    val pages = if (editor) null else remember(document, options, measurer, fixedDiscountLabel) {
        document?.let { layout(it, options.copy(fixedDiscountLabel = fixedDiscountLabel), measurer) }
    }
    Dialog(
        onDismissRequest = { if (!exporting) { if (editor) editor = false else onClose() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !exporting, onClick = { if (editor) editor = false else onClose() }) {
                        Text(if (editor) "До перегляду" else "Назад")
                    }
                    if (document != null && !editor) {
                        TextButton(enabled = !exporting, onClick = { editor = true }) { Text("Оформлення") }
                        TextButton(onClick = { zoomed = !zoomed }) { Text(if (zoomed) "За шириною" else "Збільшити") }
                    }
                }
                Text(if (editor) "Оформлення PDF" else "Попередній перегляд PDF",
                    style = MaterialTheme.typography.titleLarge)
                if (loading || exporting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                message?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (editor) {
                    EstimateDocumentEditor(options) { options = it }
                } else if (pages != null) {
                    if (exporter != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EstimateDocumentAction.entries.forEach { action ->
                                OutlinedButton(enabled = !exporting, onClick = {
                                    exporting = true
                                    message = null
                                    scope.launch {
                                        try {
                                            beforeExport?.invoke()
                                            exporter.exportWithShareConfirmation(pages, action, onComplete = { result ->
                                                if (result != null || message == null) message = result
                                                exporting = false
                                            }, onShareDispatched = {
                                                if (action == EstimateDocumentAction.SHARE && shareDispatched != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                                    try {
                                                        if (!shareDispatched()) message = shareConflict
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Exception) {
                                                        message = shareConflict
                                                    }
                                                }
                                            })
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: EstimatePreviewLoadFailure) {
                                            message = failure.message
                                            exporting = false
                                        } catch (_: Exception) {
                                            message = "Не вдалося підготувати PDF до експорту."
                                            exporting = false
                                        }
                                    }
                                }) { Text(action.label) }
                            }
                        }
                    } else {
                        Text("Збереження, надсилання й друк PDF доступні на Android.",
                            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val pageWidth = if (zoomed) maxOf(maxWidth, 595.dp) else minOf(maxWidth, 595.dp)
                        val viewport = Modifier.fillMaxSize()
                        Box(if (zoomed) viewport.horizontalScroll(horizontalScroll) else viewport) {
                            LazyColumn(Modifier.width(pageWidth).fillMaxHeight(),
                                contentPadding = PaddingValues(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(pages.count) { index ->
                                    Surface(shadowElevation = 2.dp) {
                                        Canvas(Modifier.fillMaxWidth()
                                            .aspectRatio(EstimateDocumentPages.WIDTH.toFloat() / EstimateDocumentPages.HEIGHT)
                                            .semantics { contentDescription = pages.pageText(index) }) {
                                            pages.drawPage(this, index)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EstimateDocumentEditor(
    options: DocumentPresentationOptions,
    onChange: (DocumentPresentationOptions) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(options.order.size, key = { options.order[it].name }) { index ->
            val section = options.order[index]
            fun move(offset: Int) {
                val order = options.order.toMutableList()
                order.removeAt(index)
                order.add(index + offset, section)
                onChange(options.copy(order = order))
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(section.label, style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(enabled = index > 0, onClick = { move(-1) }) { Text("Вище") }
                        TextButton(enabled = index < options.order.lastIndex, onClick = { move(1) }) { Text("Нижче") }
                        TextButton(onClick = {
                            val hidden = if (section in options.hidden) options.hidden - section else options.hidden + section
                            onChange(options.copy(hidden = hidden))
                        }) { Text(if (section in options.hidden) "Показати" else "Приховати") }
                    }
                    if (section.editable && section !in options.hidden) {
                        OutlinedTextField(options.text[section].orEmpty(),
                            onValueChange = { onChange(options.copy(text = options.text + (section to it))) },
                            modifier = Modifier.fillMaxWidth(), label = { Text(section.label) }, minLines = 2)
                    }
                }
            }
        }
    }
}
