package com.andreyceilings.shared.localization

import java.util.prefs.Preferences

class DesktopAppLanguageStorage(private val preferences: Preferences) : AppLanguageStorage {
    override fun read(): String? = preferences.get(APP_LANGUAGE_KEY, null)

    override fun write(value: String) {
        preferences.put(APP_LANGUAGE_KEY, value)
    }
}
