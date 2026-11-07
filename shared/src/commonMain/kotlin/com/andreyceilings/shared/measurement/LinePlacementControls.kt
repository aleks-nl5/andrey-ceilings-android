package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.input.DecimalInputField

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun LinePlacementControls(model:MeasurementEditModel,enabled:Boolean) {
    val state by model.state.collectAsState()
    val scope=rememberCoroutineScope()
    val lines=model.displayLines()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        if(state.original.lineElements.isNotEmpty())Text("Елементи стелі · Лінії",style=MaterialTheme.typography.titleMedium)
        state.original.lineElements.forEach {element ->
            val placed=lines.filter {it.elementUuid==element.uuid && !it.unresolved}.sumOf {it.lengthMm}
            val unresolved=lines.count {it.elementUuid==element.uuid && it.unresolved}
            val declared=state.declaredLengths[element.uuid]?.let {ElementQuantity.Exact(it)} ?: element.quantity
            Text(element.label,style=MaterialTheme.typography.titleSmall,color=if(lines.any {it.elementUuid==element.uuid && it.uuid==state.selectedLineUuid})MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text("Заявлено ${formatElementQuantity(declared,element.unit)} · На кресленні ${formatElementMilli(placed)} м · Потребує уточнення: $unresolved")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(enabled=enabled && state.linePreview==null,onClick={model.beginLine(element.uuid)}) {Text("Розмістити лінію")}
                TextButton(enabled=enabled && placed>0 && unresolved==0,onClick={model.requestDeclaredLength(element.uuid)}) {Text("Взяти довжину з креслення")}
            }
            FlowRow {lines.filter {it.elementUuid==element.uuid}.forEachIndexed {i,line ->
                FilterChip(state.selectedLineUuid==line.uuid,onClick={model.selectLine(line.uuid)},enabled=enabled,
                    label={Text("Сегмент ${i+1}: ${formatElementMilli(line.lengthMm)} м")})
            }}
        }
        if(state.lineElementUuid!=null) {
            Text(if(state.lineStart==null)"Торкніться початку лінії." else if(state.linePreview==null)"Торкніться кінця лінії." else "Приблизна лінія: ${formatElementMilli(state.linePreview!!.lengthMm)} м")
            FlowRow {
                TextButton(enabled=enabled && state.linePreview!=null,onClick=model::acceptLineCreation){Text("Зберегти лінію в чернетці")}
                TextButton(enabled=enabled,onClick=model::cancelLineCreation){Text("Скасувати лінію")}
            }
        }
        val selected=lines.find {it.uuid==state.selectedLineUuid}
        if(selected!=null) {
            Text(when {selected.unresolved->"Потребує уточнення";selected.needsReview->"Перевірити розміщення";selected.precision==PointPrecision.EXACT->"За виміряними відступами";else->"Приблизно"})
            Text("Довжина ${formatElementMilli(selected.lengthMm)} м. Це центральна монтажна вісь; ширина не змінює площу й не перевіряє геометрію вирізу.")
            FlowRow {
                listOf("Перемістити сегмент","Початок","Кінець").forEachIndexed {i,label ->
                    FilterChip(state.lineMoveTarget==i,onClick={model.setLineMoveTarget(i)},enabled=enabled,label={Text(label)})
                }
            }
            Text("Перетягніть вибрану лінію. Змінені кінці стануть приблизними, їх активні прив'язки буде скинуто.")
            FlowRow {
                TextButton(enabled=enabled,onClick={model.openLineMounting(true)}){Text("Розміри початку від стін")}
                TextButton(enabled=enabled,onClick={model.openLineMounting(false)}){Text("Розміри кінця від стін")}
                TextButton(enabled=enabled,onClick={if(state.reviewing)scope.launch {model.confirmLineAndContinueReview()} else model.confirmLine()}){Text("Розміщення перевірено")}
                TextButton(enabled=enabled,onClick=model::openLineProperties){Text("Параметри лінії")}
                TextButton(enabled=enabled,onClick=model::requestLineDelete){Text("Видалити лінію")}
            }
            selected.widthMm?.let {Text("Ширина: $it мм (без перевірки оболонки)")}
            if(selected.note.isNotBlank())Text(selected.note)
            listOf("Початок" to selected.startMounting,"Кінець" to selected.endMounting).forEach {(title,m) ->
                m.anchor?.let {a ->
                    fun wall(id:String?)=model.draftWalls().find {it.uuid==id}?.let {measurementSideLabels(model.draftWalls().size)[it.ordinal]} ?: "втрачена стіна"
                    Text("$title: ${wall(a.firstWall)} ${formatPointMetres(a.firstMm)} м · ${if(a.kind==WallAnchor.Kind.ALONG_INWARD) "усередину" else wall(a.secondWall)} ${formatPointMetres(a.secondMm)} м")
                } ?: Text("$title: виміряних прив'язок немає")
            }
        }
        val review=lines.count {it.needsReview}
        if(review>0)TextButton(enabled=enabled,onClick=model::reviewNow){Text("Перевірити лінії ($review)")}
        model.lineCapacityError()?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
    state.lineProperties?.let {input ->
        if(state.linePropertiesDismiss)AlertDialog(onDismissRequest=model::continueLineProperties,title={Text("Відхилити параметри лінії?")},
            confirmButton={TextButton(onClick=model::discardLineProperties){Text("Відхилити параметри")}},
            dismissButton={TextButton(onClick=model::continueLineProperties){Text("Продовжити")}})
        else AlertDialog(onDismissRequest=model::dismissLineProperties,title={Text("Параметри лінії")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                DecimalInputField(input.width, { model.changeLineProperties(width = it) }, "Ширина, мм (необов'язково)", isError = !input.valid, allowDecimalSeparator = false)
                OutlinedTextField(input.note,{model.changeLineProperties(note=it)},label={Text("Примітка сегмента")})
                Text("Ширина є довідковим параметром. Площу не віднімаємо; допустимість отвору чи оболонки не перевірена.")
            }},confirmButton={TextButton(enabled=input.valid,onClick=model::applyLineProperties){Text("Застосувати параметри")}},
            dismissButton={TextButton(onClick=model::dismissLineProperties){Text("Скасувати")}})
    }
    if(state.lineDeleteConfirm)AlertDialog(onDismissRequest=model::cancelLineDelete,title={Text("Видалити вибраний сегмент?")},
        text={Text("Заявлена кількість залишиться. Зміна потрапить в один Save усієї стелі; доступне Undo.")},
        confirmButton={TextButton(onClick=model::deleteLine){Text("Видалити лінію")}},dismissButton={TextButton(onClick=model::cancelLineDelete){Text("Скасувати")}})
    state.lengthConfirm?.let {id ->
        val length=lines.filter {it.elementUuid==id && !it.unresolved}.sumOf {it.lengthMm}
        AlertDialog(onDismissRequest={model.requestDeclaredLength(null)},title={Text("Взяти довжину з креслення?")},
            text={Text("Нова заявлена кількість: ${formatElementMilli(length)} м. Лише валідні сегменти. Зміна кількості й креслення запишуться одним Save з перевіркою версії.")},
            confirmButton={TextButton(enabled=length>0,onClick=model::takeDeclaredLength){Text("Взяти ${formatElementMilli(length)} м")}},
            dismissButton={TextButton(onClick={model.requestDeclaredLength(null)}){Text("Скасувати")}})
    }
}
