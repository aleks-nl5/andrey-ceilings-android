package com.andreyceilings.shared.input

/** Lexical state only: COMPLETE still needs the field's unit, bounds and geometry checks.
 * Never use this policy to rewrite/filter an editor's text or selection.
 */
enum class DecimalTextState { PARTIAL, COMPLETE, INVALID }

data class DecimalTextPolicy(
    val fractionDigits: Int,
    val signed: Boolean = false,
    val wholeDigits: Int = Int.MAX_VALUE
) {
    init { require(fractionDigits >= 0 && wholeDigits > 0) }

    fun state(text: String): DecimalTextState {
        val magnitude = if (signed) text.removePrefix("-") else text
        if (magnitude.isEmpty()) return DecimalTextState.PARTIAL
        val separator = magnitude.indexOfFirst { it == '.' || it == ',' }
        val whole = if (separator < 0) magnitude else magnitude.substring(0, separator)
        val fraction = if (separator < 0) "" else magnitude.substring(separator + 1)
        if (whole.length > wholeDigits || whole.any { it !in '0'..'9' } ||
            fraction.any { it !in '0'..'9' } || fraction.length > fractionDigits ||
            (separator >= 0 && fractionDigits == 0)) return DecimalTextState.INVALID
        return if (separator >= 0 && fraction.isEmpty()) DecimalTextState.PARTIAL
            else DecimalTextState.COMPLETE
    }

    /** Commit-only canonical spelling; partial and malformed text never becomes zero. */
    fun committed(text: String): String? {
        if (state(text) != DecimalTextState.COMPLETE) return null
        val negative = text.startsWith('-')
        val magnitude = text.removePrefix("-").replace(',', '.')
        return (if (negative) "-" else "") + (if (magnitude.startsWith('.')) "0" else "") + magnitude
    }
}
