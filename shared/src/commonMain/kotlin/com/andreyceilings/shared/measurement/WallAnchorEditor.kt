package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.input.DecimalInputField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
                FilterChip(input.kind==WallAnchor.Kind.TWO_WALLS,onClick={model.updateMounting { it.copy(kind=WallAnchor.Kind.TWO_WALLS) }},label={Text("Від двох стін")})
                FilterChip(input.kind==WallAnchor.Kind.ALONG_INWARD,onClick={model.updateMounting { it.copy(kind=WallAnchor.Kind.ALONG_INWARD) }},label={Text("Уздовж + усередину")})
            }
            Text(if(input.kind==WallAnchor.Kind.TWO_WALLS) "Перша опорна стіна" else "Обрана стіна й початок її напрямку")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { walls.forEach { wall ->
                FilterChip(input.firstWall==wall.uuid,onClick={model.updateMounting { it.copy(firstWall=wall.uuid) }},
                    label={Text("1: ${measurementSideLabels(walls.size)[wall.ordinal]}")})
            } }
            if(input.kind==WallAnchor.Kind.TWO_WALLS) {
                Text("Друга опорна стіна")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { walls.forEach { wall ->
                    FilterChip(input.secondWall==wall.uuid,onClick={model.updateMounting { it.copy(secondWall=wall.uuid) }},
                        label={Text("2: ${measurementSideLabels(walls.size)[wall.ordinal]}")})
                } }
            }
            DecimalInputField(input.firstText, { value -> model.updateMounting { it.copy(firstText = value) } },
                if (input.kind == WallAnchor.Kind.TWO_WALLS) "Від першої стіни, м" else "Від початку вздовж стіни, м",
                modifier = measurementFieldFocusModifier(),
                onNext = if (editorFocus) ({ secondField.requestFocus() }) else null)
            DecimalInputField(input.secondText, { value -> model.updateMounting { it.copy(secondText = value) } },
                if (input.kind == WallAnchor.Kind.TWO_WALLS) "Від другої стіни, м" else "Відступ усередину, м",
                modifier = measurementFieldFocusModifier().focusRequester(secondField))
            // Do not show a large red geometry error before the user has typed both dimensions.
            if (input.firstText.isNotBlank() && input.secondText.isNotBlank() && solution == null)
                Text("Немає допустимої точки. Перевірте відступи або оберіть «Уздовж + усередину».",
                    color = MaterialTheme.colorScheme.error)
            else if (solution != null && !inline)
                Text("Перевірено: ${formatPointMetres(solution.firstActualMm.roundToLong())} м / ${formatPointMetres(solution.secondActualMm.roundToLong())} м")
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
        FlowRow { apply(); cancel() }
    } else AlertDialog(onDismissRequest=model::dismissMounting,title={Text("Розміри від стін")},
        text=fields,confirmButton=apply,dismissButton=cancel)
}
