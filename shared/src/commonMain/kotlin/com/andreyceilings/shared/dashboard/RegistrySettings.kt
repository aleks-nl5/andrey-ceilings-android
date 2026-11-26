package com.andreyceilings.shared.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.navigation.AppRoute
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun DashboardAppearanceSettings(
    preferences: DashboardPreferences,
    onSave: suspend (DashboardPreferences) -> Boolean
) {
    var page by rememberSaveable { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ListItem(
        modifier = Modifier.clickable { page = 1 },
        leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(Res.string.p6c_appearance)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
    when (page) {
        1 -> AlertDialog(onDismissRequest = { page = 0 },
            title = { Text(stringResource(Res.string.p6c_appearance)) },
            text = { ListItem(modifier = Modifier.clickable { page = 2 },
                leadingContent = { Icon(Icons.Default.Dashboard, null) },
                headlineContent = { Text(stringResource(Res.string.p6c_dashboard)) }) },
            confirmButton = { TextButton(onClick = { page = 0 }) { Text(stringResource(Res.string.p6c_done)) } })
        2 -> DashboardPreferencesEditor(preferences, onChange = { next ->
            if (!saving && next != preferences) {
                saving = true
                error = false
                scope.launch {
                    try {
                        error = !onSave(next)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        saving = false
                    }
                }
            }
        }, onClose = { if (!saving) page = 1 }, saving = saving, saveFailed = error)
    }
}

@Composable
fun RegistrySearchDialog(onClose: () -> Unit, onOpen: (AppRoute, String, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(Res.string.p6c_global_search)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(Res.string.p6c_search)) })
                TextButton(onClick = { onOpen(AppRoute.OBJECTS, query, false) }) { Text(stringResource(Res.string.p6c_objects)) }
                TextButton(onClick = { onOpen(AppRoute.OBJECTS, query, true) }) { Text(stringResource(Res.string.p6c_clients)) }
                TextButton(onClick = { onOpen(AppRoute.MEASUREMENTS, query, false) }) { Text(stringResource(Res.string.p6c_ceilings)) }
                TextButton(onClick = { onOpen(AppRoute.ESTIMATES, query, false) }) { Text(stringResource(Res.string.p6c_quotes)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.p5d_cancel)) } })
}


@Composable
fun RegistrySearchAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Search, stringResource(Res.string.p6c_global_search))
    }
}
