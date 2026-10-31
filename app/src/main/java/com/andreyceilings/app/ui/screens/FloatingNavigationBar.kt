package com.andreyceilings.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import com.andreyceilings.app.AppTab
import com.andreyceilings.shared.navigation.AppDestination
import com.andreyceilings.shared.navigation.AppFloatingNavigation
import com.andreyceilings.shared.navigation.NavigationFeedback

/**
 * Android-only adapter: tab routing, system inset and native haptics.
 * Layout, shape, selected state, original M3 ripple and long-press mechanics
 * are implemented once in shared/commonMain/navigation/AppChrome.kt.
 */
@Composable
internal fun FloatingNavigationBar(
    selectedTab: AppTab,
    darkTheme: Boolean,
    onSelect: (AppTab) -> Unit,
    onQuickCreate: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val androidView = LocalView.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val destinations = remember {
        listOf(
            AppDestination(AppTab.DASHBOARD.name, AppTab.DASHBOARD.title, "Панель", Icons.Default.Dashboard),
            AppDestination(
                AppTab.OBJECTS.name, AppTab.OBJECTS.title, "Об’єкти", Icons.Default.Business,
                quickCreateLabel = "Створити новий об’єкт"
            ),
            AppDestination(
                AppTab.MEASUREMENTS.name, AppTab.MEASUREMENTS.title, "Заміри", Icons.Default.Straighten,
                quickCreateLabel = "Створити новий замір"
            ),
            AppDestination(AppTab.CALCULATOR.name, AppTab.CALCULATOR.title, "Кальк.", Icons.Default.Calculate),
            AppDestination(AppTab.CALENDAR.name, AppTab.CALENDAR.title, "Календар", Icons.Default.CalendarMonth)
        )
    }

    AppFloatingNavigation(
        selectedId = selectedTab.name,
        destinations = destinations,
        darkTheme = darkTheme,
        onSelect = { id -> onSelect(AppTab.valueOf(id)) },
        onQuickCreate = { id -> onQuickCreate(AppTab.valueOf(id)) },
        onFeedback = { feedback ->
            androidView.performHapticFeedback(
                when (feedback) {
                    NavigationFeedback.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
                    NavigationFeedback.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
                }
            )
        },
        navigationInset = navigationInset,
        modifier = modifier
    )
}
