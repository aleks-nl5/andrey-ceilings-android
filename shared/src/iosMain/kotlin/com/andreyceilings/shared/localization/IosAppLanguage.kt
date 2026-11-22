package com.andreyceilings.shared.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSCurrentLocaleDidChangeNotification
import platform.Foundation.NSLocale
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplicationDidBecomeActiveNotification

class IosAppLanguageStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : AppLanguageStorage {
    override fun read(): String? = defaults.stringForKey(APP_LANGUAGE_KEY)

    override fun write(value: String) {
        defaults.setObject(value, forKey = APP_LANGUAGE_KEY)
    }
}

@Composable
fun IosAppLanguageResources(state: AppLanguageState, content: @Composable () -> Unit) {
    var tag by remember { mutableStateOf(NSLocale.preferredLanguages.firstOrNull() as? String) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(NSCurrentLocaleDidChangeNotification, UIApplicationDidBecomeActiveNotification).map { name ->
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) {
                tag = NSLocale.preferredLanguages.firstOrNull() as? String
            }
        }
        onDispose { observers.forEach { center.removeObserver(it) } }
    }
    AppLanguageResources(state.preference, tag, content)
}
