package com.andreyceilings.desktop

import com.andreyceilings.shared.dashboard.CurrencyRatesGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.prefs.Preferences

/** Public-rate cache in per-user OS preferences, outside SQLCipher business data. */
internal class DesktopCurrencyRatesGateway : CurrencyRatesGateway {
    private val preferences = Preferences.userNodeForPackage(DesktopCurrencyRatesGateway::class.java)

    override fun readCache(): String? =
        preferences.get("monobank_currency_cache", null)

    override fun saveCache(value: String) {
        preferences.put("monobank_currency_cache", value)
        preferences.flush()
    }

    override suspend fun fetchRatesJson(): String = withContext(Dispatchers.IO) {
        val connection = URL("https://api.monobank.ua/bank/currency")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
