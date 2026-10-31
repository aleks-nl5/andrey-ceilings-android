package com.andreyceilings.shared.dashboard

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/** Public cache storage: only OS-specific persistence is supplied by hosts. */
interface CurrencyRatesCache {
    fun read(): String?
    fun write(raw: String)
}

/**
 * Real cross-platform HTTP and shared cache gateway.
 * Ktor chooses the supplied platform engine (Android / JVM / Darwin).
 * Inject a MockEngine-backed HttpClient for deterministic network tests.
 */
class MonobankCurrencyGateway(
    private val cache: CurrencyRatesCache,
    private val httpClient: HttpClient = monobankHttpClient()
) : CurrencyRatesGateway {
    override fun readCache(): String? = cache.read()

    override fun saveCache(value: String) = cache.write(value)

    override suspend fun fetchRatesJson(): String =
        httpClient.get("https://api.monobank.ua/bank/currency").bodyAsText()

    fun close() {
        httpClient.close()
    }
}

fun monobankHttpClient(): HttpClient = HttpClient {
    expectSuccess = true // Never parse an HTTP error body as valid rates.
    install(HttpTimeout) {
        requestTimeoutMillis = 8_000
        connectTimeoutMillis = 8_000
    }
}
