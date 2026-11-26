package com.andreyceilings.shared.estimate

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.compose.resources.stringResource

/** All individual status entry points share the same consequence confirmation. */
@Composable
internal fun linkedQuoteStatusChange():
    suspend (MeasurementElementsStore, SavedIndividualQuote, EstimateWorkflowStatus, List<SavedIndividualQuote>) -> Boolean {
    var pending by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    pending?.let { reply ->
        AlertDialog(
            onDismissRequest = { reply.complete(false) },
            title = { Text(stringResource(Res.string.p6b_deapprove)) },
            text = { Text(stringResource(Res.string.p6b_status_linked)) },
            confirmButton = { TextButton(onClick = { reply.complete(true) }) {
                Text(stringResource(Res.string.p6b_confirm))
            } },
            dismissButton = { TextButton(onClick = { reply.complete(false) }) {
                Text(stringResource(Res.string.p5d_cancel))
            } }
        )
    }
    return { store, target, status, previous ->
        val linked = store.linkedConsolidatedApproval(target, status, previous)
        val proceed = if (linked == null) true else {
            val reply = CompletableDeferred<Boolean>()
            pending = reply
            try { reply.await() } finally { pending = null }
        }
        if (!proceed) true else store.setQuoteStatus(target.revisionUuid, target.statusVersion, status,
            previous.associate { it.revisionUuid to it.statusVersion }, linked)
    }
}
