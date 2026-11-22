package com.andreyceilings.shared.localization

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IosAppLanguageStorageTest {
    @Test
    fun selectionPersistsInTheAppSuiteAndPreservesOtherPreferences() {
        val suite = "StelaProLocalizationTests.${NSUUID().UUIDString}"
        val defaults = NSUserDefaults(suiteName = suite)
        try {
            defaults.setObject("DARK", forKey = "theme_mode")
            val explicit = AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN)
            val state = AppLanguageState(IosAppLanguageStorage(defaults))
            state.select(explicit)
            val reloaded = AppLanguageState(IosAppLanguageStorage(NSUserDefaults(suiteName = suite)))
            assertEquals(explicit, reloaded.preference)
            reloaded.select(AppLanguagePreference.System)
            assertEquals(AppLanguagePreference.System, AppLanguageState(IosAppLanguageStorage(defaults)).preference)
            assertEquals("DARK", defaults.stringForKey("theme_mode"))
        } finally {
            defaults.removePersistentDomainForName(suite)
        }
    }
}
