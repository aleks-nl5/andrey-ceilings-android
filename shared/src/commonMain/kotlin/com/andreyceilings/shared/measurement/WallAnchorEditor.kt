package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
fun WallAnchorEditor(model: MeasurementEditModel) {
    val state by model.state.collectAsState()
    val input=state.mountingInput ?: return
    if (state.mountingDismiss) {
        AlertDialog(onDismissRequest=model::continueMounting,title={Text("Відхилити введені відстані?")},
            text={Text("Незастосовані монтажні розміри буде втрачено. Інша чернетка стелі залишиться.")},
            confirmButton={TextButton(onClick=model::discardMounting){Text("Відхилити відстані")}},
            dismissButton={TextButton(onClick=model::continueMounting){Text("Продовжити")}})
        return
    }
    val geometry=validateMeasurementCreate(state.draft).geometry
    val walls=model.draftWalls()
    val solution=geometry?.let { g -> input.anchor()?.let { solveWallAnchor(g,walls,it) } }
    AlertDialog(onDismissRequest=model::dismissMounting,title={Text("Розміри від стін")},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Виміряйте відстані від обраних стін до центра світильника. Значення у метрах, до 3 знаків після коми або крапки.")
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
            OutlinedTextField(input.firstText,{value->model.updateMounting { it.copy(firstText=value) }},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),
                label={Text(if(input.kind==WallAnchor.Kind.TWO_WALLS) "Від першої стіни, м" else "Від початку вздовж стіни, м")})
            OutlinedTextField(input.secondText,{value->model.updateMounting { it.copy(secondText=value) }},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),
                label={Text(if(input.kind==WallAnchor.Kind.TWO_WALLS) "Від другої стіни, м" else "Відступ усередину, м")})
            if(solution==null) Text("Немає однозначної внутрішньої точки з проєкціями на обрані відрізки. Перевірте розміри, оберіть інші стіни або спосіб «Уздовж + усередину».",color=MaterialTheme.colorScheme.error)
            else Text("Фактично після округлення до мм: ${formatPointMetres(solution.firstActualMm.roundToLong())} м / ${formatPointMetres(solution.secondActualMm.roundToLong())} м. Похибка представлення не перевищує 1 мм.")
            Text("Застосування змінює тільки чернетку. Для запису всієї стелі натисніть «Зберегти».")
        }},confirmButton={TextButton(enabled=solution!=null,onClick=model::applyMounting){Text("Застосувати розміри")}},
        dismissButton={TextButton(onClick=model::dismissMounting){Text("Скасувати")}})
}
