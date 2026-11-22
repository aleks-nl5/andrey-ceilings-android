package com.andreyceilings.shared.localization

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

class AndroidAppLanguageStorage(private val preferences: SharedPreferences) : AppLanguageStorage {
    override fun read(): String? = preferences.all[APP_LANGUAGE_KEY] as? String

    override fun write(value: String) {
        preferences.edit().putString(APP_LANGUAGE_KEY, value).apply()
    }

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    )
}

@Composable
fun AndroidAppLanguageResources(state: AppLanguageState, content: @Composable () -> Unit) {
    val locales = LocalConfiguration.current.locales
    AppLanguageResources(state.preference, locales[0]?.toLanguageTag(), content)
}
