package com.andreyceilings.app.ui.screens

import android.content.Context
import com.andreyceilings.shared.dashboard.CurrencyRatesCache

/**
 * The only Android-specific code: durable storage in the original preferences.
 * Existing installations keep their saved offline rates without migration.
 * HTTP, validation and refresh policy run in shared/commonMain.
 */
internal class AndroidCurrencyRatesCache(context: Context) : CurrencyRatesCache {
    private val preferences = context.applicationContext.getSharedPreferences(
        "currency_rates", Context.MODE_PRIVATE
    )

    override fun read(): String? =
        preferences.getString("monobank_currency_cache", null)

    override fun write(raw: String) {
        // Commit is synchronous: the offline cache must be durable after success.
        check(preferences.edit().putString("monobank_currency_cache", raw).commit()) {
            "Could not persist currency cache"
        }
    }
}
