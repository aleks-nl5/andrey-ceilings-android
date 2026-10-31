package com.andreyceilings.shared.prices

/**
 * Common UI projection; SQLDelight remains the source of truth.
 * ID is the stable local SQLDelight row ID, never a printable document number.
 * code identifies the semantic position for calculation/revision matching.
 */
data class PriceCatalogItem(
    val id: Long,
    val code: String,
    val title: String,
    val category: String,
    val unit: String,
    val price: Double,
    /** SQLite modification token; allows the shared editor to reject stale writes. */
    val updatedAt: Long = 0L
)

data class PriceCatalogDraft(
    val title: String,
    val category: String,
    val unit: String,
    val price: Double
)

/** Only the sale price is required; extended cost components belong to issue #37. */
object PriceCatalogRules {
    /** Suggested categories; the SQLDelight price rows remain the source of truth. */
    val standardCategories: List<String> = listOf(
        "Полотна", "Профілі", "Освітлення", "Роботи", "Додаткові роботи"
    )

    /** Include user-created categories without requiring a new database table. */
    fun categoryOptions(items: List<PriceCatalogItem>): List<String> =
        standardCategories + categories(items)
            .filter { it !in standardCategories }

    /** Common ceiling-work units; existing custom units remain available in the dropdown. */
    val standardUnits: List<String> = listOf("м²", "м.п.", "шт.", "компл.", "послуга")

    fun unitOptions(items: List<PriceCatalogItem>): List<String> =
        standardUnits + items.map { it.unit.trim() }
            .filter { it.isNotEmpty() && it !in standardUnits }
            .distinct().sortedBy { it.lowercase() }

    /**
     * Compare the unsaved editor against the state shown when it was opened.
     * Cursor/selection changes are not edits; changing from 290,00 to 290 is
     * not a monetary change either. Invalid typed text is always a draft.
     * For a new row, category and unit start with the same UI defaults.
     */
    fun hasUnsavedEdits(
        initial: PriceCatalogItem?,
        quickPriceOnly: Boolean,
        title: String,
        category: String,
        unit: String,
        salePriceText: String
    ): Boolean {
        val initialText = initial?.price?.let(::formatSalePrice).orEmpty()
        val initialNumber = parseSalePrice(initialText)
        val draftNumber = parseSalePrice(salePriceText)
        val priceChanged = if (salePriceText.trim() == initialText) {
            false
        } else if (initialNumber != null && draftNumber != null) {
            draftNumber != initialNumber
        } else {
            salePriceText.isNotBlank() || initialText.isNotBlank()
        }
        if (quickPriceOnly) return priceChanged

        return title != initial?.title.orEmpty() ||
            category != (initial?.category ?: "Додаткові роботи") ||
            unit != (initial?.unit ?: "шт.") ||
            priceChanged
    }

    private val pricePattern = Regex("""^\d{1,9}(?:[.,]\d{1,2})?$""")

    fun parseSalePrice(input: String): Double? {
        val cleaned = input.trim().replace(" ", "").replace('\u00A0', ' ')
            .replace(" ", "")
        if (!pricePattern.matches(cleaned)) return null
        return cleaned.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun validate(title: String, category: String, unit: String, salePrice: String): String? =
        when {
            title.isBlank() -> "Вкажіть назву позиції."
            category.isBlank() -> "Вкажіть категорію."
            unit.isBlank() -> "Вкажіть одиницю вимірювання."
            parseSalePrice(salePrice) == null -> "Вкажіть ціну продажу від 0 до 999 999 999,99 (до 2 знаків після коми)."
            else -> null
        }

    fun categories(items: List<PriceCatalogItem>): List<String> =
        items.map { it.category.trim() }.filter { it.isNotEmpty() }
            .distinct().sortedBy { it.lowercase() }

    fun filter(items: List<PriceCatalogItem>, query: String, category: String?): List<PriceCatalogItem> {
        val term = query.trim().lowercase()
        return items.asSequence()
            .filter { category == null || it.category == category }
            .filter { term.isEmpty() || it.title.lowercase().contains(term) || it.code.lowercase().contains(term) }
            .sortedWith(compareBy<PriceCatalogItem>({ it.category.lowercase() }, { it.title.lowercase() }, { it.id }))
            .toList()
    }

    fun formatSalePrice(value: Double): String {
        if (!value.isFinite() || value < 0.0 || value > 999_999_999.99) return "—"
        // UI display only. Saved calculations continue to use the original stored sale price.
        val cents = kotlin.math.round(value * 100.0).toLong()
        val major = cents / 100L
        val minor = (cents % 100L).toString().padStart(2, '0')
        return "$major,$minor"
    }
}
