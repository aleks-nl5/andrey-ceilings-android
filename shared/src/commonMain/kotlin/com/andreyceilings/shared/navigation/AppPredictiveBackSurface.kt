package com.andreyceilings.shared.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Shared Compose Multiplatform visual response to an in-app Back gesture.
 * Hosts provide native progress, while KMP commits navigation only on completion.
 * This shows the destination label, not a mock previous screen.
 */
@Composable
fun AppPredictiveBackSurface(
    progress: State<Float>,
    fromLeftEdge: State<Boolean>,
    destinationTitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        Text(
            text = "Назад до\n$destinationTitle",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(if (fromLeftEdge.value) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 20.dp)
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
        )
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val fraction = progress.value.coerceIn(0f, 1f)
                val direction = if (fromLeftEdge.value) 1f else -1f
                translationX = direction * size.width * 0.25f * fraction
                scaleX = 1f - 0.035f * fraction
                scaleY = 1f - 0.035f * fraction
                shape = RoundedCornerShape((20f * fraction).dp)
                clip = fraction > 0.001f
            }
        ) {
            content()
        }
    }
}
