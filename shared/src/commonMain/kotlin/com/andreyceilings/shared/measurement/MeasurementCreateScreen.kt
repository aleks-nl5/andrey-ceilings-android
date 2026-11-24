package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.input.filterNumericEdit
import com.andreyceilings.shared.input.CompactOutlinedField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** A new measurement enters the same workspace with a session-only original. */
@Composable
fun MeasurementCreateWorkspace(
    projectId: Long, precision: MeasurementPrecision, store: MeasurementEditStore?,
    contentPadding: PaddingValues, systemTopInset: Dp, onBack: () -> Unit, onSaved: (Long) -> Unit,
    navigationGuard: MeasurementCreateNavigationGuard? = null, quoteStore: MeasurementElementsStore? = null
) {
    if (store == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.p5c_back)) }
            Text(stringResource(Res.string.p5c_database_unavailable))
        }
        return
    }
    key(projectId) {
        val saver = remember(store) { Saver<MeasurementEditModel, String>(
            save = { it.snapshot() },
            restore = { MeasurementEditModel.restore(store, it, projectId, 0L) }
        ) }
        val model = rememberSaveable(store, saver = saver) {
            MeasurementEditModel(store, MeasurementEditDocument(0L, 0L, MeasurementCreateDraft(projectId, precision)))
        }
        val parentFlow = remember(store, projectId) { store.observeCreateParent(projectId) }
        val parent by parentFlow.collectAsState(MeasurementCreateParent.Loading)
        MeasurementEditScreen(model, contentPadding, systemTopInset, onBack, navigationGuard,
            onCreated = onSaved, creationParent = parent, quoteStore = quoteStore)
    }
}

@Composable
internal fun MeasurementLengthField(value: String, label: String, error: String?, enabled: Boolean,
    modifier: Modifier = Modifier, selected: Boolean = false, onFocus: (Boolean) -> Unit = {},
    onNext: (() -> Unit)? = null, onChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) {
            field = field.copy(text = value, selection = TextRange(
                field.selection.start.coerceIn(0, value.length), field.selection.end.coerceIn(0, value.length)))
        }
    }
    fun update(next: TextFieldValue) {
        val edited = filterNumericEdit(field, next)
        val changed = edited.text != field.text
        field = edited
        if (changed) onChange(edited.text)
    }
    val interactionSource = remember { MutableInteractionSource() }
    var hasRealFocus by remember { mutableStateOf(false) }
    // A wall selected on the canvas has exactly the same Material outline/label
    // emphasis as a focused input, but must not request actual focus or open IME.
    // Compose keeps the round field outline and its label cutout intact.
    LaunchedEffect(selected, hasRealFocus, enabled) {
        if (selected && !hasRealFocus && enabled) {
            val previewFocus = FocusInteraction.Focus()
            interactionSource.emit(previewFocus)
            try {
                awaitCancellation()
            } finally {
                // The effect is cancelled when the wall selection changes; always
                // release the synthetic visual focus even on cancellation.
                withContext(NonCancellable) {
                    interactionSource.emit(FocusInteraction.Unfocus(previewFocus))
                }
            }
        }
    }
    val fieldModifier = modifier.then(measurementFieldFocusModifier()).fillMaxWidth()
        .onFocusChanged {
            hasRealFocus = it.isFocused
            onFocus(it.isFocused)
        }
    val keys = KeyboardOptions(keyboardType = KeyboardType.Decimal,
        imeAction = if (onNext == null) ImeAction.Default else ImeAction.Next)
    val actions = KeyboardActions(onNext = onNext?.let { next -> { next() } })
    CompactOutlinedField(field, ::update, label, modifier = fieldModifier,
        enabled = enabled, isError = error != null, interactionSource = interactionSource,
        keyboardOptions = keys, keyboardActions = actions)
}

@Composable
internal fun MeasurementShapePicker(shape: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "rectangle" to stringResource(Res.string.p5c_shape_rectangle),
        "l_shaped" to stringResource(Res.string.p5c_shape_l),
        "polygon" to stringResource(Res.string.p5c_shape_polygon)
    )
    OutlinedButton(onClick = { expanded = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("measurement-shape-picker")) {
        Text(stringResource(Res.string.p5b_shape_current,
            options.firstOrNull { it.first == shape }?.second ?: options.last().second),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        modifier = Modifier.testTag("measurement-shape-dialog"),
        title = { Text(stringResource(Res.string.p5b_shape_selection_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (id, title) ->
                    val selected = shape == id
                    Row(Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { expanded = false; onChange(id) }
                        .testTag("measurement-shape-option-$id")
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { expanded = false }) {
                Text(stringResource(Res.string.p5b_cancel_placement))
            }
        }
    )
}
