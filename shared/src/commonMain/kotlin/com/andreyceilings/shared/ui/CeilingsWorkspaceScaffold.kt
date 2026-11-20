package com.andreyceilings.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.theme.CeilingsEdge
import com.andreyceilings.shared.theme.CeilingsEdgeDefaults
import com.andreyceilings.shared.theme.CeilingsEdgeGradient

/**
 * CMP foundation for a persistent drawing surface with pinned controls and a temporary overlay.
 * The host supplies safe padding once. Only the remaining IME inset is applied to the workspace.
 * The measured header is available to content in both modes. The drawing stays in the same slot.
 */
@Composable
internal fun CeilingsWorkspaceScaffold(
    safePadding: PaddingValues,
    keyboard: WindowInsets,
    fullscreen: Boolean,
    header: @Composable BoxWithConstraintsScope.(Dp) -> Unit,
    overlay: @Composable BoxWithConstraintsScope.(Dp) -> Unit,
    content: @Composable BoxWithConstraintsScope.(Dp) -> Unit
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val colors = MaterialTheme.colorScheme
    var measuredHeader by remember { mutableIntStateOf(0) }
    CeilingsScreenSurface {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(safePadding).consumeWindowInsets(safePadding).windowInsetsPadding(keyboard)
        ) {
            val workspace = this
            val headerHeight = maxOf(CeilingsFabProfile.COMPACT.touchSize, with(density) { measuredHeader.toDp() })
            content(headerHeight)
            if (fullscreen) {
                CeilingsEdgeGradient(CeilingsEdge.TOP, Modifier.fillMaxWidth().height(headerHeight + CeilingsEdgeDefaults.FadeHeight))
            } else {
                val top = safePadding.calculateTopPadding()
                val start = safePadding.calculateStartPadding(direction)
                val end = safePadding.calculateEndPadding(direction)
                CeilingsEdgeGradient(CeilingsEdge.TOP, Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val horizontal = start.roundToPx() + end.roundToPx()
                    val layer = measurable.measure(constraints.copy(
                        minWidth = constraints.minWidth + horizontal,
                        maxWidth = constraints.maxWidth + horizontal
                    ))
                    layout(constraints.maxWidth, (layer.height - top.roundToPx()).coerceAtLeast(0)) {
                        layer.placeRelative(-start.roundToPx(), -top.roundToPx())
                    }
                }.height(top + headerHeight + 72.dp).testTag("ceilings-workspace-normal-gradient"),
                    color = if (colors.background.luminance() < 0.5f) colors.surfaceBright else colors.surfaceDim)
            }
            Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).onSizeChanged { measuredHeader = it.height }) {
                header(workspace, headerHeight)
            }
            overlay(headerHeight)
        }
    }
}
