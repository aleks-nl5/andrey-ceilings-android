package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Functional controls shared by Android touch and Windows mouse hosts. */
@Composable
fun AreaFeatureControls(model: MeasurementEditModel, enabled: Boolean) {
    val state by model.state.collectAsState()
    val scope=rememberCoroutineScope()
    val a=state.area
    val areas=model.displayAreas()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Площинні об'єкти",style=MaterialTheme.typography.titleMedium)
        Text("Перешкоди й отвори — геометричні позначки. Не віднімаються від площі стелі. Сума площ може містити перекриття.",style=MaterialTheme.typography.bodySmall)
        FlowRow {
            TextButton(enabled=enabled,onClick={model.beginArea(AreaRole.OBSTACLE)}) {Text("Позначити перешкоду")}
            TextButton(enabled=enabled,onClick={model.beginArea(AreaRole.VOID_MARKER,tool=AreaTool.CIRCLE)}) {Text("Позначити отвір")}
        }
        state.original.areaElements.forEach {e ->
            val linked=areas.filter {it.elementUuid==e.uuid}
            Text("${e.label} · Заявлено: ${formatElementQuantity(e.quantity,e.unit)} · На кресленні: ${formatElementMilli(areaQuantityMilli(linked,e.unit!!))} ${e.unit} · Нерозв'язаних: ${linked.count {it.unresolved}}")
            FlowRow {
                TextButton(enabled=enabled,onClick={model.beginArea(AreaRole.GENERAL_AREA,e.uuid)}) {Text("Розмістити область: ${e.label}")}
                TextButton(enabled=enabled && areaQuantityMilli(linked,e.unit!!)>0,onClick={model.requestAreaQuantity(e.uuid)}) {Text("Взяти кількість з креслення: ${e.label}")}
            }
        }
        if(a.tool!=null) {
            FlowRow {AreaTool.entries.forEach {tool -> FilterChip(a.tool==tool,enabled=enabled,onClick={model.setAreaTool(tool)},label={Text(when(tool) {
                AreaTool.RECTANGLE -> "Прямокутник";AreaTool.POLYGON -> "Багатокутник";AreaTool.CIRCLE -> "Коло"
            })})}}
            Text(when(a.tool) {AreaTool.RECTANGLE -> "Позначте два протилежні кути.";AreaTool.POLYGON -> "Позначте 3–16 вершин, потім завершіть фігуру.";else -> "Позначте центр і введіть виміряний діаметр."})
            if(a.tool==AreaTool.CIRCLE)OutlinedTextField(a.diameter,model::setAreaDiameter,enabled=enabled,singleLine=true,label={Text("Діаметр, м (парні мм)")})
            Text("Точок: ${a.vertices.size}. Положення від тапів приблизне.")
            FlowRow {
                TextButton(enabled=enabled && model.areaCreationShape()!=null,onClick=model::acceptAreaCreation) {Text("Завершити фігуру в чернетці")}
                TextButton(enabled=enabled,onClick=model::cancelAreaCreation) {Text("Скасувати фігуру")}
            }
        }
        areas.forEachIndexed {i,feature ->
            FilterChip(a.selected==feature.uuid,onClick={model.selectArea(feature.uuid)},enabled=enabled,
                label={Text("Фігура ${i+1}: ${areaDescription(feature)}")})
            if(feature.note.isNotBlank())Text(feature.note)
        }
        val selected=areas.find {it.uuid==a.selected}
        if(selected!=null) {
            Text(areaDescription(selected))
            Text(if(selected.unresolved)"Остання відома геометрія збережена. Виміряйте всі опори й радіус та явно підтвердьте." else "Перетягування змінює лише приблизне положення; опори задаються виміряними відстанями.")
            FlowRow {
                FilterChip(a.moveVertex==null,onClick={model.setAreaMoveVertex(null)},label={Text("Переміщати всю фігуру")})
                selected.shape.positions.indices.forEach {index ->
                    FilterChip(a.moveVertex==index,onClick={model.setAreaMoveVertex(index)},label={Text(if(selected.shape is AreaShape.Circle)"Центр" else "Вершина ${index+1}")})
                    TextButton(enabled=enabled,onClick={model.openAreaMounting(index)}) {Text("Опори ${index+1}")}
                }
            }
            FlowRow {
                TextButton(enabled=enabled,onClick=model::openAreaProperties) {Text("Властивості фігури")}
                TextButton(enabled=enabled,onClick=model::confirmArea) {Text("Підтвердити фігуру")}
                TextButton(enabled=enabled,onClick=model::requestAreaDelete) {Text("Видалити фігуру")}
            }
        }
        if(areas.any {it.needsReview})FlowRow {
            TextButton(enabled=enabled && !state.dirty,onClick=model::reviewNow) {Text("Перевірити площинні об'єкти")}
            TextButton(enabled=enabled && !state.dirty,onClick={scope.launch {model.reviewLater()}}) {Text("Площинні об'єкти: пізніше")}
        }
        model.areaCapacityError()?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
    if(a.deleteConfirm)AlertDialog(onDismissRequest=model::cancelAreaDelete,title={Text("Видалити фігуру?")},
        text={Text("Фігуру буде вилучено після загального Save. Заявлена кількість залишиться.")},
        confirmButton={TextButton(onClick=model::deleteArea){Text("Видалити")}},dismissButton={TextButton(onClick=model::cancelAreaDelete){Text("Скасувати")}})
    a.quantityConfirm?.let {id ->
        val e=state.original.areaElements.find {it.uuid==id}
        val qty=if(e!=null)areaQuantityMilli(areas.filter {it.elementUuid==id},e.unit!!) else 0
        AlertDialog(onDismissRequest={model.requestAreaQuantity(null)},title={Text("Взяти кількість з креслення?")},
            text={Text("${formatElementQuantity(e?.quantity ?: ElementQuantity.Unknown,e?.unit)} → ${formatElementMilli(qty)} ${e?.unit.orEmpty()}. Тільки валідні фігури; запис разом із заміром, із перевіркою версій.")},
            confirmButton={TextButton(enabled=qty>0,onClick=model::takeAreaQuantity){Text("Застосувати кількість")}},
            dismissButton={TextButton(onClick={model.requestAreaQuantity(null)}){Text("Скасувати")}})
    }
    val properties=a.properties
    if(properties!=null) {
        if(a.dismissProperties)AlertDialog(onDismissRequest=model::continueAreaProperties,title={Text("Відхилити властивості?")},
            confirmButton={TextButton(onClick=model::discardAreaProperties){Text("Відхилити")}},dismissButton={TextButton(onClick=model::continueAreaProperties){Text("Продовжити")}})
        else AlertDialog(onDismissRequest=model::dismissAreaProperties,title={Text("Властивості фігури")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                AreaRole.entries.forEach {role ->FilterChip(properties.role==role,onClick={model.changeAreaProperties {it.copy(role=role)}},label={Text(areaRoleLabel(role))})}
                OutlinedTextField(properties.note,{text->model.changeAreaProperties {it.copy(note=text)}},label={Text("Примітка фігури")})
                if(properties.diameter.isNotEmpty())Row {Checkbox(properties.diameterMeasured,onCheckedChange={checked ->model.changeAreaProperties {it.copy(diameterMeasured=checked)}});Text("Діаметр повторно виміряно")}
                if(properties.diameter.isNotEmpty())OutlinedTextField(properties.diameter,{text->model.changeAreaProperties {it.copy(diameter=text,diameterMeasured=true)}},label={Text("Виміряний діаметр, м (парні мм)")})
            }
        },confirmButton={TextButton(onClick=model::applyAreaProperties){Text("Застосувати властивості")}},dismissButton={TextButton(onClick=model::dismissAreaProperties){Text("Скасувати")}})
    }
}
