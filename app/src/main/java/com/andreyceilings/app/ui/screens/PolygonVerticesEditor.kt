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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.calculation.MeasuredOrthogonalSide
import com.andreyceilings.shared.calculation.OrthogonalSideContour
import com.andreyceilings.shared.calculation.OrthogonalTurn

/**
 * Deliberately uses real measured wall lengths, NOT coordinates.
 * The internal polygon vertices only exist below the KMP geometry boundary.
 */
@Composable
internal fun OrthogonalSidesEditor(
    sides: List<Pair<String, OrthogonalTurn>>,
    onEdit: (Int, Pair<String, OrthogonalTurn>) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Виміряйте кожну сторону за порядком обходу кімнати. " +
                "Після сторони виберіть поворот на 90°. " +
                "Останню сторону також потрібно виміряти.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sides.forEachIndexed { index, (length, turn) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = length, onValueChange = { onEdit(index, it to turn) },
                        label = { Text("Сторона ${index + 1}, м") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRemove(index) },
                        enabled = sides.size > 4
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Видалити сторону ${index + 1}")
                    }
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(OrthogonalTurn.LEFT, OrthogonalTurn.RIGHT).forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = turn == option,
                            onClick = { onEdit(index, length to option) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = i, count = 2
                            ),
                            label = {
                                Text(if (option == OrthogonalTurn.LEFT) "↶ Ліворуч 90°"
                                    else "↷ Праворуч 90°")
                            }
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAdd,
            enabled = sides.size < CeilingPolygonCalculator.MAX_VERTICES
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Додати сторону")
        }
    }
}

internal fun sidesFromText(
    sides: List<Pair<String, OrthogonalTurn>>,
    precisionStepMeters: Double
): List<MeasuredOrthogonalSide> = OrthogonalSideContour.fromMeters(
    sides.map { (text, turn) ->
        (text.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN) to turn
    },
    precisionStepMeters
)

internal fun defaultPolygonSides(): List<Pair<String, OrthogonalTurn>> = listOf(
    "5" to OrthogonalTurn.RIGHT,
    "4" to OrthogonalTurn.RIGHT,
    "5" to OrthogonalTurn.RIGHT,
    "4" to OrthogonalTurn.RIGHT
)
