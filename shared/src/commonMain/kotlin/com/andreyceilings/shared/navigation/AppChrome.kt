package com.andreyceilings.shared.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Stable cross-platform navigation descriptor. UI and navigation behavior are common CMP.
 * Android/Windows/iOS hosts choose which destinations are actually implemented.
 * Quick creation is optional; the host owns the real create workflow.
 */
data class AppDestination(
    val id: String,
    val title: String,
    val caption: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val quickCreateLabel: String? = null
)

enum class NavigationFeedback { TAP, LONG_PRESS }

val FloatingBarHeight = 64.dp
val AppHeaderHeight = 56.dp

/**
 * Floating Material 3 navigation surface used by the Android and desktop hosts.
 * Touch areas are full-height. Only the original ripple on the icon pill is drawn;
 * active state changes immediately, without extra transition animations.
 */
@Composable
fun AppFloatingNavigation(
    selectedId: String,
    destinations: List<AppDestination>,
    darkTheme: Boolean,
    onSelect: (String) -> Unit,
    onQuickCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFeedback: (NavigationFeedback) -> Unit = {},
    navigationInset: Dp = 0.dp
) {
    val oval = RoundedCornerShape(32.dp)
    val surface = if (darkTheme) {
        lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant, 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = MaterialTheme.colorScheme.outline.copy(
        alpha = if (darkTheme) 0.46f else 0.27f
    )

    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = navigationInset)
            .height(FloatingBarHeight)
            .shadow(if (darkTheme) 8.dp else 6.dp, oval, clip = false)
            .clip(oval)
            .background(surface)
            .border(1.dp, borderColor, oval),
        windowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEach { item ->
                AppFloatingItem(
                    destination = item,
                    selected = selectedId == item.id,
                    onSelect = {
                        onFeedback(NavigationFeedback.TAP)
                        onSelect(item.id)
                    },
                    onQuickCreate = {
                        onFeedback(NavigationFeedback.LONG_PRESS)
                        onQuickCreate(item.id)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppFloatingItem(
    destination: AppDestination,
    selected: Boolean,
    onSelect: () -> Unit,
    onQuickCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactions = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val indicatorColor = if (selected) {
        lerp(scheme.primaryContainer, scheme.primary, 0.18f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) scheme.primary else scheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxHeight()
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                enabled = destination.enabled,
                role = Role.Tab,
                onClickLabel = "Перейти: ${destination.title}",
                onLongClickLabel = destination.quickCreateLabel,
                onLongClick = if (destination.quickCreateLabel != null) onQuickCreate else null,
                onClick = onSelect
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.fillMaxWidth(0.86f).height(32.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(indicatorColor)
                .indication(interactions, ripple(bounded = false)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                destination.icon,
                contentDescription = null,
                tint = if (destination.enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.widthIn(min = 22.dp).height(22.dp)
            )
        }
        Text(
            destination.caption,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = (-0.25).sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (destination.enabled) contentColor else contentColor.copy(alpha = 0.38f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The app title, material actions and gradient drawing now live in commonMain.
 * Insets, scroll behavior and optional platform actions come from the host.
 *
 * As the bar collapses, the gradient contracts towards the *bottom of the status
 * bar* (rather than sliding into or underneath it). No system window APIs here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    collapsedFraction: Float,
    shadeOpacity: Float,
    statusBarHeight: Dp,
    windowInsets: WindowInsets,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onSettings: (() -> Unit)? = null,
    extraActions: @Composable () -> Unit = {}
) {
    val fadeColor = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxWidth().height(statusBarHeight + AppHeaderHeight)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(statusBarHeight + AppHeaderHeight + 36.dp)
                .drawBehind {
                    val boundary = statusBarHeight.toPx().coerceIn(0f, size.height)
                    val fraction = collapsedFraction.coerceIn(0f, 1f)
                    val lowerEdge = boundary + (size.height - boundary) * (1f - fraction)
                    if (lowerEdge > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    fadeColor.copy(alpha = shadeOpacity.coerceIn(0f, 1f)),
                                    fadeColor.copy(alpha = 0.025f)
                                ),
                                startY = 0f,
                                endY = lowerEdge
                            ),
                            size = Size(size.width, lowerEdge)
                        )
                    }
                }
        )
        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            expandedHeight = AppHeaderHeight,
            windowInsets = windowInsets,
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground
            ),
            title = {
                Column {
                    Text("СтеляПро", fontWeight = FontWeight.Bold)
                    Text("Майстер натяжних стель", style = MaterialTheme.typography.labelSmall)
                }
            },
            actions = {
                extraActions()
                if (onSettings != null) {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Налаштування")
                    }
                }
            }
        )
    }
}
