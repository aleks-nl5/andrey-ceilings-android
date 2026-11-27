package com.andreyceilings.shared.dashboard

import androidx.compose.foundation.gestures.detectDragGestures
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DashboardPreferencesEditor(
    preferences: DashboardPreferences,
    onChange: (DashboardPreferences) -> Unit,
    onClose: () -> Unit,
    saving: Boolean = false,
    saveFailed: Boolean = false
) {
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.p6c_dashboard)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val current by rememberUpdatedState(preferences)
                val change by rememberUpdatedState(onChange)
                val busy by rememberUpdatedState(saving)
                var dragging by remember { mutableStateOf<DashboardBlock?>(null) }
                var dragDistance by remember { mutableFloatStateOf(0f) }
                preferences.order.forEachIndexed { index, block ->
                    key(block) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DragHandle, stringResource(Res.string.p6c_drag),
                                Modifier.size(44.dp).pointerInput(block) {
                                    val step = 48.dp.toPx()
                                    detectDragGestures(
                                        onDragStart = { if (!busy) { dragging = block; dragDistance = 0f } },
                                        onDragCancel = { dragging = null; dragDistance = 0f },
                                        onDragEnd = {
                                            if (dragging == block && !busy) {
                                                val distance = (dragDistance / step).roundToInt()
                                                if (distance != 0) change(current.move(block, distance))
                                            }
                                            dragging = null
                                            dragDistance = 0f
                                        }
                                    ) { event, delta ->
                                        if (dragging == block) {
                                            event.consume()
                                            dragDistance += delta.y
                                        }
                                    }
                                }.padding(9.dp),
                                tint = if (dragging == block) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(block.label()), style = MaterialTheme.typography.bodySmall)
                                if (block.mandatory) Text(stringResource(Res.string.p6c_required),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!block.mandatory) {
                                Checkbox(preferences.isVisible(block), { onChange(preferences.toggle(block)) }, enabled = !saving)
                            }
                            IconButton(enabled = !saving && index > 0,
                                onClick = { onChange(preferences.move(block, -1)) }) {
                                Icon(Icons.Default.ArrowUpward, stringResource(Res.string.p6c_move_up), Modifier.size(17.dp))
                            }
                            IconButton(enabled = !saving && index < preferences.order.lastIndex,
                                onClick = { onChange(preferences.move(block, 1)) }) {
                                Icon(Icons.Default.ArrowDownward, stringResource(Res.string.p6c_move_down), Modifier.size(17.dp))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(Res.string.p6c_preview), style = MaterialTheme.typography.labelLarge)
                preferences.rows().forEach { blocks ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        blocks.forEach { block ->
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(stringResource(block.label()), Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (saveFailed) Text(stringResource(Res.string.p6c_preferences_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p6c_done)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = { onChange(DashboardPreferences.default) }) {
            Text(stringResource(Res.string.p6c_reset))
        } })
}
