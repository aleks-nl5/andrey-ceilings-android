package com.andreyceilings.shared.localization

import com.andreyceilings.shared.ui.CeilingsPopupMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.andreyceilings.shared.resources.Res
import com.andreyceilings.shared.resources.app_language
import com.andreyceilings.shared.resources.language_system
import com.andreyceilings.shared.resources.language_ukrainian
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppLanguageSettings(state: AppLanguageState) {
    val system = AppLanguagePreference.System
    val ukrainian = AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN)
    val options = listOf(
        system to stringResource(Res.string.language_system),
        ukrainian to stringResource(Res.string.language_ukrainian)
    )
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { expanded = true },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(stringResource(Res.string.app_language)) },
            supportingContent = { Text(options.first { it.first == state.preference }.second) }
        )
        CeilingsPopupMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (preference, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    state.select(preference)
                    expanded = false
                })
            }
        }
    }
}
