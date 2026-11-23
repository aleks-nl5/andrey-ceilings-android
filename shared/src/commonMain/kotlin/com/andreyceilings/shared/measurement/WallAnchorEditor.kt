package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.input.DecimalInputField
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
data class WallAnchorInput(val pointUuid: String, val kind: WallAnchor.Kind, val firstWall: String,
    val secondWall: String, val firstText: String, val secondText: String, val initial: List<String>) {
    val dirty get() = listOf(kind.name,firstWall,secondWall,firstText,secondText)!=initial
    fun anchor(): WallAnchor? {
        val a=parsePointMetres(firstText,3_000_000) ?: return null
        val b=parsePointMetres(secondText,3_000_000) ?: return null
        return WallAnchor(kind,firstWall,secondWall.takeIf { kind==WallAnchor.Kind.TWO_WALLS },a,b)
    }
    companion object {
        fun from(point: PointPlacement,walls: List<CeilingWall>): WallAnchorInput {
            val anchor=point.mounting.anchor
            val kind=anchor?.kind ?: WallAnchor.Kind.TWO_WALLS
            val first=anchor?.firstWall ?: walls.first().uuid
            val second=anchor?.secondWall ?: walls.last().uuid
            val a=anchor?.firstMm?.let(::formatPointMetres).orEmpty();val b=anchor?.secondMm?.let(::formatPointMetres).orEmpty()
            return WallAnchorInput(point.uuid,kind,first,second,a,b,listOf(kind.name,first,second,a,b))
        }
    }
}

@Composable
fun WallAnchorEditor(model: MeasurementEditModel, inline: Boolean = false) {
    val state by model.state.collectAsState()
    val input=state.mountingInput ?: return
    if (state.mountingDismiss) {
        AlertDialog(onDismissRequest=model::continueMounting,title={Text("Відхилити введені відстані?")},
            text={Text("Незастосовані монтажні розміри буде втрачено. Інша чернетка стелі залишиться.")},
            confirmButton={TextButton(onClick=model::discardMounting){Text("Відхилити відстані")}},
            dismissButton={TextButton(onClick=model::continueMounting){Text("Продовжити")}})
        return
    }
    val secondField = remember { FocusRequester() }
    val editorFocus = LocalMeasurementInputFocus.current != null
    val geometry=validateMeasurementCreate(state.draft).geometry
    val walls=model.draftWalls()
    val solution=geometry?.let { g -> input.anchor()?.let { solveWallAnchor(g,walls,it) } }
    val fields: @Composable () -> Unit = { Column(
        Modifier.fillMaxWidth().then(if (inline) Modifier else Modifier.verticalScroll(rememberScrollState())),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if (!inline) Text("Виміряйте відстані від обраних стін до вибраної точки або кінця лінії. Значення у метрах, до 3 знаків після коми або крапки.")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(input.kind==WallAnchor.Kind.TWO_WALLS,
                    onClick={model.updateMounting { it.copy(kind=WallAnchor.Kind.TWO_WALLS) }},
                    label={Text(stringResource(if (inline) Res.string.p5b_anchor_two_short else Res.string.p5b_anchor_two_full),
                        style = MaterialTheme.typography.labelSmall)})
                FilterChip(input.kind==WallAnchor.Kind.ALONG_INWARD,
                    onClick={model.updateMounting { it.copy(kind=WallAnchor.Kind.ALONG_INWARD) }},
                    label={Text(stringResource(if (inline) Res.string.p5b_anchor_along_short else Res.string.p5b_anchor_along_full),
                        style = MaterialTheme.typography.labelSmall)})
            }
            if (inline) {
                val firstTitle = stringResource(if (input.kind == WallAnchor.Kind.TWO_WALLS)
                    Res.string.p5b_anchor_first_wall else Res.string.p5b_anchor_direction_wall)
                val firstLabel = stringResource(if (input.kind == WallAnchor.Kind.TWO_WALLS)
                    Res.string.p5b_anchor_distance_short else Res.string.p5b_anchor_along_distance_short)
                WallAnchorCompactWallRow(firstTitle, walls, input.firstWall,
                    onWall = { uuid -> model.updateMounting { it.copy(firstWall = uuid) } },
                    distance = input.firstText, fieldLabel = firstLabel,
                    onDistance = { value -> model.updateMounting { it.copy(firstText = value) } },
                    onNext = if (editorFocus) ({ secondField.requestFocus() }) else null)
                if (input.kind == WallAnchor.Kind.TWO_WALLS) {
                    WallAnchorCompactWallRow(stringResource(Res.string.p5b_anchor_second_wall), walls,
                        input.secondWall,
                        onWall = { uuid -> model.updateMounting { it.copy(secondWall = uuid) } },
                        distance = input.secondText,
                        fieldLabel = stringResource(Res.string.p5b_anchor_distance_short),
                        onDistance = { value -> model.updateMounting { it.copy(secondText = value) } },
                        focus = secondField)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.p5b_anchor_inward_title),
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.25f))
                        DecimalInputField(input.secondText,
                            { value -> model.updateMounting { it.copy(secondText = value) } },
                            stringResource(Res.string.p5b_anchor_inward_distance_short),
                            modifier = measurementFieldFocusModifier().focusRequester(secondField).weight(1f),
                            compact = true)
                    }
                }
            } else {
                Text(stringResource(if (input.kind == WallAnchor.Kind.TWO_WALLS)
                    Res.string.p5b_anchor_first_wall else Res.string.p5b_anchor_direction_wall),
                    style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { walls.forEach { wall ->
                    FilterChip(input.firstWall==wall.uuid,
                        onClick={model.updateMounting { it.copy(firstWall=wall.uuid) }},
                        label={Text(measurementSideLabels(walls.size)[wall.ordinal])})
                } }
                if(input.kind==WallAnchor.Kind.TWO_WALLS) {
                    Text(stringResource(Res.string.p5b_anchor_second_wall), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { walls.forEach { wall ->
                        FilterChip(input.secondWall==wall.uuid,
                            onClick={model.updateMounting { it.copy(secondWall=wall.uuid) }},
                            label={Text(measurementSideLabels(walls.size)[wall.ordinal])})
                    } }
                }
                DecimalInputField(input.firstText, { value -> model.updateMounting { it.copy(firstText = value) } },
                    if (input.kind == WallAnchor.Kind.TWO_WALLS) "Від першої стіни, м" else "Від початку вздовж стіни, м",
                    modifier = measurementFieldFocusModifier(),
                    onNext = if (editorFocus) ({ secondField.requestFocus() }) else null)
                DecimalInputField(input.secondText, { value -> model.updateMounting { it.copy(secondText = value) } },
                    if (input.kind == WallAnchor.Kind.TWO_WALLS) "Від другої стіни, м" else "Відступ усередину, м",
                    modifier = measurementFieldFocusModifier().focusRequester(secondField))
            }
            // Do not show a large red geometry error before the user has typed both dimensions.
            if (input.firstText.isNotBlank() && input.secondText.isNotBlank() && solution == null)
                Text(stringResource(Res.string.p5b_wall_anchor_invalid),
                    color = MaterialTheme.colorScheme.error)
            else if (solution != null && !inline)
                Text(stringResource(Res.string.p5b_wall_anchor_verified,
                    formatPointMetres(solution.firstActualMm.roundToLong()),
                    formatPointMetres(solution.secondActualMm.roundToLong())))
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if (!inline) Text("Застосування змінює тільки чернетку. Для запису всієї стелі натисніть «Зберегти».")
        } }
    val apply: @Composable () -> Unit = {
        TextButton(enabled=solution!=null,onClick=model::applyMounting){Text("Застосувати розміри")}
    }
    val cancel: @Composable () -> Unit = {
        TextButton(onClick=model::dismissMounting){Text("Скасувати")}
    }
    if (inline) {
        fields()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = model::dismissMounting) {
                Icon(Icons.Default.Close, stringResource(Res.string.p5b_cancel_placement))
            }
            IconButton(enabled = solution != null, onClick = model::applyMounting) {
                Icon(Icons.Default.Check, stringResource(Res.string.p5b_apply_dimensions))
            }
        }
    } else AlertDialog(onDismissRequest=model::dismissMounting,title={Text("Розміри від стін")},
        text=fields,confirmButton=apply,dismissButton=cancel)
}

/** Two compact rows of wall choices alongside their own distances. */
@Composable
private fun WallAnchorCompactWallRow(
    title: String, walls: List<CeilingWall>, selected: String,
    onWall: (String) -> Unit, distance: String, fieldLabel: String,
    onDistance: (String) -> Unit, focus: FocusRequester? = null, onNext: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            walls.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { wall ->
                        val side = measurementSideLabels(walls.size)[wall.ordinal]
                        FilterChip(selected == wall.uuid, onClick = { onWall(wall.uuid) },
                            modifier = Modifier.weight(1f).heightIn(min = 36.dp)
                                .semantics { contentDescription = "$title: $side" },
                            label = { Text(side, maxLines = 1,
                                style = MaterialTheme.typography.labelSmall) })
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        DecimalInputField(distance, onDistance, fieldLabel,
            modifier = measurementFieldFocusModifier().then(
                if (focus != null) Modifier.focusRequester(focus) else Modifier).weight(1f),
            compact = true, onNext = onNext)
    }
}
