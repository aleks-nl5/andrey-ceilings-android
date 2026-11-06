package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Details stages transforms in the same saveable full-set editor as the contour screen. */
@Composable
internal fun rememberDetailsDrawingEditor(store:MeasurementEditStore?, project:Long, id:Long, version:Long,
    sourceVersions:Map<String,Long>?=null):MeasurementEditModel? {
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
    // Checking/error changes from this read are not a new refresh request. In
    // particular a failed read must wait for a new source revision or explicit retry.
    val reloadBlocked=state?.let {it.dirty || it.saving || it.saved || it.exitReady || it.uncertain || it.conflict ||
        it.drag!=null || it.lineDrag!=null || it.area.drag!=null}!=false
    val refresh by rememberUpdatedState(Triple(version,sourceVersions,state?.original to reloadBlocked))
    LaunchedEffect(current) {
        if(current==null)return@LaunchedEffect
        var document:MeasurementEditDocument?=null
        var expected=emptyMap<String,Long>()
        // Sequential collection lets an accepted read finish when another child stream
        // changes; collectLatest/effect-key cancellation could strand a stale co-editor.
        snapshotFlow {refresh}.collect { (version,source,_) ->
            // A pointer event can precede the next Compose snapshot. Consult the model
            // synchronously before a read that would replace its clean document.
            val state=current.state.value
            if(document!==state.original) {
                document=state.original
                val original=state.original.placements
                expected=detailsDrawingVersions(original.points,original.lines,original.areas,original.elements)
            }
            // Ignore reactive notifications older than our own confirmed Save.
            val changed=version>state.original.updatedAt || source!=null &&
                (expected.keys!=source.keys || source.any {(key,value)->value>expected.getValue(key)})
            if(changed && !state.dirty && !state.saving && !state.exitReady && !state.checking && !state.saved &&
                !state.uncertain && !state.conflict && state.drag==null && state.lineDrag==null && state.area.drag==null)current.reload()
        }
    }
    return current
}

@Composable
internal fun DetailsDrawingControls(model:MeasurementEditModel,enabled:Boolean) {
    val state by model.state.collectAsState()
    val scope=rememberCoroutineScope()
    val available=enabled && !state.saving && !state.checking && !state.saved && !state.exitReady && !state.uncertain && !state.conflict
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
    if(state.selectedPointUuid!=null || state.selectedLineUuid!=null || state.area.selected!=null || state.dirty) {
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
    if(state.value.exitRequested || state.value.exitReady) {saveAndExit();return}
    val point=state.value.selectedPointUuid;val line=state.value.selectedLineUuid;val area=state.value.area.selected
    save(keepEditing=true)
    if(!state.value.uncertain)delivered()
    if(!state.value.dirty && !state.value.conflict) {
        point?.let(::selectPoint);line?.let(::selectLine);area?.let(::selectArea)
    }
}

internal fun detailsDrawingVersions(points:List<PointPlacement>,lines:List<LinePlacement>,areas:List<AreaFeature>,elements:Map<String,Long>)=
    buildMap<String,Long> {
        points.forEach {put("point:${it.uuid}",it.updatedAt)}
        lines.forEach {put("line:${it.uuid}",it.updatedAt)}
        areas.forEach {put("area:${it.uuid}",it.updatedAt)}
        elements.forEach {(uuid,version)->put("element:$uuid",version)}
    }

/** Keep the navigation modal mounted even when the LazyColumn drawing is off screen. */
@Composable
internal fun DetailsDrawingExitDialog(model:MeasurementEditModel) {
    val state by model.state.collectAsState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(model,state.exitReady) {model.deliverPendingExit()}
    val available=!state.saving && !state.checking && !state.exitReady && !state.conflict
    fun save(){scope.launch(start=CoroutineStart.UNDISPATCHED){model.saveAndExit()}}
    if(state.exitRequested)AlertDialog(onDismissRequest=model::continueEditing,title={Text("Незбережені зміни креслення")},
        text={Column {
            Text("Збережіть креслення або явно відхиліть зміни перед виходом.")
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={TextButton(enabled=available && model.canSaveMountingDraft(),onClick=::save){Text(if(state.uncertain)"Повторити ту саму спробу" else "Зберегти креслення")}},
        dismissButton={Row {
            TextButton(enabled=!state.saving && !state.checking,onClick=model::discardAndExit){Text("Відхилити зміни")}
            TextButton(enabled=!state.saving && !state.checking,onClick=model::continueEditing){Text("Продовжити")}
        }})
}
