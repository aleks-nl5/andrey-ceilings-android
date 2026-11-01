package com.andreyceilings.desktop

import com.andreyceilings.shared.dashboard.CurrencyRatesCache
import java.util.prefs.Preferences

/** Per-user OS preferences only; public currency rates are outside SQLCipher. */
internal class DesktopCurrencyRatesCache : CurrencyRatesCache {
    private val preferences = Preferences.userNodeForPackage(DesktopCurrencyRatesCache::class.java)

    override fun read(): String? = preferences.get("monobank_currency_cache", null)

    override fun write(raw: String) {
        preferences.put("monobank_currency_cache", raw)
        preferences.flush()
    }
}
