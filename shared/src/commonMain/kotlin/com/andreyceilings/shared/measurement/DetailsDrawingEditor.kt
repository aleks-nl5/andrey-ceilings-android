package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.*

/** Details stages transforms in the same saveable full-set editor as the contour screen. */
@Composable
internal fun rememberDetailsDrawingEditor(store:MeasurementEditStore?, project:Long, id:Long, version:Long):MeasurementEditModel? {
    if(store==null)return null
    val saver=remember(store) {Saver<MeasurementEditModel?,String>(save={it?.snapshot()},
        restore={MeasurementEditModel.restore(store,it,project,id)})}
    var model by rememberSaveable(store,project,id,stateSaver=saver) {mutableStateOf<MeasurementEditModel?>(null)}
    LaunchedEffect(store,project,id) {
        if(model==null)try {model=MeasurementEditModel(store,withContext(Dispatchers.Default){store.load(project,id)})}
        catch(_:Exception) { /* Viewing remains available if an old contour cannot be edited. */ }
    }
    val current=model
    val state=current?.state?.collectAsState()?.value
    LaunchedEffect(current) {current?.reconcile()}
    LaunchedEffect(current,state?.saved) {current?.resumeDetailsAfterSave()}
    LaunchedEffect(version,state?.original?.updatedAt,state?.saving,state?.checking,state?.dirty) {
        if(current!=null && state!=null && state.original.updatedAt!=version && !state.dirty && !state.saving &&
            !state.checking && !state.uncertain && !state.conflict && state.lineDrag==null && state.area.drag==null)current.reload()
    }
    return current
}

@Composable
internal fun DetailsDrawingControls(model:MeasurementEditModel,enabled:Boolean) {
    val state by model.state.collectAsState()
    val scope=rememberCoroutineScope()
    val available=enabled && !state.saving && !state.checking && !state.saved && !state.uncertain && !state.conflict
    fun save() {scope.launch(start=CoroutineStart.UNDISPATCHED) {model.saveDetailsDrawing()}}
    if(state.selectedLineUuid!=null)FlowRow {
        listOf("Перемістити сегмент","Початок","Кінець").forEachIndexed {i,label ->
            FilterChip(state.lineMoveTarget==i,onClick={model.setLineMoveTarget(i)},enabled=available,label={Text(label)})
        }
    }
    model.displayAreas().find {it.uuid==state.area.selected}?.let {a ->
        FlowRow {
            FilterChip(state.area.moveVertex==null,onClick={model.setAreaMoveVertex(null)},enabled=available,label={Text("Перемістити фігуру")})
            if(a.shape is AreaShape.Polygon)a.shape.positions.indices.forEach {i ->
                FilterChip(state.area.moveVertex==i,onClick={model.setAreaMoveVertex(i)},enabled=available,label={Text("Вершина ${i+1}")})
            }
        }
    }
    if(state.selectedLineUuid!=null || state.area.selected!=null || state.dirty) {
        Text("Перетягніть вибраний об’єкт. Змінені координати стануть приблизними; збережіть зміни креслення.")
        FlowRow {
            TextButton(enabled=available,onClick=model::clearDrawingSelection){Text("Зняти виділення")}
            TextButton(enabled=available && state.canUndo,onClick=model::undo){Text("Скасувати дію")}
            TextButton(enabled=available && state.canRedo,onClick=model::redo){Text("Повторити")}
            Button(enabled=available && state.dirty && model.canSaveMountingDraft(),onClick=::save){Text("Зберегти креслення")}
        }
    }
    state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    model.lineCapacityError()?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    model.areaCapacityError()?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(state.saving || state.checking) {LinearProgressIndicator();Text("Збереження або перевірка креслення…")}
    if(state.uncertain)TextButton(enabled=!state.saving,onClick=::save){Text("Повторити ту саму спробу")}
    var reload by remember {mutableStateOf(false)}
    if(state.conflict || state.uncertain || state.dirty)TextButton(enabled=!state.saving && !state.checking,onClick={reload=true}){Text("Перезавантажити креслення")}
    if(reload)AlertDialog(onDismissRequest={reload=false},title={Text("Відхилити чернетку креслення?")},
        text={Text("Відкриються поточні дані з бази. Незбережені переміщення буде відхилено.")},
        confirmButton={TextButton(onClick={reload=false;scope.launch {model.reload()}}){Text("Перезавантажити")}},
        dismissButton={TextButton(onClick={reload=false}){Text("Продовжити")}})
}

internal suspend fun MeasurementEditModel.saveDetailsDrawing() {
    val line=state.value.selectedLineUuid;val area=state.value.area.selected
    save(keepEditing=true)
    if(!state.value.uncertain)delivered()
    if(!state.value.dirty && !state.value.conflict) {
        line?.let(::selectLine);area?.let(::selectArea)
    }
}

/** Keep the navigation modal mounted even when the LazyColumn drawing is off screen. */
@Composable
internal fun DetailsDrawingExitDialog(model:MeasurementEditModel) {
    val state by model.state.collectAsState()
    val scope=rememberCoroutineScope()
    val available=!state.saving && !state.checking && !state.uncertain && !state.conflict
    fun save(){scope.launch(start=CoroutineStart.UNDISPATCHED){model.saveDetailsDrawing()}}
    if(state.exitRequested)AlertDialog(onDismissRequest=model::continueEditing,title={Text("Незбережені зміни креслення")},
        text={Text("Збережіть креслення або явно відхиліть зміни перед виходом.")},
        confirmButton={TextButton(enabled=available && model.canSaveMountingDraft(),onClick={model.continueEditing();save()}){Text("Зберегти креслення")}},
        dismissButton={Row {
            TextButton(enabled=!state.saving && !state.checking,onClick=model::discardAndExit){Text("Відхилити зміни")}
            TextButton(onClick=model::continueEditing){Text("Продовжити")}
        }})
}
