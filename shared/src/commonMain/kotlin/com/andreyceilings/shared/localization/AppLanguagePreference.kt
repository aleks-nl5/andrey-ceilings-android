package com.andreyceilings.shared.localization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(val tag: String) {
    UKRAINIAN("uk");

    companion object {
        // Ordinary BCP 47 tags (primary language, script, region, variants and extensions).
        // All supported Ukrainian variants resolve to the canonical product language.
        private val ukrainianTag = Regex(
            """uk(?:-[a-z]{4})?(?:-(?:[a-z]{2}|[0-9]{3}))?""" +
                """((?:-(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*)""" +
                """((?:-[0-9a-wy-z](?:-[a-z0-9]{2,8})+)*)(?:-x(?:-[a-z0-9]{1,8})+)?"""
        )

        fun fromTag(tag: String?): AppLanguage? {
            val normalized = tag?.trim()?.replace('_', '-')?.lowercase() ?: return null
            val match = ukrainianTag.matchEntire(normalized) ?: return null
            val variants = match.groupValues[1].split('-').filter(String::isNotEmpty)
            val extensions = match.groupValues[2].split('-').filter { it.length == 1 }
            if (variants.size != variants.toSet().size || extensions.size != extensions.toSet().size) return null
            return UKRAINIAN
        }
    }
}

sealed interface AppLanguagePreference {
    data object System : AppLanguagePreference
    data class Explicit(val language: AppLanguage) : AppLanguagePreference

    fun effectiveLanguage(systemLanguageTag: String?): AppLanguage = when (this) {
        System -> AppLanguage.fromTag(systemLanguageTag) ?: AppLanguage.UKRAINIAN
        is Explicit -> language
    }

    companion object {
        fun fromStoredValue(value: String?): AppLanguagePreference =
            AppLanguage.fromTag(value)?.let(::Explicit) ?: System
    }
}

interface AppLanguageStorage {
    fun read(): String?
    fun write(value: String)
}

internal const val APP_LANGUAGE_KEY = "app_language"

class AppLanguageState(private val storage: AppLanguageStorage) {
    var preference by mutableStateOf(AppLanguagePreference.fromStoredValue(storage.read()))
        private set

    fun select(value: AppLanguagePreference) {
        if (value == preference) return
        val storedValue = when (value) {
            AppLanguagePreference.System -> "system"
            is AppLanguagePreference.Explicit -> value.language.tag
        }
        storage.write(storedValue)
        preference = value
    }
}
