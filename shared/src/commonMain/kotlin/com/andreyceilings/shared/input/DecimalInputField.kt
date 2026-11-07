package com.andreyceilings.shared.input

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

/** Initial separators gain a display zero; domain validation never rewrites the editor state. */
@Composable
fun DecimalInputField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, isError: Boolean = false,
    signed: Boolean = false, allowDecimalSeparator: Boolean = true
) {
    val requester = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(
            field.selection.start.coerceIn(0, value.length), field.selection.end.coerceIn(0, value.length)))
    }
    fun update(next: TextFieldValue) {
        val edited = filterNumericEdit(field, next, signed, allowDecimalSeparator)
        val changed = edited.text != field.text
        field = edited
        if (changed) onValueChange(edited.text)
    }
    OutlinedTextField(field, { update(it) }, label = { Text(label) },
        modifier = modifier.fillMaxWidth().focusRequester(requester), enabled = enabled,
        singleLine = true, isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimalSeparator) KeyboardType.Decimal else KeyboardType.Number),
        trailingIcon = if (allowDecimalSeparator) ({
            IconButton(enabled = enabled, onClick = {
                insertDecimalSeparator(field)?.let { update(it) }
                requester.requestFocus()
            }, modifier = Modifier.semantics { contentDescription = "Вставити десяткову кому" }) { Text(",") }
        }) else null,
        leadingIcon = if (signed) ({
            IconButton(enabled = enabled, onClick = {
                val removing = field.text.startsWith('-')
                val text = if (removing) field.text.drop(1) else "-${field.text}"
                val delta = if (removing) -1 else 1
                update(TextFieldValue(text, TextRange(
                    (field.selection.start + delta).coerceIn(0, text.length),
                    (field.selection.end + delta).coerceIn(0, text.length)),
                    field.composition?.let { range -> TextRange(
                        (range.start + delta).coerceIn(0, text.length),
                        (range.end + delta).coerceIn(0, text.length)) }))
                requester.requestFocus()
            }, modifier = Modifier.semantics { contentDescription = "Змінити знак" }) { Text("±") }
        }) else null)
}

/** Replace the current selection, never append behind the caret or insert a second separator. */
fun insertDecimalSeparator(field: TextFieldValue): TextFieldValue? {
    val a = field.selection.min
    val b = field.selection.max
    val remaining = field.text.removeRange(a, b)
    if ('.' in remaining || ',' in remaining) return null
    fun map(offset: Int) = when {
        offset <= a -> offset
        offset < b -> a + 1
        else -> offset + 1 - (b - a)
    }
    return TextFieldValue(field.text.replaceRange(a, b, ","), TextRange(a + 1),
        field.composition?.let {
            val start = if (a == b && it.start == a && it.end > a) a + 1 else map(it.start)
            TextRange(start, map(it.end))
        })
}
