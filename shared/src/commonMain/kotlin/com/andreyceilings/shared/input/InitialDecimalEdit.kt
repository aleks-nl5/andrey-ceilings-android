package com.andreyceilings.shared.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Keep a display zero before a leading separator after a text edit. */
fun normalizeInitialDecimalEdit(previous: TextFieldValue, next: TextFieldValue, signed: Boolean = false): TextFieldValue {
    if (previous.text == next.text) return next
    val position = if (signed && next.text.startsWith('-')) 1 else 0
    if (next.text.getOrNull(position) !in listOf('.', ',')) return next

    fun shift(range: TextRange) = TextRange(
        range.start + if (range.start >= position) 1 else 0,
        range.end + if (range.end >= position) 1 else 0)
    return next.copy(text = next.text.substring(0, position) + "0" + next.text.substring(position),
        selection = shift(next.selection), composition = next.composition?.let(::shift))
}

/** Filters only the inserted fragment of a user edit, preserving atomic selection replacement. */
fun filterNumericEdit(previous: TextFieldValue, next: TextFieldValue, signed: Boolean = false,
    decimal: Boolean = true): TextFieldValue {
    if (previous.text == next.text) return next
    var start = previous.selection.min
    var end = previous.selection.max
    var prefix = previous.text.substring(0, start)
    var suffix = previous.text.substring(end)
    if (next.text.length < prefix.length + suffix.length ||
        !next.text.startsWith(prefix) || !next.text.endsWith(suffix)) {
        val composition = previous.composition
        val compositionPrefix = composition?.let { previous.text.substring(0, it.min) }
        val compositionSuffix = composition?.let { previous.text.substring(it.max) }
        if (composition != null && next.text.length >= compositionPrefix!!.length + compositionSuffix!!.length &&
            next.text.startsWith(compositionPrefix) && next.text.endsWith(compositionSuffix)) {
            start = composition.min
            end = composition.max
        } else {
            start = previous.text.commonPrefixWith(next.text).length
            val commonEnd = previous.text.substring(start).commonSuffixWith(next.text.substring(start)).length
            end = previous.text.length - commonEnd
        }
        prefix = previous.text.substring(0, start)
        suffix = previous.text.substring(end)
    }
    val insertedEnd = next.text.length - suffix.length
    val fragment = next.text.substring(start, insertedEnd)
    if (fragment.isEmpty()) return if (decimal) normalizeInitialDecimalEdit(previous, next, signed) else next
    // Inserting before an existing sign would move it away from the start.
    if (prefix.isEmpty() && suffix.startsWith('-')) return previous
    var hasSeparator = (prefix + suffix).any { it == '.' || it == ',' }
    var hasSign = (prefix + suffix).contains('-')
    val kept = StringBuilder()
    val offsets = IntArray(fragment.length + 1)
    fragment.forEachIndexed { index, ch ->
        val accept = when {
            ch in '0'..'9' -> true
            decimal && (ch == '.' || ch == ',') && !hasSeparator -> { hasSeparator = true; true }
            signed && ch == '-' && !hasSign && prefix.isEmpty() && kept.isEmpty() -> { hasSign = true; true }
            else -> false
        }
        if (accept) kept.append(ch)
        offsets[index + 1] = kept.length
    }
    if (kept.isEmpty()) return previous
    val text = prefix + kept + suffix
    fun map(offset: Int): Int = when {
        offset <= start -> offset
        offset < insertedEnd -> start + offsets[offset - start]
        else -> offset - fragment.length + kept.length
    }.coerceIn(0, text.length)
    fun map(range: TextRange) = TextRange(map(range.start), map(range.end))
    val filtered = next.copy(text = text, selection = map(next.selection), composition = next.composition?.let(::map))
    return if (decimal) normalizeInitialDecimalEdit(previous, filtered, signed) else filtered
}
