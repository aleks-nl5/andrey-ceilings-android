package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.resources.*
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

@Serializable
enum class WorkspaceCoordinatesKind { POINT, LINE, AREA }

@Serializable
data class WorkspaceCoordinatesInput(
    val kind: WorkspaceCoordinatesKind,
    val uuid: String,
    val baseline: List<String>,
    val values: List<String> = baseline,
    val hasDiameter: Boolean = false,
    val invalidGeometry: Boolean = false
) {
    val dirty: Boolean get() = values != baseline
    fun millimetres(): List<Long>? {
        val parsed = values.map { parsePointMetres(it, 2_000_000) ?: return null }
        if (hasDiameter && (parsed.last() !in 2..2_000_000 || parsed.last() % 2L != 0L)) return null
        return parsed
    }
}

@Composable
internal fun WorkspaceCoordinatesAction(
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(stringResource(Res.string.p5b_numeric_coordinates))
    }
}

@Composable
internal fun WorkspaceCoordinatesEditor(
    input: WorkspaceCoordinatesInput,
    model: MeasurementEditModel,
    enabled: Boolean
) {
    val requesters = remember(input.uuid, input.values.size) { List(input.values.size) { FocusRequester() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.p5b_numeric_coordinates), style = MaterialTheme.typography.titleMedium)
        input.values.forEachIndexed { index, value ->
            val diameter = input.hasDiameter && index == input.values.lastIndex
            val label = when {
                diameter -> stringResource(Res.string.p5b_numeric_diameter)
                index % 2 == 0 -> stringResource(Res.string.p5b_numeric_x, index / 2 + 1)
                else -> stringResource(Res.string.p5b_numeric_y, index / 2 + 1)
            }
            DecimalInputField(value, { model.changeWorkspaceCoordinate(index, it) }, label,
                modifier = measurementFieldFocusModifier().focusRequester(requesters[index]).testTag("p5b-coordinate-$index"),
                signed = !diameter, enabled = enabled, isError = input.invalidGeometry,
                onNext = requesters.getOrNull(index + 1)?.let { next -> { next.requestFocus() } })
        }
        if (input.invalidGeometry) Text(
            stringResource(Res.string.p5b_coordinates_invalid), color = MaterialTheme.colorScheme.error
        )
        FlowRow {
            TextButton(onClick = model::applyWorkspaceCoordinates,
                enabled = enabled && input.dirty && input.millimetres() != null) {
                Text(stringResource(Res.string.p5b_apply_coordinates))
            }
            TextButton(onClick = model::closeWorkspaceCoordinates, enabled = enabled) {
                Text(stringResource(Res.string.p5b_cancel_placement))
            }
        }
    }
}
