package com.andreyceilings.shared.dashboard

import platform.Foundation.NSUserDefaults

/**
 * Native persistence for a future runnable iOS dashboard (#42).
 * HTTP, decoding and all refresh policy are already commonMain.
 * These rates are public data, never SQLCipher keys or business records.
 */
class IosCurrencyRatesCache : CurrencyRatesCache {
    private val preferences = NSUserDefaults.standardUserDefaults

    override fun read(): String? = preferences.stringForKey("monobank_currency_cache")

    override fun write(raw: String) {
        preferences.setObject(raw, forKey = "monobank_currency_cache")
    }
}
