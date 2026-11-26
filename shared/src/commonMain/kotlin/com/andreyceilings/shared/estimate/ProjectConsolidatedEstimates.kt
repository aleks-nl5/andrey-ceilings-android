package com.andreyceilings.shared.estimate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.measurement.WorkspaceTrash
import com.andreyceilings.shared.measurement.measurementMoney
import com.andreyceilings.shared.measurement.workspaceStatusAppearance
import com.andreyceilings.shared.measurement.workspaceStatusLabel
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.ContextFeedbackIcon
import com.andreyceilings.shared.ui.ContextFeedbackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProjectConsolidatedEstimates(store: ObjectsClientsStore, elements: MeasurementElementsStore, projectId: Long) {
    var error by remember(store, projectId) { mutableStateOf(false) }
    var busy by remember(store, projectId) { mutableStateOf(false) }
    var confirming by remember(store, projectId) { mutableStateOf<Map<String, Long>?>(null) }
    val flow = remember(store, projectId) {
        store.observeConsolidatedVersions(projectId).catch { error = true }
    }
    val versions by flow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.p6b_group), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (error) {
                val reason = stringResource(Res.string.p6b_failed)
                ContextFeedbackIcon(reason, reason, ContextFeedbackKind.ERROR)
            }
            IconButton(onClick = { elements.openConsolidatedComposer(projectId) }, enabled = !busy) {
                Icon(Icons.Default.Add, stringResource(Res.string.p6b_new))
            }
        }
        val rows = versions.orEmpty()
        if (rows.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rows.first().publicNumber, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    val protected = rows.any { it.status == EstimateWorkflowStatus.APPROVED.name }
                    if (protected) {
                        val reason = stringResource(Res.string.p5d_delete_protected)
                        ContextFeedbackIcon(reason, reason)
                    }
                    WorkspaceTrash(!protected && !busy, Res.string.p5d_delete_series) {
                        confirming = rows.associate { it.revisionUuid to it.version }
                    }
                }
                rows.forEach { row ->
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy) {
                        elements.openConsolidatedComposer(projectId, revisionUuid = row.revisionUuid)
                    }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.p5d_version, row.versionNumber), style = MaterialTheme.typography.labelLarge)
                        Text(row.title, Modifier.weight(1f).padding(horizontal = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(measurementMoney(row.finalCents), style = MaterialTheme.typography.labelLarge)
                        val status = EstimateWorkflowStatus.valueOf(row.status)
                        val (icon, tint) = workspaceStatusAppearance(status)
                        Icon(icon, workspaceStatusLabel(status), Modifier.padding(start = 8.dp).size(18.dp), tint = tint)
                    }
                }
            }
        }
    }
    confirming?.let { expected ->
        ConsolidatedDeleteDialog(true, onClose = { confirming = null }) {
            confirming = null
            busy = true
            error = false
            scope.launch {
                try { error = !elements.deleteConsolidated(projectId, expected, true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }
    }
}
