package com.andreyceilings.shared.estimate

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.measurement.MeasurementElementsStore
import com.andreyceilings.shared.measurement.measurementMoney
import kotlinx.coroutines.CancellationException

@Composable
internal fun ConsolidatedEstimateScreen(
    store: MeasurementElementsStore,
    session: ConsolidatedComposerSession,
    onClose: () -> Unit,
    onOpenMeasurement: (Long) -> Unit
) {
    var catalog by remember { mutableStateOf<ConsolidatedRoomCatalog?>(null) }
    var loadError by remember { mutableStateOf(false) }
    val projectId = session.projectId
    val selected by session.selection.collectAsState()
    val previewing by session.previewing.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(store, projectId) {
        try {
            catalog = store.consolidatedRooms(projectId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        }
    }

    val refs = remember(selected) { selected.values.filterNotNull() }
    val projectUuid = catalog?.projectUuid
    val complete = selected.isNotEmpty() && refs.size == selected.size
    var validation by remember(refs, projectUuid, complete) { mutableStateOf<ConsolidatedDocumentResult?>(null) }
    var validationFailed by remember(refs, projectUuid, complete) { mutableStateOf(false) }
    LaunchedEffect(refs, projectUuid, complete, previewing) {
        if (complete && !previewing && projectUuid != null) {
            try {
                validation = store.consolidatedDocument(projectId, projectUuid, refs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                validationFailed = true
            }
        }
    }

    if (previewing && complete && projectUuid != null) {
        val load: suspend () -> ConsolidatedEstimateDocument = {
            when (val result = store.consolidatedDocument(projectId, projectUuid, refs)) {
                is ConsolidatedDocumentResult.Ready -> result.document
                is ConsolidatedDocumentResult.Rejected -> throw EstimatePreviewLoadFailure(result.problem.label)
            }
        }
        EstimateDocumentPreviewDialog(
            load = load,
            layout = ::layoutConsolidatedEstimateDocument,
            onClose = {
                validation = null
                validationFailed = false
                session.previewing.value = false
            },
            beforeExport = { load() },
            editRoomSections = false
        )
        return
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                TextButton(onClick = onClose) { Text("Скасувати") }
                Text("Зведений кошторис", style = MaterialTheme.typography.titleLarge)
                val rooms = catalog?.rooms
                when {
                    loadError -> Text("Не вдалося завантажити дані. Перевірте доступ до захищеної бази.",
                        color = MaterialTheme.colorScheme.error)
                    rooms == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    rooms.isEmpty() -> Text("В об’єкті ще немає приміщень.")
                    else -> {
                        val missing = selected.keys - rooms.map { it.uuid }.toSet()
                        if (missing.isNotEmpty()) {
                            Text(ConsolidatedDocumentProblem.ROOM_MISSING.label, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = {
                                session.previewing.value = false
                                session.selection.value = selected - missing
                            }) { Text("Скинути недоступний вибір") }
                        }
                        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(rooms, key = { it.uuid }) { room ->
                                ConsolidatedRoomCard(room, room.uuid in selected, selected[room.uuid], onToggle = {
                                    session.previewing.value = false
                                    if (room.uuid in selected) {
                                        session.selection.value = selected - room.uuid
                                    } else session.selection.value = selected + (room.uuid to null)
                                }, onRevision = {
                                    session.previewing.value = false
                                    session.selection.value = selected + (room.uuid to it)
                                }, onOpenMeasurement = {
                                    onClose()
                                    onOpenMeasurement(room.id)
                                })
                            }
                        }
                        val ready = validation as? ConsolidatedDocumentResult.Ready
                        val problem = when {
                            validationFailed -> "Не вдалося перевірити вибрані версії."
                            selected.isEmpty() -> "Оберіть приміщення."
                            !complete -> "Оберіть доступну збережену версію для кожного приміщення."
                            else -> (validation as? ConsolidatedDocumentResult.Rejected)?.problem?.label
                        }
                        if (complete && validation == null && !validationFailed) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        problem?.let { Text(it) }
                        ready?.let { Text("Разом до оплати · ${measurementMoney(it.document.totalCents)}",
                            style = MaterialTheme.typography.titleMedium) }
                        Button(enabled = ready != null, onClick = { session.previewing.value = true }) {
                            Text("Переглянути зведений PDF")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsolidatedRoomCard(
    room: ConsolidatedRoomOption,
    selected: Boolean,
    revision: ConsolidatedDocumentRef?,
    onToggle: () -> Unit,
    onRevision: (ConsolidatedDocumentRef) -> Unit,
    onOpenMeasurement: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val enabled = room.revisions.isNotEmpty() || selected
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }
            ), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                Text(room.name, style = MaterialTheme.typography.titleMedium)
            }
            if (room.revisions.isEmpty()) {
                if (!room.unavailableRevisions) Text("Немає збережених кошторисів.")
                TextButton(onClick = onOpenMeasurement) { Text("Відкрити замір") }
            } else if (selected) {
                Column(Modifier.selectableGroup()) {
                    room.revisions.forEach { option ->
                        val status = option.status?.label ?: "Статус не зафіксовано"
                        val chosen = revision == option.ref
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(chosen, role = Role.RadioButton, onClick = { onRevision(option.ref) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen, onClick = null)
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("${option.number} · v${option.version} · ${option.title}")
                                Text("$status · ${measurementMoney(option.finalCents)}",
                                    style = MaterialTheme.typography.bodySmall)
                                if (option.selectedForWork) Text("Обрано для роботи", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                Text("Збережені версії · ${room.revisions.size}", style = MaterialTheme.typography.bodySmall)
            }
            if (room.unavailableRevisions) {
                Text("Деякі збережені версії недоступні.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
