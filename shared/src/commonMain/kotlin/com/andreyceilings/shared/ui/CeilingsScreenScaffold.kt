package com.andreyceilings.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.theme.CeilingsEdge
import com.andreyceilings.shared.theme.CeilingsEdgeDefaults
import com.andreyceilings.shared.theme.CeilingsEdgeGradient

private enum class Layer { HEADER, PINNED, BOTTOM, CONTENT, TOP_FADE, BOTTOM_FADE, OVERLAY }

/** Padding for an edge-to-edge list: viewport stays full-size, only items are inset. */
internal fun ceilingsContentPadding(
    base: PaddingValues,
    safe: PaddingValues,
    direction: androidx.compose.ui.unit.LayoutDirection,
    header: Dp,
    pinned: Dp,
    bottom: Dp
): PaddingValues = PaddingValues(
    start = base.calculateStartPadding(direction) + safe.calculateStartPadding(direction),
    end = base.calculateEndPadding(direction) + safe.calculateEndPadding(direction),
    top = base.calculateTopPadding() + safe.calculateTopPadding() + header + pinned,
    bottom = base.calculateBottomPadding() + safe.calculateBottomPadding() + bottom
)

/**
 * Compact CMP layering contract. Header/pinned/navigation are measured BEFORE
 * list composition: the very first frame and resize use actual foreground sizes.
 * Pass padding to LazyColumn.contentPadding (or inside verticalScroll), never to
 * the viewport. The scaffold does not own list state, nested scroll or snapping.
 *
 * Defaults use safeDrawing UNION ime, not their sum. A nested scaffold supplied
 * with host-computed padding must explicitly use WindowInsets(0, 0, 0, 0).
 * Insets are consumed for descendants; content remains drawable under overlays.
 */
@Composable
fun CeilingsScreenScaffold(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    windowInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
    topGradient: Boolean = true,
    topGradientIntensity: Float = 1f,
    bottomGradient: Boolean = true,
    header: @Composable () -> Unit = {},
    pinned: @Composable () -> Unit = {},
    bottomNavigation: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit
) {
    val safe = windowInsets.asPaddingValues()
    val direction = LocalLayoutDirection.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        SubcomposeLayout(Modifier.fillMaxSize().consumeWindowInsets(windowInsets)) { constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val safeTop = safe.calculateTopPadding().roundToPx()
            val safeBottom = safe.calculateBottomPadding().roundToPx()
            val foregroundConstraints = constraints.copy(minWidth = width, minHeight = 0,
                maxHeight = (height - safeTop - safeBottom).coerceAtLeast(0))
            fun measure(layer: Layer, slot: @Composable () -> Unit) =
                subcompose(layer) { Box(Modifier.fillMaxWidth()
                    .padding(start = safe.calculateStartPadding(direction),
                        end = safe.calculateEndPadding(direction))) { slot() } }
                    .single().measure(foregroundConstraints)
            val headerPlaceable = measure(Layer.HEADER, header)
            val pinnedPlaceable = measure(Layer.PINNED) { CeilingsPinnedContent { pinned() } }
            val bottomPlaceable = measure(Layer.BOTTOM, bottomNavigation)
            val padding = ceilingsContentPadding(contentPadding, safe, direction,
                headerPlaceable.height.toDp(), pinnedPlaceable.height.toDp(), bottomPlaceable.height.toDp())
            val full = constraints.copy(minWidth = width, minHeight = height)
            val contentPlaceable = subcompose(Layer.CONTENT) {
                Box(Modifier.fillMaxSize()) { content(padding) }
            }.single().measure(full)
            val topHeight = (safeTop + headerPlaceable.height + pinnedPlaceable.height).toDp()
            val topFade = if (topGradient) subcompose(Layer.TOP_FADE) {
                CeilingsEdgeGradient(CeilingsEdge.TOP, Modifier.fillMaxWidth()
                    .height(topHeight + CeilingsEdgeDefaults.FadeHeight), intensity = topGradientIntensity)
            }.single().measure(constraints.copy(minHeight = 0)) else null
            val bottomFade = if (bottomGradient) subcompose(Layer.BOTTOM_FADE) {
                CeilingsEdgeGradient(CeilingsEdge.BOTTOM, Modifier.fillMaxWidth()
                    .height(safeBottom.toDp() + CeilingsEdgeDefaults.FadeHeight))
            }.single().measure(constraints.copy(minHeight = 0)) else null
            val overlayPlaceable = subcompose(Layer.OVERLAY) {
                Box(Modifier.fillMaxSize(), content = overlay)
            }.single().measure(full)
            layout(width, height) {
                contentPlaceable.placeRelative(0, 0)
                topFade?.placeRelative(0, 0)
                bottomFade?.placeRelative(0, height - bottomFade.height)
                headerPlaceable.placeRelative(0, safeTop)
                pinnedPlaceable.placeRelative(0, safeTop + headerPlaceable.height)
                bottomPlaceable.placeRelative(0, height - safeBottom - bottomPlaceable.height)
                overlayPlaceable.placeRelative(0, 0)
            }
        }
    }
}
