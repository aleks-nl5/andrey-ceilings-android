package com.andreyceilings.app.ui.screens

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.DocumentsContract
import android.service.chooser.ChooserResult
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.andreyceilings.shared.estimate.EstimateDocumentAction
import com.andreyceilings.shared.estimate.EstimateDocumentExporter
import com.andreyceilings.shared.estimate.EstimateDocumentPages
import com.andreyceilings.shared.estimate.LocalEstimateDocumentExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.nio.file.Files

class EstimatePdfFileProvider : FileProvider()

private const val SaveCache = "estimate-pdf-save"
private const val ShareCache = "estimate-pdf-share"
private const val ExportFailure = "Не вдалося сформувати PDF. Спробуйте ще раз."

@Composable
internal fun AndroidEstimateDocumentExport(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }
    var saveCompletion by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val path = pendingSave
        val completion = saveCompletion
        pendingSave = null
        saveCompletion = null
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val staged = path?.let { File(context.cacheDir, it) }
                try {
                    if (uri == null) null
                    else {
                        checkNotNull(staged).inputStream().use { input ->
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { input.copyTo(it) }
                        }
                        null
                    }
                } catch (_: Exception) {
                    // The picker returns a new document. Remove an empty or partial output if the provider allows it.
                    val removed = uri != null && runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        .getOrDefault(false)
                    if (removed) "Не вдалося зберегти PDF."
                    else "Не вдалося зберегти PDF. У вибраній папці міг залишитися неповний файл."
                } finally {
                    staged?.parentFile?.deleteRecursively()
                }
            }
            if (completion != null) completion(message)
            else if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (uri != null && message == null) Toast.makeText(context, "PDF збережено.", Toast.LENGTH_SHORT).show()
        }
    }
    var pendingShare by remember { mutableStateOf<PendingEstimateShare?>(null) }
    fun finishShare() {
        pendingShare?.let {
            pendingShare = null
            it.dispose()
            it.complete(null)
        }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Some chooser implementations return RESULT_CANCELED both with and without a target.
        // The chooser's explicit selected-component callback is the confirmation.
        val share = pendingShare
        scope.launch {
            delay(750)
            if (share != null && pendingShare === share) finishShare()
        }
    }
    DisposableEffect(context) {
        onDispose { pendingShare?.dispose() }
    }
    fun exportDocument(pages: EstimateDocumentPages, action: EstimateDocumentAction, complete: (String?) -> Unit, dispatched: () -> Unit) {
        if (!pages.exportable) {
            complete(ExportFailure)
        } else if (pendingSave != null || pendingShare != null) {
            complete("Спочатку завершіть збереження PDF.")
        } else {
            scope.launch {
                var staged: File? = null
                try {
                    when (action) {
                        EstimateDocumentAction.PRINT -> {
                            val manager = context.getSystemService(PrintManager::class.java)
                            checkNotNull(manager).print(pages.fileName, EstimatePrintAdapter(pages),
                                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build())
                            complete(null)
                        }
                        EstimateDocumentAction.SAVE, EstimateDocumentAction.SHARE -> {
                            val directory = if (action == EstimateDocumentAction.SAVE) SaveCache else ShareCache
                            val file = withContext(Dispatchers.IO) { stagePdf(context, directory, pages.fileName) }
                            staged = file
                            writeEstimateDocumentPdf(pages, 0 until pages.count) { file.outputStream() }
                            if (action == EstimateDocumentAction.SAVE) {
                                pendingSave = file.relativeTo(context.cacheDir).path
                                saveCompletion = complete
                                save.launch(pages.fileName)
                            } else {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.estimate-pdfs", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri(pages.fileName, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val selectionAction = "${context.packageName}.ESTIMATE_PDF_TARGET.${UUID.randomUUID()}"
                                val callback = PendingIntent.getBroadcast(context, 0,
                                    Intent(selectionAction).setPackage(context.packageName),
                                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
                                val receiver = object : BroadcastReceiver() {
                                    override fun onReceive(context: Context?, selection: Intent?) {
                                        if (selection?.action != selectionAction) return
                                        val selectedComponent = if (Build.VERSION.SDK_INT >= 35)
                                            selection.getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT, ChooserResult::class.java)?.selectedComponent
                                        else null
                                        @Suppress("DEPRECATION")
                                        val legacyComponent = selection.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)
                                        if (selectedComponent == null && legacyComponent == null) return
                                        dispatched()
                                        finishShare()
                                    }
                                }
                                ContextCompat.registerReceiver(context, receiver, IntentFilter(selectionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
                                pendingShare = PendingEstimateShare({
                                    context.unregisterReceiver(receiver)
                                    callback.cancel()
                                }, complete)
                                chooser.launch(Intent.createChooser(intent, null, callback.intentSender))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (pendingSave == null) staged?.parentFile?.deleteRecursively()
                    throw cancelled
                } catch (_: Exception) {
                    staged?.parentFile?.deleteRecursively()
                    pendingSave = null
                    saveCompletion = null
                    pendingShare?.dispose()
                    pendingShare = null
                    complete(ExportFailure)
                }
            }
        }
    }
    val exporter = object : EstimateDocumentExporter {
        override fun export(pages: EstimateDocumentPages, action: EstimateDocumentAction, onComplete: (String?) -> Unit) =
            exportDocument(pages, action, onComplete) {}

        override fun exportWithShareConfirmation(
            pages: EstimateDocumentPages, action: EstimateDocumentAction, onComplete: (String?) -> Unit, onShareDispatched: () -> Unit
        ) = exportDocument(pages, action, onComplete, onShareDispatched)
    }
    CompositionLocalProvider(LocalEstimateDocumentExporter provides exporter, content = content)
}

private class PendingEstimateShare(val dispose: () -> Unit, val complete: (String?) -> Unit)

private fun stagePdf(context: Context, directory: String, name: String): File {
    val cache = File(context.cacheDir, directory)
    check(cache.isDirectory || cache.mkdirs())
    val expired = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    cache.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.deleteRecursively() }
    return File(Files.createTempDirectory(cache.toPath(), "pdf-").toFile(), name)
}

/** Paragraphs share the preview's UI-thread font layout. Only streaming bytes uses the IO dispatcher. */
internal suspend fun writeEstimateDocumentPdf(
    pages: EstimateDocumentPages,
    indices: Iterable<Int>,
    isCancelled: () -> Boolean = { false },
    output: () -> OutputStream
) = withContext(Dispatchers.Main.immediate) {
    check(pages.exportable)
    val pdf = PdfDocument()
    try {
        val width = EstimateDocumentPages.WIDTH
        val height = EstimateDocumentPages.HEIGHT
        val drawing = CanvasDrawScope()
        for (index in indices) {
            if (isCancelled()) throw CancellationException()
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            try {
                drawing.draw(Density(1f), LayoutDirection.Ltr, Canvas(page.canvas), Size(width.toFloat(), height.toFloat())) {
                    pages.drawPage(this, index)
                }
            } finally {
                pdf.finishPage(page)
            }
            yield()
        }
        withContext(Dispatchers.IO) {
            if (isCancelled()) throw CancellationException()
            output().use { destination ->
                // Android's native PDF writer can return normally after an OutputStream write fails.
                // Check the actual destination writes before reporting success.
                val checked = CheckedPdfOutput(destination)
                pdf.writeTo(checked)
                checked.finish()
            }
        }
    } finally {
        pdf.close()
    }
}

private class CheckedPdfOutput(private val destination: OutputStream) : OutputStream() {
    private var failure: Exception? = null
    private var wroteBytes = false

    override fun write(value: Int) = recordWrite { destination.write(value) }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length > 0) recordWrite { destination.write(bytes, offset, length) }
    }

    private inline fun recordWrite(write: () -> Unit) {
        failure?.let { throw it }
        try {
            write()
            wroteBytes = true
        } catch (error: Exception) {
            failure = error
            throw error
        }
    }

    fun finish() {
        failure?.let { throw it }
        if (!wroteBytes) throw IOException("PDF renderer produced no output")
        destination.flush()
    }
}

internal class EstimatePrintAdapter(private val pages: EstimateDocumentPages) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onFinish() {
        scope.cancel()
        super.onFinish()
    }

    override fun onLayout(
        oldAttributes: PrintAttributes,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        if (!scope.isActive || cancellationSignal.isCanceled) callback.onLayoutCancelled()
        else callback.onLayoutFinished(
            PrintDocumentInfo.Builder(pages.fileName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.count).build(),
            oldAttributes != newAttributes
        )
    }

    override fun onWrite(
        requested: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        writePages(requested, destination, cancellationSignal) { result ->
            when (result) {
                is PrintWriteResult.Finished -> callback.onWriteFinished(result.pages.toTypedArray())
                PrintWriteResult.Cancelled -> callback.onWriteCancelled()
                PrintWriteResult.Failed -> callback.onWriteFailed(ExportFailure)
            }
        }
    }

    internal fun writePages(
        requested: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        complete: (PrintWriteResult) -> Unit
    ) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        // Android invokes the adapter on main. Enter even for a late write after onFinish,
        // so every request closes its descriptor and reports its terminal result.
        val result = try {
            ensureActive()
            if (cancellationSignal.isCanceled) throw CancellationException()
            val indices = (0 until pages.count).filter { index -> requested.any { index in it.start..it.end } }
            if (indices.isNotEmpty()) {
                writeEstimateDocumentPdf(pages, indices, { cancellationSignal.isCanceled }) {
                    ParcelFileDescriptor.AutoCloseOutputStream(destination)
                }
            }
            ensureActive()
            if (cancellationSignal.isCanceled) PrintWriteResult.Cancelled
            else PrintWriteResult.Finished(indices.map { PageRange(it, it) })
        } catch (_: CancellationException) {
            PrintWriteResult.Cancelled
        } catch (_: Exception) {
            if (cancellationSignal.isCanceled || !isActive) PrintWriteResult.Cancelled else PrintWriteResult.Failed
        } finally {
            runCatching { destination.close() }
        }
        complete(result)
    }
}

internal sealed interface PrintWriteResult {
    data class Finished(val pages: List<PageRange>) : PrintWriteResult
    data object Cancelled : PrintWriteResult
    data object Failed : PrintWriteResult
}
