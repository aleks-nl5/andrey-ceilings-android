package com.andreyceilings.shared.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
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
import com.andreyceilings.shared.resources.p5c_measurement_card_metrics
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
                Text(
                    stringResource(Res.string.p5c_measurement_card_metrics,
                        formatMeasurementAreaCompact(ceiling.area), formatMeasurementLengthCompact(ceiling.perimeter)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (quote != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(compactQuoteAmount(quote.finalCents), style = MaterialTheme.typography.bodyMedium)
                        val (icon, tint) = workspaceStatusAppearance(quote.status)
                        Icon(icon, workspaceStatusLabel(quote.status), Modifier.size(16.dp), tint = tint)
                    }
                } else if (quoteUnavailable) {
                    Text(stringResource(Res.string.p6a_finance_unavailable), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
