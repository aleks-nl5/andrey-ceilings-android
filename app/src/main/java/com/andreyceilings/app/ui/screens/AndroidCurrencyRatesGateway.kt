package com.andreyceilings.app.ui.screens

import android.content.Context
import com.andreyceilings.shared.dashboard.CurrencyRatesGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the legacy preference file and key: upgrading to CMP does not wipe
 * existing offline rates. These public rates are separate from SQLCipher.
 */
internal class AndroidCurrencyRatesGateway(context: Context) : CurrencyRatesGateway {
    private val preferences = context.applicationContext.getSharedPreferences(
        "currency_rates", Context.MODE_PRIVATE
    )

    override fun readCache(): String? =
        preferences.getString("monobank_currency_cache", null)

    override fun saveCache(value: String) {
        preferences.edit().putString("monobank_currency_cache", value).apply()
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
