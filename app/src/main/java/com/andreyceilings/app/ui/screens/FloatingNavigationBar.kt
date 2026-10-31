package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import com.andreyceilings.app.AppTab

/**
 * The established floating oval: 64dp high, edge-to-edge content still scrolls behind it.
 * Five full-height click targets remain >=48dp; 10dp internal end padding keeps icons
 * and captions away from the rounded ends. Long press shares no tap click handler.
 */
@Composable
internal fun FloatingNavigationBar(
    selectedTab: AppTab,
    darkTheme: Boolean,
    onSelect: (AppTab) -> Unit,
    onQuickCreate: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(32.dp)
    val surface = if (darkTheme) {
        lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant, 0.45f)
    } else MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = if (darkTheme) 0.46f else 0.27f)

    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .height(64.dp)
            .shadow(if (darkTheme) 8.dp else 6.dp, shape = shape, clip = false)
            .clip(shape)
            .background(surface)
            .border(1.dp, outline, shape),
        windowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                Triple(AppTab.DASHBOARD, Icons.Default.Dashboard, "Панель"),
                Triple(AppTab.OBJECTS, Icons.Default.Business, "Об’єкти"),
                Triple(AppTab.MEASUREMENTS, Icons.Default.Straighten, "Заміри"),
                // The full screen title and accessibility label remain "Калькулятор".
                Triple(AppTab.CALCULATOR, Icons.Default.Calculate, "Кальк."),
                Triple(AppTab.CALENDAR, Icons.Default.CalendarMonth, "Календар")
            )
            tabs.forEach { (tab, icon, caption) ->
                FloatingNavigationItem(
                    tab = tab,
                    icon = icon,
                    caption = caption,
                    selected = selectedTab == tab,
                    onClick = { onSelect(tab) },
                    onLongClick = if (tab == AppTab.OBJECTS || tab == AppTab.MEASUREMENTS) {
                        { onQuickCreate(tab) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingNavigationItem(
    tab: AppTab,
    icon: ImageVector,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    // One interaction source drives the native Material 3 ripple INSIDE the icon pill.
    // The full-height combinedClickable handles both taps and long presses, but does
    // not draw a second ripple over the entire tab. No selection color animation.
    val interactionSource = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val indicatorTarget = if (selected) lerp(colors.primaryContainer, colors.primary, 0.18f)
        else Color.Transparent
    // Change selection immediately: the floating capsule and typography stay
    // steady while taps still receive native Android haptic confirmation.
    val indicatorColor = indicatorTarget
    val foreground = if (selected) colors.primary else colors.onSurfaceVariant
    val quickCreateDescription = when (tab) {
        AppTab.OBJECTS -> "Створити новий об’єкт"
        AppTab.MEASUREMENTS -> "Створити новий замір"
        else -> null
    }

    Column(
        modifier = modifier.fillMaxHeight()
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClickLabel = "Перейти: ${tab.title}",
                onLongClickLabel = quickCreateDescription,
                onLongClick = onLongClick?.let { action ->
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        action()
                    }
                },
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth(0.86f).height(32.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(indicatorColor)
                // Restore the original theme-driven Material 3 touch wave, not the
                // extra full-item effect or the removed color-change animation.
                .indication(interactionSource, ripple(bounded = false)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.widthIn(min = 22.dp).height(22.dp),
                tint = foreground)
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = (-0.25).sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = foreground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
