package com.andreyceilings.shared.dashboard

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrencyRatesStoreTest {
    private val goodReply = """
        [
          {"currencyCodeA":840,"currencyCodeB":980,"rateBuy":41.5,"rateSell":42.5,"date":1000},
          {"currencyCodeA":978,"currencyCodeB":980,"rateBuy":47.1,"rateSell":48.1,"date":1000}
        ]
    """.trimIndent()

    private class FakeGateway(var response: String) : CurrencyRatesGateway {
        var cached: String? = null
        var requests: Int = 0
        var fail = false
        override fun readCache(): String? = cached
        override fun saveCache(value: String) { cached = value }
        override suspend fun fetchRatesJson(): String {
            requests++
            if (fail) throw IllegalStateException("Offline")
            return response
        }
    }

    @Test
    fun loadsBankDataOnceThrottlesRequestsAndPreservesOfflineCache() = runBlocking {
        var current = 10_000L
        val fake = FakeGateway(goodReply)
        val store = CurrencyRatesStore(fake) { current }

        store.start()
        assertEquals(1, fake.requests)
        assertEquals(2, assertNotNull(store.state.value.snapshot).rows.size)
        assertNotNull(fake.cached)
        store.refresh()
        assertEquals(1, fake.requests) // No requests during cooldown.
        current += CURRENCY_CACHE_MS
        store.tick()
        assertTrue(store.state.value.canRefresh)
        fake.fail = true
        store.refresh()
        assertEquals(2, fake.requests)
        assertEquals(2, assertNotNull(store.state.value.snapshot).rows.size)
        assertTrue(store.state.value.message!!.contains("збережені"))
        assertFalse(store.state.value.loading)

        // A failed fetch never replaces the cache and does not start cooldown.
        fake.fail = false
        store.refresh()
        assertEquals(3, fake.requests)
        assertNotNull(decodeCurrencyCache(fake.cached))
        assertFalse(store.state.value.canRefresh)

        // A recreated host uses persisted data without another HTTP request.
        val reopened = CurrencyRatesStore(fake) { current + 1L }
        reopened.start()
        assertEquals(3, fake.requests)
        assertEquals(store.state.value.snapshot?.rows, reopened.state.value.snapshot?.rows)
    }

    @Test
    fun badPayloadDoesNotDeletePreviouslySavedRates() = runBlocking {
        var current = 1_000_000L
        val fake = FakeGateway(goodReply)
        val store = CurrencyRatesStore(fake) { current }
        store.start()
        val before = store.state.value.snapshot
        current += CURRENCY_CACHE_MS
        fake.response = """[{"currencyCodeA":840,"currencyCodeB":980,"rateBuy":0}]"""
        store.refresh()
        assertEquals(before, store.state.value.snapshot)
        assertNotNull(fake.cached)
        assertEquals(before, decodeCurrencyCache(fake.cached))
    }
}
