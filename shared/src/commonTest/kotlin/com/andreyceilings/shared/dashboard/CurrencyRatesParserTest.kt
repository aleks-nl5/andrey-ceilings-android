package com.andreyceilings.shared.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrencyRatesParserTest {
    private val bankReply = """
        [
          {"currencyCodeA":978,"currencyCodeB":840,"rateCross":1.1052,"date":200},
          {"currencyCodeA":840,"currencyCodeB":980,"rateBuy":41.12,"rateSell":41.89,"date":300},
          {"currencyCodeA":978,"currencyCodeB":980,"rateBuy":47.01,"rateSell":47.50,"date":400},
          {"currencyCodeA":826,"currencyCodeB":980,"rateBuy":55.0,"rateSell":56.0,"date":999}
        ]
    """.trimIndent()

    @Test
    fun selectsBankPairsByIsoCodeAndFallsBackToCross() {
        val snapshot = assertNotNull(parseMonobankRates(bankReply, null, 900_000L))
        assertEquals(listOf("USD", "EUR", "EUR/USD"), snapshot.rows.map { it.title })
        assertEquals(41.12, snapshot.rows[0].buy)
        assertEquals(47.50, snapshot.rows[1].sell)
        assertEquals(1.1052, snapshot.rows[2].buy)
        assertEquals(1.1052, snapshot.rows[2].sell)
        assertEquals(200L, snapshot.rows[2].setAtSeconds)
    }

    @Test
    fun partialBankReplyPreservesEarlierPairsButRejectsBadRows() {
        val previous = assertNotNull(parseMonobankRates(bankReply, null, 1000L))
        val partial = """[{"currencyCodeA":840,"currencyCodeB":980,"rateBuy":42.2,"rateSell":43.1}]"""
        val updated = assertNotNull(parseMonobankRates(partial, previous, 2000L))
        assertEquals(42.2, updated.rows[0].buy)
        assertEquals(300L, updated.rows[0].setAtSeconds) // Missing date is not device time.
        assertEquals(previous.rows[1], updated.rows[1])
        assertEquals(previous.rows[2], updated.rows[2])
        assertNull(parseMonobankRates("""[{"currencyCodeA":840,"currencyCodeB":980,"rateBuy":-10}]""", previous, 4000L))
        assertNull(parseMonobankRates("not json", previous, 4000L))
        assertNull(parseMonobankRates("{}", previous, 4000L))
    }

    @Test
    fun oldAndroidCacheReadsWithoutMigrationAndInvalidCacheFailsClosed() {
        val legacy = """
            {"fetchedAt":1234567,"rows":[
              {"title":"USD","symbol":"$","buy":41.2,"sell":42.1,"date":150},
              {"title":"EUR","symbol":"€","buy":45.2,"sell":46.1,"date":160},
              {"title":"EUR/USD","symbol":"€/$","buy":1.11,"sell":1.15,"date":170}
            ]}
        """.trimIndent()
        val decoded = assertNotNull(decodeCurrencyCache(legacy))
        assertEquals(3, decoded.rows.size)
        assertEquals(1234567L, decoded.fetchedAtMillis)
        assertEquals(decoded, decodeCurrencyCache(encodeCurrencyCache(decoded)))
        assertNull(decodeCurrencyCache("""{"fetchedAt":0,"rows":[]}"""))
        assertNull(decodeCurrencyCache("""{"fetchedAt":1,"rows":[{"title":"USD","buy":-2,"sell":5}]}"""))
    }

    @Test
    fun cooldownAndFormattingRespectPrecisionAndClockSkew() {
        val snapshot = CurrencySnapshot(listOf(CurrencyRate("USD", 1.0, 2.0, 1L)), 1_000L)
        assertEquals(CURRENCY_CACHE_MS, currencyRefreshRemainingMillis(snapshot, 1_000L))
        assertEquals(1L, currencyRefreshRemainingMillis(snapshot, 1_000L + CURRENCY_CACHE_MS - 1L))
        assertEquals(0L, currencyRefreshRemainingMillis(snapshot, 1_000L + CURRENCY_CACHE_MS))
        assertEquals(0L, currencyRefreshRemainingMillis(snapshot, 999L))
        assertTrue(CurrencyRatesUiState(snapshot, 1_000L + CURRENCY_CACHE_MS).canRefresh)
        assertFalse(CurrencyRatesUiState(snapshot, 1_000L, loading = true).canRefresh)
        assertEquals("41,13 ₴", formatCurrencyRate(41.126, false))
        assertEquals("1,105 $", formatCurrencyRate(1.1052, true))
    }
}
