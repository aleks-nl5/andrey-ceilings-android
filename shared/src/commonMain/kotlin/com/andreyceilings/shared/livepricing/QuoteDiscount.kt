package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.input.DecimalTextPolicy

@kotlinx.serialization.Serializable
data class QuoteAmounts(val subtotalCents: Long, val discountBasisPoints: Int,
    val discountCents: Long, val finalCents: Long)

object QuoteDiscount {
    fun parseBasisPoints(text: String): Int? {
        val committed = DecimalTextPolicy(2).committed(text.trim()) ?: return null
        val parts = committed.replace(',', '.').split('.')
        val whole = parts[0].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.toIntOrNull() ?: 0
        return (whole * 100 + fraction).takeIf { it in 0..10000 }
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
