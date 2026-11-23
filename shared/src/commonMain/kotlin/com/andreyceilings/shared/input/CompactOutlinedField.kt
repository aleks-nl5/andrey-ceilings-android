package com.andreyceilings.shared.input

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.andreyceilings.shared.theme.CeilingsFieldDefaults

/**
 * Compact outlined input with genuinely smaller interior padding.
 * Height-only overrides on M3 OutlinedTextField clip text/labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactOutlinedField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    // Compact height must never imply a smaller value font: standard M3 input is bodyLarge.
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = 1
) {
    require(maxLines > 0)
    val singleLine = maxLines == 1
    val transformation = VisualTransformation.None
    val colors = OutlinedTextFieldDefaults.colors()
    BasicTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = CeilingsFieldDefaults.CompactHeight),
        enabled = enabled, singleLine = singleLine, maxLines = maxLines,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        textStyle = textStyle.copy(color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text, innerTextField = inner,
                enabled = enabled, singleLine = singleLine, visualTransformation = transformation,
                interactionSource = interactionSource, isError = isError,
                // Let Material3 animate its default expanded/floating label typography.
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = leadingIcon, colors = colors,
                contentPadding = CeilingsFieldDefaults.CompactContentPadding
            )
        }
    )
}

/** Text name variant with the same compact *interior* padding as numeric fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactOutlinedStringField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = 1
) {
    var field by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) field = field.copy(
            text = value, selection = TextRange(
                field.selection.start.coerceIn(0, value.length),
                field.selection.end.coerceIn(0, value.length)))
    }
    CompactOutlinedField(
        field, onValueChange = { next ->
            field = next
            if (next.text != value) onValueChange(next.text)
        }, label = label, modifier = modifier, enabled = enabled, isError = isError,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, maxLines = maxLines
    )
}
