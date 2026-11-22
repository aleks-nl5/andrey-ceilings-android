package com.andreyceilings.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.localization.AndroidAppLanguageResources
import com.andreyceilings.shared.localization.AndroidAppLanguageStorage
import com.andreyceilings.shared.localization.AppLanguage
import com.andreyceilings.shared.localization.AppLanguagePreference
import com.andreyceilings.shared.localization.AppLanguageSettings
import com.andreyceilings.shared.localization.AppLanguageState
import com.andreyceilings.shared.navigation.AppHeader
import com.andreyceilings.shared.objects.ObjectsClientsRows
import com.andreyceilings.shared.objects.ObjectsClientsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppLanguageUiTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun unsupportedAndroidLocaleUsesUkrainianResourcesAndSelectionKeepsActiveDraft() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("localization_ui_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("theme_mode", "DARK").commit()
        val state = AppLanguageState(AndroidAppLanguageStorage(prefs))
        val before = Locale.getDefault()
        val explicit = AppLanguagePreference.Explicit(AppLanguage.UKRAINIAN)
        try {
            compose.setContent {
                val japanese = Configuration(LocalConfiguration.current).apply { setLocale(Locale.JAPAN) }
                CompositionLocalProvider(LocalConfiguration provides japanese) {
                    AndroidAppLanguageResources(state) {
                        CeilingsTheme {
                            Column {
                                AppHeader(0f, 0f, 0.dp, WindowInsets(0, 0, 0, 0), onSettings = {})
                                AppLanguageSettings(state)
                                var draft by rememberSaveable { mutableStateOf("") }
                                TextField(
                                    draft, { draft = it }, Modifier.testTag("draft"),
                                    keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false)
                                )
                                ObjectsClientsScreen(
                                    rows = ObjectsClientsRows(
                                        projects = emptyList(),
                                        clients = listOf(ClientListEntry(1L, "Збережене ім’я", "", "", "", 22))
                                    ),
                                    detailsProjectId = null,
                                    detailsStore = null,
                                    onOpenProject = {},
                                    onCloseProject = {},
                                    databaseAvailable = true,
                                    onSaveClient = { _, _ -> },
                                    onSaveProject = { _, _ -> },
                                    onDeleteClient = {},
                                    onDeleteProject = {},
                                    onDial = {},
                                    onExternalAction = { _, _ -> }
                                )
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Майстер натяжних стель").assertIsDisplayed()
            compose.onNodeWithContentDescription("Налаштування").assertIsDisplayed()
            compose.onNodeWithText("Системна").assertIsDisplayed()
            compose.onNodeWithText("Клієнти").performClick()
            compose.onNodeWithText("22 об’єкти").assertIsDisplayed()
            compose.onNodeWithTag("draft").performClick().performTextInput("0.5")
            compose.runOnIdle { state.select(explicit) }
            compose.onNodeWithTag("draft").assertIsFocused()
            compose.onNodeWithText("0.5").assertIsDisplayed()
            compose.onNodeWithText("22 об’єкти").assertIsDisplayed()
            compose.onNodeWithText("Українська").assertIsDisplayed()
            assertEquals(explicit, AppLanguageState(AndroidAppLanguageStorage(prefs)).preference)
            assertEquals("DARK", prefs.getString("theme_mode", null))
            assertEquals(before, Locale.getDefault())

            compose.onNodeWithText("Мова").performClick()
            compose.onNodeWithText("Системна").performClick()
            compose.waitForIdle()
            assertEquals(AppLanguagePreference.System, AppLanguageState(AndroidAppLanguageStorage(prefs)).preference)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
