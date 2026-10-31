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
    val price: Double
)

data class PriceCatalogDraft(
    val title: String,
    val category: String,
    val unit: String,
    val price: Double
)

/** Only the sale price is required; extended cost components belong to issue #37. */
object PriceCatalogRules {
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
