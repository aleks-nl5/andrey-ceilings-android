package com.andreyceilings.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.theme.ceilingsStatusColors
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

internal enum class ContextFeedbackKind { HELP, INFO, WARNING, ERROR }

/** Shared contextual explanation. Compact inline glyphs don't inflate field/row height. */
@Composable
internal fun ContextFeedbackIcon(
    text: String,
    description: String,
    kind: ContextFeedbackKind = ContextFeedbackKind.INFO,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    expanded: Boolean? = null,
    onExpanded: ((Boolean) -> Unit)? = null,
    tint: Color? = null
) {
    var localOpen by remember(text, kind) { mutableStateOf(false) }
    val open = expanded ?: localOpen
    fun setOpen(value: Boolean) {
        if (onExpanded != null) onExpanded(value) else localOpen = value
    }
    val scheme = MaterialTheme.colorScheme
    val icon = when (kind) {
        ContextFeedbackKind.HELP -> Icons.Default.HelpOutline
        ContextFeedbackKind.INFO, ContextFeedbackKind.WARNING -> Icons.Default.Info
        ContextFeedbackKind.ERROR -> Icons.Default.Warning
    }
    val ink = tint ?: when (kind) {
        ContextFeedbackKind.HELP, ContextFeedbackKind.INFO -> scheme.onSurfaceVariant
        ContextFeedbackKind.WARNING -> ceilingsStatusColors(scheme.surface.luminance() < 0.5f).warning
        ContextFeedbackKind.ERROR -> scheme.error
    }
    val accessibleLabel = when (kind) {
        ContextFeedbackKind.WARNING -> stringResource(Res.string.p5b_feedback_warning, description)
        ContextFeedbackKind.ERROR -> stringResource(Res.string.p5b_validation_error, description)
        else -> description
    }
    Box(modifier) {
        if (compact) {
            Box(Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button) { setOpen(!open) },
                contentAlignment = Alignment.Center) {
                Icon(icon, accessibleLabel, Modifier.size(18.dp), tint = ink)
            }
        } else {
            IconButton(onClick = { setOpen(!open) }) {
                Icon(icon, accessibleLabel, Modifier.size(21.dp), tint = ink)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            Text(text, Modifier.widthIn(max = 280.dp).padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
