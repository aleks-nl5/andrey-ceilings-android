package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.input.DecimalInputField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
fun PointPositionEditor(scene: PointPlacementState, model: MeasurementElementsModel) {
    val state = scene.positionEditor ?: return
    if (state.exitRequested || state.reloadRequested) {
        AlertDialog(onDismissRequest = model::continuePosition,
            title = { Text(if (state.reloadRequested) "Замінити чернетку положення?" else "Відхилити зміни положення?") },
            text = { Text("Незбережені значення буде втрачено. Невизначений результат перевірте за актуальною позицією; автоматичного повтору немає.") },
            confirmButton = { TextButton(onClick = if (state.reloadRequested) model::reloadPosition else model::discardPosition) {
                Text(if (state.reloadRequested) "Перезавантажити положення" else "Відхилити зміни") } },
            dismissButton = { TextButton(onClick = model::continuePosition) { Text("Продовжити") } })
        return
    }
    val draft = state.draft
    val enabled = !scene.saving && scene.retry == null && !state.conflict
    val position = draft.position()
    AlertDialog(onDismissRequest = model::requestPositionDismiss, title = { Text("Положення світильника") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Координати центру у збереженому контурі, метри. Числове підтвердження робить лише цю позначку точною.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(draft.kind == PointPositionInputKind.WORLD, enabled = enabled,
                    onClick = { model.updatePosition { it.copy(kind = PointPositionInputKind.WORLD) } }, label = { Text("X / Y") })
                FilterChip(draft.kind == PointPositionInputKind.WALL, enabled = enabled,
                    onClick = { model.updatePosition { it.copy(kind = PointPositionInputKind.WALL) } }, label = { Text("Від стіни") })
            }
            if (draft.kind == PointPositionInputKind.WORLD) {
                PositionField(draft.x, "X, м", enabled, signed = true) { value -> model.updatePosition { it.copy(x = value) } }
                PositionField(draft.y, "Y, м", enabled, signed = true) { value -> model.updatePosition { it.copy(y = value) } }
            } else {
                Text("Сторона й напрям від її початку:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    measurementSideLabels(draft.geometry.vertices.size).forEachIndexed { i, label ->
                        FilterChip(draft.side == i, enabled = enabled, onClick = { model.updatePosition { it.copy(side = i) } }, label = { Text(label) })
                    }
                }
                PositionField(draft.along, "Від початку стіни, м", enabled) { value -> model.updatePosition { it.copy(along = value) } }
                PositionField(draft.inward, "Відступ усередину, м", enabled) { value -> model.updatePosition { it.copy(inward = value) } }
                position?.let {
                    val actual = pointWallDistances(draft.geometry, draft.side, it)
                    fun metres(mm: Double) = ((mm * 1000).roundToLong() / 1_000_000.0).toString().replace('.', ',')
                    Text("Фактичні відстані після округлення до мм: уздовж ${metres(actual.alongMm)} м, усередину ${metres(actual.inwardMm)} м.")
                }
            }
            position?.let { Text("X: ${formatPointMetres(it.xMm)} м · Y: ${formatPointMetres(it.yMm)} м") }
            if (position == null) Text("До 3 знаків після коми/крапки; точка строго всередині контуру. Для відступів: уздовж у межах сторони, усередину > 0.", color = MaterialTheme.colorScheme.error)
            if (scene.saving) { LinearProgressIndicator(); Text("Збереження положення…") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.conflict) TextButton(enabled = !scene.saving, onClick = model::requestPositionReload) { Text("Перезавантажити актуальне положення") }
            if (scene.retry != null) TextButton(enabled = !scene.saving, onClick = model::retryPoint) { Text("Повторити ту саму спробу") }
        } },
        confirmButton = { TextButton(enabled = enabled && position != null, onClick = model::savePosition) { Text("Зберегти положення") } },
        dismissButton = { TextButton(enabled = !scene.saving, onClick = model::requestPositionDismiss) { Text("Скасувати") } })
}

@Composable
private fun PositionField(value: String, label: String, enabled: Boolean, signed: Boolean = false, onChange: (String) -> Unit) {
    DecimalInputField(value, onChange, label, enabled = enabled, signed = signed)
}
