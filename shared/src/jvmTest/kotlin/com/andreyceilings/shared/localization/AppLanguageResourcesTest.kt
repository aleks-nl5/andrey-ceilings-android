package com.andreyceilings.shared.localization

import com.andreyceilings.shared.resources.Res
import com.andreyceilings.shared.resources.app_name
import com.andreyceilings.shared.resources.client_project_count
import com.andreyceilings.shared.resources.navigate_action
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageResourcesTest {
    @OptIn(InternalResourceApi::class)
    @Test
    fun generatedAccessorsLoadDefaultAndUseUkrainianPluralRules() = runBlocking {
        val processLocale = Locale.getDefault()
        val env = appLanguageResourceEnvironment(
            AppLanguagePreference.System.effectiveLanguage("ja-JP"),
            ThemeQualifier.LIGHT,
            DensityQualifier.MDPI
        )
        assertEquals("СтеляПро", getString(env, Res.string.app_name))
        assertEquals("Перейти: Об’єкти", getString(env, Res.string.navigate_action, "Об’єкти"))
        for ((quantity, expected) in mapOf(
            0 to "0 об’єктів", 1 to "1 об’єкт", 2 to "2 об’єкти", 4 to "4 об’єкти",
            5 to "5 об’єктів", 11 to "11 об’єктів", 14 to "14 об’єктів",
            21 to "21 об’єкт", 22 to "22 об’єкти", 25 to "25 об’єктів"
        )) {
            assertEquals(expected, getPluralString(env, Res.plurals.client_project_count, quantity, quantity))
        }
        assertEquals(processLocale, Locale.getDefault())
    }

    @Test
    fun desktopPreferencesRoundTripAndKeepUnrelatedKeys() {
        val node = Preferences.userRoot().node("stelapro-localization-test-${UUID.randomUUID()}")
        try {
            node.put("theme_mode", "DARK")
            val explicit = AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN)
            AppLanguageState(DesktopAppLanguageStorage(node)).select(explicit)
            node.flush()
            val reopened = Preferences.userRoot().node(node.absolutePath())
            assertEquals(explicit, AppLanguageState(DesktopAppLanguageStorage(reopened)).preference)
            assertEquals("DARK", reopened.get("theme_mode", null))
        } finally {
            node.removeNode()
        }
    }
}
