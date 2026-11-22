package com.andreyceilings.shared.localization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppLanguagePreferenceTest {
    @Test
    fun supportedTagsNormalizeAndUnsupportedSystemLanguagesFallBack() {
        val ukrainian = AppLanguage.UKRAINIAN
        val explicit = AppLanguagePreference.Explicit(ukrainian)
        for (tag in listOf("uk", " UK_ua ", "uk-Cyrl-UA", "uk-UA-u-nu-latn", "uk-x-local")) {
            assertEquals(ukrainian, AppLanguage.fromTag(tag))
            assertEquals(explicit, AppLanguagePreference.fromStoredValue(tag))
        }
        for (tag in listOf(
            null, "", "system", "uk-!", "uk-UA-invalid-too-long-here", "uk-UA-u",
            "uk-variant-variant", "uk-u-ca-gregory-u-nu-latn", "en-US", "pl"
        )) {
            assertNull(AppLanguage.fromTag(tag))
            assertSame(AppLanguagePreference.System, AppLanguagePreference.fromStoredValue(tag))
            assertEquals(ukrainian, AppLanguagePreference.System.effectiveLanguage(tag))
            assertEquals(ukrainian, explicit.effectiveLanguage(tag))
        }
    }

    @Test
    fun selectionUpdatesStateAndPersistsAcrossFreshStoreReads() {
        val values = mutableMapOf("theme_mode" to "DARK", "measurement_precision" to "FIVE_MILLIMETERS")
        val storage = object : AppLanguageStorage {
            override fun read(): String? = values[APP_LANGUAGE_KEY]
            override fun write(value: String) {
                values[APP_LANGUAGE_KEY] = value
            }
        }
        val state = AppLanguageState(storage)
        assertSame(AppLanguagePreference.System, state.preference)
        val explicit = AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN)
        state.select(explicit)
        assertEquals(explicit, state.preference)
        assertEquals(explicit, AppLanguageState(storage).preference)
        assertEquals("uk", values[APP_LANGUAGE_KEY])
        state.select(AppLanguagePreference.System)
        assertSame(AppLanguagePreference.System, state.preference)
        assertSame(AppLanguagePreference.System, AppLanguageState(storage).preference)
        assertEquals(
            mapOf("theme_mode" to "DARK", "measurement_precision" to "FIVE_MILLIMETERS", APP_LANGUAGE_KEY to "system"),
            values
        )
    }
}
