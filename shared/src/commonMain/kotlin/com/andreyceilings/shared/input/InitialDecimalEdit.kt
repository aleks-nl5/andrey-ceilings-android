package com.andreyceilings.shared.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Insert a display zero only when this edit introduces a leading separator.
 * Deletion, caret movement and edits after an existing separator remain untouched.
 * This is an editor transformation, independent of precision and commit validation.
 */
fun normalizeInitialDecimalEdit(previous: TextFieldValue, next: TextFieldValue, signed: Boolean = false): TextFieldValue {
    if (previous.text == next.text) return next
    val position = if (signed && next.text.startsWith('-')) 1 else 0
    if (next.text.getOrNull(position) !in listOf('.', ',')) return next

    // Selection replacement/paste can look like a deletion in a minimal text diff
    // (select all of "0.5", paste ".5"). Prefer the actual selection when it fits.
    val start = previous.selection.min
    val end = previous.selection.max
    val prefix = previous.text.substring(0, start)
    val suffix = previous.text.substring(end)
    val inserted = if (next.text.length >= prefix.length + suffix.length &&
        next.text.startsWith(prefix) && next.text.endsWith(suffix)) {
        start until (next.text.length - suffix.length)
    } else {
        val commonStart = previous.text.commonPrefixWith(next.text).length
        val commonEnd = previous.text.substring(commonStart).commonSuffixWith(next.text.substring(commonStart)).length
        commonStart until (next.text.length - commonEnd)
    }
    if (position !in inserted) return next

    fun shift(range: TextRange) = TextRange(
        range.start + if (range.start >= position) 1 else 0,
        range.end + if (range.end >= position) 1 else 0)
    return next.copy(text = next.text.substring(0, position) + "0" + next.text.substring(position),
        selection = shift(next.selection), composition = next.composition?.let(::shift))
}
