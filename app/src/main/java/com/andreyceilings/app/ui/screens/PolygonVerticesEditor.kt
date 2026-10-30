package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.estimate.VerifiedCeilingGeometry

/**
 * Shared Android entry widget for the calculator and saved measurement editor.
 * The KMP model owns validation and the immutable contour, never this UI.
 */
@Composable
internal fun PolygonVerticesEditor(
    vertices: List<Pair<String, String>>,
    onEdit: (Int, Pair<String, String>) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Вершини за порядком обходу контуру (метри). " +
                "Перша вершина автоматично з'єднується з останньою. " +
                "Мінімум 3, максимум ${CeilingPolygonCalculator.MAX_VERTICES}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        vertices.forEachIndexed { index, (x, y) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = x,
                    onValueChange = { onEdit(index, it to y) },
                    label = { Text("X${index + 1}, м") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = y,
                    onValueChange = { onEdit(index, x to it) },
                    label = { Text("Y${index + 1}, м") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemove(index) },
                    enabled = vertices.size > 3
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Видалити вершину ${index + 1}")
                }
            }
        }
        OutlinedButton(
            onClick = onAdd,
            enabled = vertices.size < CeilingPolygonCalculator.MAX_VERTICES
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Додати вершину")
        }
    }
}

/** Parse user text without inventing missing coordinates. */
internal fun capturedPolygonFromText(
    vertices: List<Pair<String, String>>,
    precisionStepMeters: Double
): GeometryInput = VerifiedCeilingGeometry.capturedPolygon(
    vertices.map { (x, y) ->
        (x.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN) to
            (y.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN)
    },
    precisionStepMeters
)

internal fun defaultPolygonVertices(): List<Pair<String, String>> = listOf(
    "0" to "0", "5" to "0", "5" to "4", "0" to "4"
)
