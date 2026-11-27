package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource

@Composable
fun CalendarSettingsSection(store: CalendarStore?) {
    var open by remember(store) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = !open }, enabled = store != null, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.CalendarMonth, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.work_settings))
    }
    if (!open || store == null) return
    val read = calendarState(remember(store) { store.options() })
    val options = read.data()
    if (options == null) {
        if (read is CalendarRead.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Text(stringResource(Res.string.work_unavailable))
        return
    }
    val scope = rememberCoroutineScope()
    var zone by remember(options.settings.zone) { mutableStateOf(options.settings.zone.id) }
    var days by remember(options.settings.workingDays) { mutableStateOf(options.settings.workingDays) }
    var first by remember(options.settings.firstWeekday) { mutableStateOf(options.settings.firstWeekday) }
    var failure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CalendarWorkType?>(null) }
    val parsedZone = remember(zone) { runCatching { TimeZone.of(zone) }.getOrNull() }
    val changes = parsedZone?.let { CalendarSettings(it, first, days) }?.takeIf { it != options.settings }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkField(zone, Res.string.work_zone, error = parsedZone == null) { zone = it }
        Text(stringResource(Res.string.work_working_days), style = MaterialTheme.typography.labelMedium)
        DaySelection(days) { days = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.work_first_day), Modifier.weight(1f))
            CalendarMenu(stringResource(first?.label() ?: Res.string.work_system)) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.work_system)) }, onClick = { first = null })
                DayOfWeek.entries.forEach { day ->
                    DropdownMenuItem(text = { Text(stringResource(day.label())) }, onClick = { first = day })
                }
            }
        }
        TextButton(enabled = changes != null && !busy, onClick = {
            busy = true
            scope.launch {
                try { store.saveSettings(checkNotNull(changes)); failure = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failure = true }
                finally { busy = false }
            }
        }) { Text(stringResource(Res.string.oc_save)) }
        if (failure) Text(stringResource(Res.string.work_save_failed), color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.work_manage_types), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { editor = CalendarWorkType(newRecordUuid(), "", 3763412, "other", 60, false) }) {
                Icon(Icons.Default.Add, stringResource(Res.string.work_add_type))
            }
        }
        options.types.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(typeIcon(type), null, tint = typeColor(type))
                Text(calendarTypeLabel(type), Modifier.weight(1f).padding(horizontal = 8.dp))
                if (type.archived) Icon(Icons.Default.Archive, stringResource(Res.string.work_archive))
                TextButton(onClick = { editor = type }) { Text(stringResource(Res.string.work_type_duration) + " · " + type.durationMinutes) }
            }
        }
    }
    editor?.let { type ->
        CalendarTypeEditor(type, store, onClose = { editor = null })
    }
}

@Composable
private fun CalendarTypeEditor(type: CalendarWorkType, store: CalendarStore, onClose: () -> Unit) {
    var label by remember(type) { mutableStateOf(type.label) }
    var color by remember(type) { mutableStateOf(type.color.toString(16).padStart(6, '0')) }
    var icon by remember(type) { mutableStateOf(type.icon) }
    var duration by remember(type) { mutableStateOf(type.durationMinutes.toString()) }
    var archived by remember(type) { mutableStateOf(type.archived) }
    var error by remember(type) { mutableStateOf(false) }
    var busy by remember(type) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsedColor = color.toIntOrNull(16)?.takeIf { color.length == 6 && it in 0..16777215 }
    val parsedDuration = duration.toIntOrNull()?.takeIf { it in 1..1439 }
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (type.label == "") stringResource(Res.string.work_add_type) else calendarTypeLabel(type)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label != null) {
                    WorkField(label.orEmpty(), Res.string.work_type_label) { label = it }
                    WorkField(color, Res.string.work_type_color, error = parsedColor == null) { color = it }
                    Text(stringResource(Res.string.work_type_icon), style = MaterialTheme.typography.labelMedium)
                    Row {
                        listOf("measure", "install", "deliver", "other").forEach { option ->
                            FilledTonalIconToggleButton(icon == option, { if (it) icon = option }) {
                                val semantic = when (option) {
                                    "measure" -> Res.string.work_measure
                                    "install" -> Res.string.work_install
                                    "deliver" -> Res.string.work_delivery
                                    else -> Res.string.work_other
                                }
                                Icon(typeIcon(type.copy(icon = option)), stringResource(semantic))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(archived, { archived = it })
                        Text(stringResource(if (type.archived) Res.string.work_unarchive else Res.string.work_archive))
                    }
                }
                WorkField(duration, Res.string.work_type_duration, error = parsedDuration == null, keyboard = KeyboardType.Number) { duration = it }
                if (error) Text(stringResource(Res.string.work_save_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && label?.isNotBlank() != false && parsedColor != null && parsedDuration != null,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            store.saveType(type.copy(label = label, color = checkNotNull(parsedColor),
                                icon = icon, durationMinutes = checkNotNull(parsedDuration), archived = archived))
                            onClose()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(Res.string.oc_save)) }
        }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(Res.string.p5d_cancel)) } })
}
