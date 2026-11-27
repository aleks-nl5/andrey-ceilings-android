package com.andreyceilings.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared resource-backed outlined selector for host dialogs. */
@Composable
fun CeilingsProjectPicker(projects: List<Pair<Long, String>>, selectedId: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.p6c_project), style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(projects.firstOrNull { it.first == selectedId }?.second ?: stringResource(Res.string.p6c_choose_project),
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
            }
            CeilingsPopupMenu(expanded, { expanded = false }) {
                projects.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) })
                }
            }
        }
    }
}
