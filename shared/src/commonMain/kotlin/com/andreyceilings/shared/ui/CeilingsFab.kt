package com.andreyceilings.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed accepted geometry; the draw area contains the complete painted shadow. */
enum class CeilingsFabProfile(
    val drawSize: Dp,
    val touchSize: Dp,
    val circleSize: Dp,
    internal val ambientRadius: Dp,
    internal val directionalRadius: Dp,
    internal val directionalOffset: Dp
) {
    CREATE(76.dp, 56.dp, 56.dp, 34.dp, 33.dp, 4.dp),
    COMPACT(56.dp, 56.dp, 42.dp, 27.dp, 24.dp, 3.dp)
}

/** One outline role for the navigation surface and compact FABs. */
fun ceilingsFloatingOutlineAlpha(darkTheme: Boolean): Float = if (darkTheme) 0.46f else 0.27f

private fun Modifier.ceilingsFabShadow(profile: CeilingsFabProfile, progress: Float) = drawBehind {
    if (progress > 0f) {
        val middle = Offset(size.width / 2f, size.height / 2f)
        val ambient = Offset(middle.x, middle.y + 1.dp.toPx())
        val radius = profile.ambientRadius.toPx()
        drawCircle(Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.07f * progress),
            0.72f to Color.Black.copy(alpha = 0.07f * progress),
            0.84f to Color.Black.copy(alpha = 0.035f * progress),
            1f to Color.Transparent, center = ambient, radius = radius
        ), radius = radius, center = ambient)
        val directional = Offset(middle.x + 1.dp.toPx(), middle.y + profile.directionalOffset.toPx())
        val directionalRadius = profile.directionalRadius.toPx()
        drawCircle(Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.14f * progress),
            0.68f to Color.Black.copy(alpha = 0.14f * progress),
            0.80f to Color.Black.copy(alpha = 0.09f * progress),
            1f to Color.Transparent, center = directional, radius = directionalRadius
        ), radius = directionalRadius, center = directional)
    }
}

/**
 * Stable CMP shadow, circular hit target and indication, without native elevation
 * or animated outline layers. Callers retain their animation/state and colors.
 * Disabled removes click semantics immediately while painted colors may fade out.
 */
@Composable
fun CeilingsFab(
    onClick: () -> Unit,
    actionLabel: String,
    modifier: Modifier = Modifier,
    profile: CeilingsFabProfile = CeilingsFabProfile.CREATE,
    progress: Float = 1f,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    outlineAlpha: Float = 0.45f,
    content: @Composable BoxScope.() -> Unit
) {
    val p = progress.coerceIn(0f, 1f)
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.size(profile.drawSize).ceilingsFabShadow(profile, p),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(profile.touchSize).clip(CircleShape).then(
            if (enabled) Modifier.clickable(
                interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = actionLabel, onClick = onClick
            ) else Modifier
        ), contentAlignment = Alignment.Center) {
            Box(Modifier.size(profile.circleSize)
                .background(containerColor.copy(alpha = p), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = outlineAlpha * p), CircleShape)
                .clip(CircleShape)
                .indication(interaction, ripple(bounded = true)),
                contentAlignment = Alignment.Center, content = content)
        }
    }
}
