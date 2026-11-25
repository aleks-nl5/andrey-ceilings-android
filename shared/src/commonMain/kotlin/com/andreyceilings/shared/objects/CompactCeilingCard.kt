package com.andreyceilings.shared.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import com.andreyceilings.shared.measurement.formatMeasurementAreaCompact
import com.andreyceilings.shared.measurement.formatMeasurementLengthCompact
import com.andreyceilings.shared.measurement.measurementDrawingProjection
import com.andreyceilings.shared.measurement.workspaceStatusAppearance
import com.andreyceilings.shared.measurement.workspaceStatusLabel
import com.andreyceilings.shared.resources.Res
import com.andreyceilings.shared.resources.p5b_live_area_value
import com.andreyceilings.shared.resources.p5b_live_perimeter_value
import com.andreyceilings.shared.resources.p5b_live_area_label
import com.andreyceilings.shared.resources.p5b_live_perimeter_label
import com.andreyceilings.shared.resources.p5d_unknown
import com.andreyceilings.shared.resources.p6a_amount_uah
import com.andreyceilings.shared.resources.p6a_ceiling_contour
import com.andreyceilings.shared.resources.p6a_contour_unavailable
import com.andreyceilings.shared.resources.p6a_finance_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun compactQuoteAmount(cents: Long): String =
    stringResource(Res.string.p6a_amount_uah, cents / 100, (cents % 100).toString().padStart(2, '0'))

@Composable
internal fun CompactCeilingCard(
    ceiling: ProjectMeasurement,
    contour: MeasurementDrawingContour?,
    quote: CeilingQuoteSummary?,
    quoteUnavailable: Boolean,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CeilingThumbnail(contour, Modifier.size(width = 68.dp, height = 52.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ceiling.name, style = MaterialTheme.typography.titleSmall)
                CompactMetricsRow(ceiling.area, ceiling.perimeter,
                    trailing = if (quote != null) {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(compactQuoteAmount(quote.finalCents), style = MaterialTheme.typography.bodySmall)
                                val (icon, tint) = workspaceStatusAppearance(quote.status)
                                Icon(icon, workspaceStatusLabel(quote.status), Modifier.size(14.dp), tint = tint)
                            }
                        }
                    } else if (quoteUnavailable) {
                        {
                            Icon(Icons.Default.Warning, stringResource(Res.string.p6a_finance_unavailable),
                                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    } else null
                )
            }
        }
    }
}

/** Measure complete values; only wrap the trailing amount when the row cannot fit. */
@Composable
internal fun CompactMetricsRow(area: Double?, perimeter: Double?, trailing: (@Composable () -> Unit)? = null) {
    val unknown = stringResource(Res.string.p5d_unknown)
    val areaValue = stringResource(Res.string.p5b_live_area_value, area?.let(::formatMeasurementAreaCompact) ?: unknown)
    val perimeterValue = stringResource(Res.string.p5b_live_perimeter_value, perimeter?.let(::formatMeasurementLengthCompact) ?: unknown)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                CompactMetric(Icons.Default.CropSquare, areaValue, stringResource(Res.string.p5b_live_area_label, areaValue))
                CompactMetric(Icons.Default.Straighten, perimeterValue, stringResource(Res.string.p5b_live_perimeter_label, perimeterValue))
            }
            if (trailing != null) Box { trailing() }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val left = measurables[0].measure(loose)
        val right = measurables.getOrNull(1)?.measure(loose)
        val gap = 5.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else left.width + (right?.width ?: 0) + gap
        val fits = right == null || left.width + gap + right.width <= width
        val height = if (fits) maxOf(left.height, right?.height ?: 0)
            else left.height + gap + checkNotNull(right).height
        layout(width, constraints.constrainHeight(height)) {
            left.placeRelative(0, if (fits) (height - left.height) / 2 else 0)
            if (right != null) right.placeRelative(width - right.width, if (fits) (height - right.height) / 2 else left.height + gap)
        }
    }
}

@Composable
internal fun CompactMetric(icon: ImageVector, value: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CeilingThumbnail(contour: MeasurementDrawingContour?, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (contour == null) {
            Icon(Icons.Default.CropFree, stringResource(Res.string.p6a_contour_unavailable),
                Modifier.size(18.dp), tint = ink)
        } else {
            val description = stringResource(Res.string.p6a_ceiling_contour)
            Box(Modifier.fillMaxSize().semantics { contentDescription = description }.drawWithCache {
                val projection = measurementDrawingProjection(contour, size.width, size.height, density)
                    ?: return@drawWithCache onDrawBehind { }
                val path = Path()
                contour.points.forEachIndexed { index, point ->
                    val p = projection.project(point)
                    if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                    else path.lineTo(p.x.toFloat(), p.y.toFloat())
                }
                path.close()
                val stroke = Stroke(width = 1.5.dp.toPx())
                onDrawBehind { drawPath(path, ink, style = stroke) }
            })
        }
    }
}
