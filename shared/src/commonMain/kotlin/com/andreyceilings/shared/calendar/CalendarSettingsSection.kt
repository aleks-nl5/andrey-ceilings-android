package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
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
    val read = calendarOptionsState(store)
    val options = read.data()
    if (options == null) {
        if (read is CalendarRead.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else CalendarFeedback(Res.string.work_unavailable)
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
        WorkZoneField(zone) { zone = it }
        Text(stringResource(Res.string.work_working_days), style = MaterialTheme.typography.labelMedium)
        DaySelection(days) { days = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.work_first_day), Modifier.weight(1f))
            CalendarMenu(stringResource(first?.label() ?: Res.string.work_system)) {
                CalendarMenuItem(text = { Text(stringResource(Res.string.work_system)) }, onClick = { first = null })
                DayOfWeek.entries.forEach { day ->
                    CalendarMenuItem(text = { Text(stringResource(day.label())) }, onClick = { first = day })
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
        if (failure) CalendarFeedback(Res.string.work_save_failed)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.work_manage_types), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { editor = CalendarWorkType(newRecordUuid(), "", 3763412, "other", 60, false) }) {
                Icon(Icons.Default.Add, stringResource(Res.string.work_add_type))
            }
        }
        options.types.forEach { type ->
            Row(Modifier.fillMaxWidth().clickable { editor = type }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
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
    var pickingColor by remember(type) { mutableStateOf(false) }
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
                    OutlinedButton(onClick = { pickingColor = true }) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xff000000 or (parsedColor ?: 0).toLong())))
                        Spacer(Modifier.width(8.dp))
                        Text("#" + color.uppercase())
                    }
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
                        Text(stringResource(Res.string.work_archive))
                    }
                }
                WorkField(duration, Res.string.work_type_duration, error = parsedDuration == null, keyboard = KeyboardType.Number) { duration = it }
                if (error) CalendarFeedback(Res.string.work_save_failed)
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
    if (pickingColor) CalendarColorPicker(color, onClose = { pickingColor = false }) { color = it; pickingColor = false }
}

@Composable
private fun CalendarColorPicker(initial: String, onClose: () -> Unit, onSelect: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val palette = listOf("B3261E","E53935","E91E63","9C27B0","673AB7","3F51B5","1976D2","039BE5","0097A7","008577",
        "388E3C","689F38","827717","F9A825","EF6C00","BF360C","795548","546E7A","616161","37474F")
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(Res.string.work_choose_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                palette.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { hex ->
                            IconButton(onClick = { value = hex }, modifier = Modifier.size(42.dp)) {
                                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xff000000 or hex.toLong(16))),
                                    contentAlignment = Alignment.Center) {
                                    if (value.equals(hex, true)) Icon(Icons.Default.Check, null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
                WorkField(value, Res.string.work_type_color, error = value.length != 6 || value.toIntOrNull(16) == null) { value = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect(value) },
            enabled = value.length == 6 && value.toIntOrNull(16)?.let { it in 0..16777215 } == true) { Text(stringResource(Res.string.oc_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p5d_cancel)) } })
}
