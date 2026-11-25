package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.input.DecimalTextPolicy

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
enum class QuoteDiscountMode { PERCENT, FIXED }

sealed interface QuoteDiscountValue {
    data class Percentage(val basisPoints: Int) : QuoteDiscountValue
    data class Fixed(val cents: Long) : QuoteDiscountValue
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class QuoteAmounts(
    val subtotalCents: Long,
    val discountBasisPoints: Int,
    val discountCents: Long,
    val finalCents: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val discountMode: QuoteDiscountMode = QuoteDiscountMode.PERCENT
)

object QuoteDiscount {
    fun parseBasisPoints(text: String): Int? {
        val committed = DecimalTextPolicy(2).committed(text.trim()) ?: return null
        val parts = committed.replace(',', '.').split('.')
        val whole = parts[0].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.toIntOrNull() ?: 0
        return (whole * 100 + fraction).takeIf { it in 0..10000 }
    }

    fun parse(text: String, mode: QuoteDiscountMode): QuoteDiscountValue? = when (mode) {
        QuoteDiscountMode.PERCENT -> parseBasisPoints(text)?.let { QuoteDiscountValue.Percentage(it) }
        QuoteDiscountMode.FIXED -> parseFixedCents(text)?.let { QuoteDiscountValue.Fixed(it) }
    }

    private fun parseFixedCents(text: String): Long? {
        val committed = DecimalTextPolicy(2).committed(text.trim()) ?: return null
        val parts = committed.replace(',', '.').split('.')
        val whole = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
        if (whole > (Long.MAX_VALUE - fraction) / 100) return null
        return whole * 100 + fraction
    }

    fun calculate(subtotalCents: Long, discount: QuoteDiscountValue): QuoteAmounts? = when (discount) {
        is QuoteDiscountValue.Percentage -> calculate(subtotalCents, discount.basisPoints)
        is QuoteDiscountValue.Fixed -> discount.cents.takeIf { it in 0..subtotalCents }?.let { cents ->
            // Zero is the compatibility column value. The exact payload carries the explicit fixed mode.
            QuoteAmounts(subtotalCents, 0, cents, subtotalCents - cents, QuoteDiscountMode.FIXED)
        }
    }

    /** Split subtotal first. Every product and sum is bounded by subtotal, even at Long.MAX_VALUE. */
    fun calculate(subtotalCents: Long, basisPoints: Int): QuoteAmounts {
        require(subtotalCents >= 0 && basisPoints in 0..10000)
        val remainder = (subtotalCents % 10000) * basisPoints
        val discount = (subtotalCents / 10000) * basisPoints + remainder / 10000 +
            if (remainder % 10000 >= 5000) 1 else 0
        return QuoteAmounts(subtotalCents, basisPoints, discount, subtotalCents - discount)
    }
}
