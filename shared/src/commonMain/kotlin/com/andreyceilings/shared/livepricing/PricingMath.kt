package com.andreyceilings.shared.livepricing

import kotlin.math.abs
import kotlin.math.round

/** No floating-point multiplication of monetary amounts. */
object PricingMath {
    const val MAX_PRICE_CENTS = 99_999_999_999L

    fun salePriceCents(value: Double): Long? {
        if (!value.isFinite() || value < 0 || value > 999_999_999.99) return null
        val cents = round(value * 100).toLong()
        // A REAL representing an exact two-decimal amount must round-trip exactly.
        return cents.takeIf { value == it.toDouble() / 100 && it in 0..MAX_PRICE_CENTS }
    }

    fun capturedMilli(value: Double): Long? {
        if (!value.isFinite() || value <= 0 || value > 9_000_000_000_000.0) return null
        val scaled = value * 1000
        val integer = round(scaled)
        // Only binary representation noise of the geometry engine, never a fourth decimal.
        return integer.toLong().takeIf { it > 0 && abs(scaled - integer) <= 0.000001 }
    }

    fun add(a: Long, b: Long): Long {
        require(a >= 0 && b >= 0)
        if (a > Long.MAX_VALUE - b) throw PricingOverflow()
        return a + b
    }

    private fun multiply(a: Long, b: Long): Long {
        if (a != 0L && b > Long.MAX_VALUE / a) throw PricingOverflow()
        return a * b
    }

    /** Positive amounts: half-away-from-zero = half-up, exactly once per row.
     * Split first so an overflowing cents*milli intermediate does not reject a safe result.
     */
    fun lineCents(cents: Long, milli: Long): Long {
        require(cents in 0..MAX_PRICE_CENTS && milli in 1..9_000_000_000_000_000L)
        val main = multiply(cents / 1000, milli)
        val remainder = multiply(cents % 1000, milli)
        return add(main, add(remainder / 1000, if (remainder % 1000 >= 500) 1 else 0))
    }
}
internal class PricingOverflow : ArithmeticException()
