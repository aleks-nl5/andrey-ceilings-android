// CMP 1.12.2 has no public constructor for ResourceEnvironment or composition
// provider for a resource-only locale. Keep the version-specific bridge here.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.andreyceilings.shared.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.ComposeEnvironment
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.LocalComposeEnvironment
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier

internal fun ResourceEnvironment.withAppLanguage(language: AppLanguage): ResourceEnvironment =
    ResourceEnvironment(LanguageQualifier(language.tag), ScriptQualifier(""), RegionQualifier(""), theme, density)

/** Updates the resource environment without keying the content or modifying platform locale. */
@Composable
fun AppLanguageResources(
    preference: AppLanguagePreference,
    systemLanguageTag: String?,
    content: @Composable () -> Unit
) {
    val language = preference.effectiveLanguage(systemLanguageTag)
    val parentEnvironment = LocalComposeEnvironment.current
    val environment = remember(parentEnvironment, language) {
        object : ComposeEnvironment {
            @Composable
            override fun rememberEnvironment(): ResourceEnvironment {
                val parent = parentEnvironment.rememberEnvironment()
                return remember(parent, language) { parent.withAppLanguage(language) }
            }
        }
    }
    CompositionLocalProvider(LocalComposeEnvironment provides environment, content = content)
}
