package com.andreyceilings.shared.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MonobankCurrencyGatewayTest {
    private class InMemoryCache : CurrencyRatesCache {
        var raw: String? = null
        override fun read(): String? = raw
        override fun write(raw: String) { this.raw = raw }
    }

    @Test
    fun httpUsesSharedHttpsEndpointAndCacheDelegatesToStorage(): Unit = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests += 1
            assertEquals("https://api.monobank.ua/bank/currency", request.url.toString())
            respond(
                """[{"currencyCodeA":840,"currencyCodeB":980,"rateBuy":41.0,"rateSell":42.0,"date":123}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }) { expectSuccess = true }
        val cache = InMemoryCache()
        val gateway = MonobankCurrencyGateway(cache, client)
        try {
            assertEquals(null, gateway.readCache())
            gateway.saveCache("old-Android-compatible-json")
            assertEquals("old-Android-compatible-json", gateway.readCache())
            val response = gateway.fetchRatesJson()
            assertTrue(response.contains("currencyCodeA"))
            assertEquals(1, requests)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun rejectsHttpFailuresInsteadOfTryingToParseTheErrorPage(): Unit = runBlocking {
        val client = HttpClient(MockEngine {
            respond("upstream unavailable", HttpStatusCode.ServiceUnavailable)
        }) { expectSuccess = true }
        val gateway = MonobankCurrencyGateway(InMemoryCache(), client)
        try {
            assertFailsWith<io.ktor.client.plugins.ServerResponseException> {
                gateway.fetchRatesJson()
            }
        } finally {
            gateway.close()
        }
    }
}
