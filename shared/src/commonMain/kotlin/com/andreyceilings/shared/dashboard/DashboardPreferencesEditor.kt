package com.andreyceilings.shared.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
                preferences.order.forEachIndexed { index, block ->
                    key(block) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DragHandle, stringResource(Res.string.p6c_drag),
                                Modifier.size(36.dp).padding(6.dp).pointerInput(block) {
                                    var accumulated = 0f
                                    val step = 48.dp.toPx()
                                    detectDragGesturesAfterLongPress(onDragStart = { accumulated = 0f }) { event, delta ->
                                        event.consume()
                                        accumulated += delta.y
                                        if (!busy && kotlin.math.abs(accumulated) >= step) {
                                            change(current.move(block, if (accumulated > 0) 1 else -1))
                                            accumulated = 0f
                                        }
                                    }
                                })
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
