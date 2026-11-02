package com.andreyceilings.shared.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class CeilingsEdge { TOP, BOTTOM }

object CeilingsEdgeDefaults {
    const val EdgeAlpha = 0.50f
    val FadeHeight = 36.dp
}

/** Ordered from the top of the drawing bounds to the bottom; never an opaque strip. */
internal fun ceilingsEdgeColors(
    color: Color,
    edge: CeilingsEdge,
    intensity: Float = 1f,
    edgeAlpha: Float = CeilingsEdgeDefaults.EdgeAlpha
): List<Color> {
    val shaded = color.copy(alpha = edgeAlpha.coerceIn(0f, 1f) * intensity.coerceIn(0f, 1f))
    val clear = color.copy(alpha = 0f)
    return if (edge == CeilingsEdge.TOP) listOf(shaded, clear) else listOf(clear, shaded)
}

/**
 * Drawing only: the caller owns the bounds, including its safe inset exactly once.
 * collapse contracts a top fade towards the safe-area boundary without translating it.
 * edgeAlpha is explicit only for legacy chrome that has not migrated to the foundation.
 */
@Composable
fun CeilingsEdgeGradient(
    edge: CeilingsEdge,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    color: Color = MaterialTheme.colorScheme.surface,
    collapse: Float = 0f,
    safeInset: androidx.compose.ui.unit.Dp = 0.dp,
    edgeAlpha: Float = CeilingsEdgeDefaults.EdgeAlpha
) {
    val colors = ceilingsEdgeColors(color, edge, intensity, edgeAlpha)
    Box(modifier.drawBehind {
        val boundary = safeInset.toPx().coerceIn(0f, size.height)
        val height = if (edge == CeilingsEdge.TOP)
            boundary + (size.height - boundary) * (1f - collapse.coerceIn(0f, 1f))
        else size.height
        if (height > 0f) drawRect(
            brush = Brush.verticalGradient(colors, startY = 0f, endY = height),
            size = Size(size.width, height)
        )
    })
}
