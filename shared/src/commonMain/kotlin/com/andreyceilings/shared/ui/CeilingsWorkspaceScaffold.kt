package com.andreyceilings.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
                CeilingsEdgeGradient(CeilingsEdge.TOP, Modifier.fillMaxWidth().height(headerHeight + 56.dp)
                    .testTag("ceilings-workspace-normal-gradient"),
                    color = MaterialTheme.colorScheme.surfaceBright)
            }
            Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).onSizeChanged { measuredHeader = it.height }) {
                header(workspace, headerHeight)
            }
            overlay(headerHeight)
        }
    }
}
