package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.db.ceilingDraftWalls
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.livepricing.BusinessRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.roundToLong
import kotlin.math.hypot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

private const val AreaCapacityMessage = "Площа/кількість разом із нерозв'язаними об'єктами перевищує заявлену."

data class MeasurementEditState(
    val original: MeasurementEditDocument, val draft: MeasurementCreateDraft = original.draft,
    val saving: Boolean = false, val checking: Boolean = false, val saved: Boolean = false, val error: String? = null,
    val conflict: Boolean = false, val exitRequested: Boolean = false, val exitReady: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val area: AreaEditorState = AreaEditorState(),
    val lineEdits: Map<String,LinePlacementEdit> = emptyMap(), val declaredLengths: Map<String,Long> = emptyMap(),
    val selectedLineUuid: String? = null, val lineElementUuid: String? = null,
    val lineStart: PointPositionMm? = null, val linePreview: LinePlacement? = null,
    val lineDrag: LinePlacement? = null, val lineMoveTarget: Int = 0, val mountingLineStart: Boolean? = null,
    val lineProperties:LinePropertiesInput?=null,val linePropertiesDismiss:Boolean=false,
    val lineDeleteConfirm: Boolean = false, val lengthConfirm: String? = null,
    val pointEdits: Map<String,PointMountingEdit> = emptyMap(), val selectedPointUuid: String? = null,
    val drag: PointDragPreview? = null, val mountingInput: WallAnchorInput? = null,
    val mountingDismiss: Boolean = false, val uncertain: Boolean = false,
    val reviewPrompt: Boolean = false, val reviewing: Boolean = false, val wallGeneration: String = newRecordUuid(),
    val workspaceSaveConfirmed: Boolean = false,
    val additions: WorkspacePlacementDraft = WorkspacePlacementDraft(),
    val pointElementUuid: String? = null,
    val coordinates: WorkspaceCoordinatesInput? = null
) {
    val dirty: Boolean get() = if (!additions.isEmpty || coordinates?.dirty == true || area.dirty || lineProperties?.dirty==true || lineEdits.isNotEmpty() || declaredLengths.isNotEmpty() || lineStart!=null || linePreview!=null || pointEdits.isNotEmpty() || mountingInput?.dirty==true) true else if (draft == original.draft) false else {
        val current = validateMeasurementCreate(draft)
        val baseline = validateMeasurementCreate(original.draft)
        draft.name.trim() != original.draft.name.trim() || draft.notes.trim() != original.draft.notes.trim() ||
            !current.canSave || current.geometry != baseline.geometry
    }
}

@Serializable
private data class CeilingFormHistory(val draft: String, val edits: Map<String,PointMountingEdit>, val wallGeneration:String="", val lines: Map<String,LinePlacementEdit> = emptyMap(), val lengths: Map<String,Long> = emptyMap(), val areas: AreaEditorState = AreaEditorState(), val additions: WorkspacePlacementDraft = WorkspacePlacementDraft())
private data class CeilingWriteAttempt(val operation: String, val now: Long?)

/** Input transactions are grouped by focus/Done. Shape, turn and topology changes are discrete. */
class MeasurementEditModel(
    private val store: MeasurementEditStore?, original: MeasurementEditDocument,
    val token: String = newRecordUuid()
) {
    private val mutableState = MutableStateFlow(MeasurementEditState(original))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val undo = mutableListOf<CeilingFormHistory>()
    private val redo = mutableListOf<CeilingFormHistory>()
    private var inputStart: CeilingFormHistory? = null
    private var attempt: CeilingWriteAttempt? = null
    private var reconcileAttempt: CeilingWriteAttempt? = null
    private fun form(draft: MeasurementCreateDraft = state.value.draft) = CeilingFormHistory(
        draft.snapshot(), state.value.pointEdits, state.value.wallGeneration, state.value.lineEdits,
        state.value.declaredLengths, state.value.area.history(), state.value.additions
    )
    private var exitAction: (() -> Unit)? = null
    fun observePoints(): Flow<PointOverlayState> = store?.observePoints(state.value.original)
        ?: flowOf(PointOverlayState.Unavailable)

    private fun publish(draft: MeasurementCreateDraft = state.value.draft) {
        mutableState.value = state.value.copy(draft = draft, canUndo = inputStart?.let { it != form(draft) } == true || undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(), error = null)
    }
    fun beginInput() { if (!state.value.saving && !state.value.checking && !state.value.saved && !state.value.exitReady && inputStart == null) inputStart = form() }
    fun commitInput() {
        val start = inputStart ?: return
        inputStart = null
        if (start != form()) { undo.add(start); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        publish()
    }
    fun accept(intent: MeasurementCreateIntent, grouped: Boolean = false) {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady || state.value.uncertain) return
        if (grouped) beginInput() else commitInput()
        val before = state.value.draft
        val after = before.reduce(intent)
        if (before == after) return
        if (!grouped) { undo.add(form()); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        if(intent is MeasurementCreateIntent.Shape || intent is MeasurementCreateIntent.AddSide || intent is MeasurementCreateIntent.RemoveSide || intent is MeasurementCreateIntent.ReplaceCapturedWithSides)
            mutableState.value=state.value.copy(wallGeneration=newRecordUuid(),drag=null)
        publish(after)
    }
    fun undo() {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady || state.value.uncertain) return
        commitInput()
        if (undo.isEmpty()) return
        redo.add(form()); restoreForm(undo.removeAt(undo.lastIndex))
    }
    fun redo() {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady || state.value.uncertain) return
        commitInput()
        if (redo.isEmpty()) return
        undo.add(form()); restoreForm(redo.removeAt(redo.lastIndex))
    }
    fun requestExit(action: () -> Unit) {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady) return
        cancelAreaDrag(); cancelPointDrag(); cancelLineDrag(); commitInput()
        if (!state.value.dirty && !state.value.uncertain) action() else {
            exitAction = action
            mutableState.value = state.value.copy(exitRequested = true)
        }
    }
    fun continueEditing() {
        if(state.value.saving || state.value.checking || state.value.exitReady)return
        exitAction = null; mutableState.value = state.value.copy(exitRequested = false)
    }
    /** Retain the exact requested action through rejection/uncertain retries. */
    suspend fun saveAndExit() {
        if(state.value.exitReady) {deliverPendingExit();return}
        if(exitAction==null || !state.value.exitRequested)return
        saveAttempt(keepEditing=true,preserveExitAction=true)
        deliverPendingExit()
    }
    /** UI also drains this after reattachment; clear before invoking to prevent duplicate delivery. */
    fun deliverPendingExit() {
        val current=state.value
        if(!current.exitReady || current.saving || current.checking || current.uncertain || current.conflict)return
        val action=exitAction ?: return
        exitAction=null
        mutableState.value=current.copy(exitReady=false)
        delivered()
        action()
    }
    fun discardAndExit() {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady) return
        val action = exitAction
        exitAction = null; attempt=null; store?.release(token); undo.clear(); redo.clear(); inputStart = null
        mutableState.value = MeasurementEditState(state.value.original)
        action?.invoke()
    }
    /** Reload is a read and is offered explicitly only after a conflict, with discard confirmation. */
    suspend fun reload() {reloadDocument(keepSelection=false)}
    internal suspend fun reloadDrawing() {reloadDocument(keepSelection=true)}
    private suspend fun reloadDocument(keepSelection:Boolean) {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady) return
        val before=state.value
        if(keepSelection && (before.dirty || before.exitRequested || before.uncertain || before.conflict ||
            before.drag!=null || before.lineDrag!=null || before.area.drag!=null))return
        mutableState.value = before.copy(checking = true)
        try {
            val original = state.value.original
            val loaded = checkNotNull(store).readDocument(original.draft.projectId, original.measurementId)
            undo.clear(); redo.clear(); inputStart = null; attempt=null; exitAction=null;store?.release(token)
            val point=before.selectedPointUuid?.takeIf {id->keepSelection && loaded.placements.points.any {it.uuid==id}}
            val line=before.selectedLineUuid?.takeIf {id->keepSelection && loaded.placements.lines.any {it.uuid==id}}
            val area=loaded.placements.areas.find {keepSelection && it.uuid==before.area.selected}
            mutableState.value = MeasurementEditState(loaded,selectedPointUuid=point,selectedLineUuid=line,
                lineMoveTarget=if(keepSelection)before.lineMoveTarget else 0,
                area=AreaEditorState(selected=area?.uuid,moveVertex=before.area.moveVertex?.takeIf {area?.shape is AreaShape.Polygon && it in area.shape.positions.indices}))
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, error = "Запис недоступний. Чернетку залишено на екрані.")
        }
    }
    suspend fun save(keepEditing: Boolean = false) {saveAttempt(keepEditing,preserveExitAction=false)}
    internal suspend fun saveInWorkspace() {
        saveAttempt(keepEditing = true, preserveExitAction = false, workspace = true)
    }

    private fun workspaceSaved(document: MeasurementEditDocument, before: MeasurementEditState, review: Boolean): MeasurementEditState {
        val selectedLine = before.selectedLineUuid?.takeIf { id -> document.placements.lines.any { it.uuid == id } }
        val selectedArea = document.placements.areas.find { it.uuid == before.area.selected }
        val vertices = (selectedArea?.shape as? AreaShape.Polygon)?.positions?.indices
        return MeasurementEditState(document, reviewPrompt = review, reviewing = before.reviewing,
            selectedPointUuid = before.selectedPointUuid?.takeIf { id -> document.placements.points.any { it.uuid == id } },
            selectedLineUuid = selectedLine,
            lineMoveTarget = if (selectedLine != null) before.lineMoveTarget else 0,
            area = AreaEditorState(selected = selectedArea?.uuid,
                moveVertex = before.area.moveVertex?.takeIf { vertices?.contains(it) == true }),
            workspaceSaveConfirmed = true)
    }

    private suspend fun saveAttempt(keepEditing:Boolean,preserveExitAction:Boolean,workspace:Boolean = false):Boolean {
        if (!mutex.tryLock()) return false
        try {
            if (state.value.saving || state.value.checking || state.value.saved || state.value.exitReady ||
            state.value.area.properties?.dirty == true || state.value.conflict ||
            state.value.mountingInput?.dirty == true || state.value.lineProperties?.dirty == true) return false
            commitInput()
            val before=state.value
            if (!validateMeasurementCreate(before.draft).canSave || !canSaveMountingDraft()) return false
            val completion=store?.beginWrite() ?: return false
            val command=attempt ?: CeilingWriteAttempt(newRecordUuid(),null).also { attempt=it }
            if(!preserveExitAction)exitAction=null
            mutableState.value=before.copy(saving=true,exitRequested=false,error=null,drag=null,workspaceSaveConfirmed=false)
            store?.retain(token,this)
            return withContext(NonCancellable+Dispatchers.Default) {
                try {
                    val timestamp=command.now ?: checkNotNull(store).now().also { attempt=command.copy(now=it) }
                    val result=checkNotNull(store).update(before.original,before.draft,before.pointEdits,command.operation,before.wallGeneration,timestamp,completion,before.lineEdits,before.declaredLengths,before.area.edits,before.area.quantities,before.additions)
                    val geometryChanged=validateMeasurementCreate(before.original.draft).geometry!=validateMeasurementCreate(result.draft).geometry
                    undo.clear();redo.clear();attempt=null
                    val review = geometryChanged && (result.placements.points.any { it.mounting.needsReview } ||
                        result.placements.lines.any { it.needsReview } || result.placements.areas.any { it.needsReview })
                    mutableState.value=if (workspace) workspaceSaved(result, before, review) else MeasurementEditState(result,exitReady=preserveExitAction && exitAction!=null,
                        saved=!keepEditing && !review,
                        reviewPrompt=review,
                        reviewing=keepEditing || before.reviewing,
                        selectedLineUuid=if(keepEditing && result.placements.points.none {it.mounting.needsReview})result.placements.lines.firstOrNull {it.needsReview}?.uuid else null,
                        selectedPointUuid=if (keepEditing) result.placements.points.firstOrNull { it.mounting.needsReview }?.uuid else null)
                    if (workspace) store.release(token)
                    true
                } catch (conflict: MeasurementEditConflict) {
                    attempt=null;mutableState.value=before.copy(error=conflict.message,conflict=true);store?.release(token);false
                } catch (failure: PointPlacementFailure.RetryConflict) {
                    attempt=null;mutableState.value=before.copy(error=pointFailureMessage(failure),conflict=true);store?.release(token);false
                } catch (failure: MeasurementEditRejected) {
                    attempt=null;mutableState.value=before.copy(uncertain=false,error=failure.message);store?.release(token);false
                } catch (failure: LinePlacementFailure) {
                    attempt=null;mutableState.value=before.copy(uncertain=false,error=failure.message);store?.release(token);false
                } catch (_: IllegalArgumentException) {
                    attempt=null;mutableState.value=before.copy(uncertain=false,error="Перевірте контур, стінні опори й відступи. Чернетку збережено; недопустимі дані не записано.");store?.release(token);false
                } catch (_: Exception) {
                    mutableState.value=before.copy(uncertain=true,error="Результат збереження не підтверджено. Повторіть ту саму спробу або перезавантажте запис; чернетку збережено.")
                    false
                } finally { store?.endWrite(completion) }
            }
        } finally { mutex.unlock() }
    }
    fun reviewNow() {
        mutableState.value=state.value.copy(reviewPrompt=false,reviewing=true,area=state.value.area.copy(selected=state.value.original.placements.areas.firstOrNull {it.needsReview}?.uuid),
            selectedLineUuid=if(state.value.original.placements.points.none {it.mounting.needsReview})state.value.original.placements.lines.firstOrNull {it.needsReview}?.uuid else null,
            selectedPointUuid=state.value.original.placements.points.firstOrNull { it.mounting.needsReview }?.uuid)
    }
    suspend fun reviewLater(inWorkspace: Boolean = false) {
        if (state.value.saving) return
        mutableState.value=state.value.copy(reviewPrompt=false,reviewing=false,area=state.value.area.copy(edits=state.value.original.placements.areas.filter {it.needsReview}.associate {it.uuid to AreaFeatureEdit(it,defer=true)}),lineEdits=state.value.original.placements.lines.filter {it.needsReview}.associate {it.uuid to LinePlacementEdit(it,defer=true)},pointEdits=state.value.original.placements.points
            .filter { it.mounting.needsReview }.associate { it.uuid to PointMountingEdit(defer=true) })
        if (inWorkspace) saveInWorkspace() else save()
    }
    suspend fun confirmSelected() {
        val point=displayPoints().find { it.uuid==state.value.selectedPointUuid } ?: return
        if (point.mounting.unresolved) return
        stagePoint(point.uuid,PointMountingEdit(confirm=true))
        save(keepEditing=true)
    }
    fun delivered() { store?.release(token) }
    /** Details keeps the confirmed document open after process-restored receipt reconciliation. */
    fun resumeDetailsAfterSave() {
        if(state.value.saved) { delivered();mutableState.value=state.value.copy(saved=false) }
    }

    private fun restoreForm(value: CeilingFormHistory) {
        val draft=checkNotNull(MeasurementCreateDraft.restore(value.draft,state.value.draft.projectId))
        mutableState.value=state.value.copy(draft=draft,area=value.areas,lineEdits=value.lines,declaredLengths=value.lengths,pointEdits=value.edits,wallGeneration=value.wallGeneration.ifEmpty { token },drag=null,mountingInput=null,
            lineStart=null,linePreview=null,lineDrag=null,lineElementUuid=null,pointElementUuid=null,additions=value.additions,coordinates=null)
        publish()
    }
    fun markPointsChanged() {
        if(!state.value.saving && !state.value.saved) mutableState.value=state.value.copy(conflict=true,error="Позначки змінено іншим редактором. Чернетку збережено; перезавантажте актуальні дані.",drag=null)
    }
    fun draftWalls() = ceilingDraftWalls(state.value.original,state.value.draft,state.value.wallGeneration)
    // Active placement tools are transient UI modes. A partially filled POINT quantity and an
    // idle LINE/AREA tool do not invalidate the persistent draft or block the global Save.
    // In-progress vertices, line endpoints and un-applied numerical input still block it.
    fun canSaveMountingDraft():Boolean = state.value.coordinates?.dirty != true &&
        state.value.mountingInput?.dirty != true &&
        (state.value.area.tool == null || (state.value.area.vertices.isEmpty() && state.value.area.diameter.isBlank())) &&
        state.value.area.properties?.dirty != true && areaCapacityError()==null && state.value.lineProperties?.dirty != true &&
        state.value.linePreview==null && state.value.lineStart==null &&
        displayLines().none { it.unresolved && state.value.lineEdits[it.uuid]?.let {e ->
            val old=state.value.original.placements.lines.find {line ->line.uuid==it.uuid}
            !e.defer && e.value.deletedAt==null && (old==null || e.confirm || e.remeasuredStart || e.remeasuredEnd ||
                e.value.start!=old.start || e.value.end!=old.end || e.value.startMounting.anchor!=old.startMounting.anchor || e.value.endMounting.anchor!=old.endMounting.anchor)
        }==true } &&
        lineCapacityError()==null && displayAreas().none {area -> area.unresolved && state.value.area.edits[area.uuid]?.let {edit ->
            val old=state.value.original.placements.areas.find {it.uuid==area.uuid}
            !edit.defer && edit.value.deletedAt==null && (old==null || edit.confirm || edit.remeasured.isNotEmpty() || edit.remeasuredRadius ||
                edit.value.shape!=old.shape || edit.value.radiusMeasured!=old.radiusMeasured || edit.value.mountings.map {it.anchor}!=old.mountings.map {it.anchor})
        }==true } && displayPoints().none { point -> point.mounting.unresolved &&
        (point.uuid in state.value.additions.points || state.value.pointEdits[point.uuid]?.let {
            it.anchor != null || it.freeX != null
        } == true) }
    fun displayPoints(): List<PointPlacement> {
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return state.value.original.placements.points
        val changed=geometry!=validateMeasurementCreate(state.value.original.draft).geometry
        return (state.value.original.placements.points + state.value.additions.points.values)
            .filter { it.uuid !in state.value.additions.removedPoints && it.elementUuid !in state.value.additions.removedWorks }.map {
            previewMounting(it, geometry, draftWalls(), changed, state.value.pointEdits[it.uuid])
        }
    }
    fun selectPoint(uuid: String) {
        if (state.value.saving || state.value.uncertain || state.value.checking || state.value.exitReady) return
        if (displayPoints().any { it.uuid==uuid }) mutableState.value=state.value.copy(area=state.value.area.copy(selected=null,tool=null,vertices=emptyList()),selectedPointUuid=uuid,selectedLineUuid=null,lineElementUuid=null,lineStart=null,linePreview=null,pointElementUuid=null)
    }

    /** Exact coincident POINTs share a Canvas hit target; cycle selection without moving them. */
    internal fun overlappingPointsForSelected(): List<PointPlacement> {
        val points = displayPoints()
        val selected = points.firstOrNull { it.uuid == state.value.selectedPointUuid } ?: return emptyList()
        return points.filter { it.xMm == selected.xMm && it.yMm == selected.yMm }
            .takeIf { it.size > 1 }.orEmpty()
    }

    internal fun selectNextOverlappingPoint() {
        if (state.value.mountingInput != null || state.value.coordinates != null ||
            state.value.pointElementUuid != null) return
        val coincident = overlappingPointsForSelected()
        val index = coincident.indexOfFirst { it.uuid == state.value.selectedPointUuid }
        if (index >= 0) selectPoint(coincident[(index + 1) % coincident.size].uuid)
    }
    fun beginPointDrag(uuid: String) {
        if (state.value.saving || state.value.uncertain || state.value.checking || validateMeasurementCreate(state.value.draft).geometry==null || state.value.selectedPointUuid!=uuid) return
        val point=displayPoints().find { it.uuid==uuid } ?: return
        mutableState.value=state.value.copy(drag=PointDragPreview(point,ElementVersion(point.elementUuid,state.value.draft.recordUuid,
            state.value.original.placements.elements[point.elementUuid]
                ?: pointElements().first { it.uuid == point.elementUuid }.updatedAt),state.value.original.updatedAt))
    }
    fun previewPointDrag(x: Double,y: Double) { mutableState.value=state.value.copy(drag=state.value.drag?.copy(xMm=x,yMm=y)) }
    fun cancelPointDrag() { mutableState.value=state.value.copy(drag=null) }
    fun finishPointDrag() {
        val drag=state.value.drag ?: return;cancelPointDrag()
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        if (!drag.xMm.isFinite() || !drag.yMm.isFinite() || drag.xMm !in -1_000_000.0..1_000_000.0 || drag.yMm !in -1_000_000.0..1_000_000.0 ||
            !pointStrictlyInside(geometry,drag.xMm.roundToLong(),drag.yMm.roundToLong())) {
            mutableState.value=state.value.copy(error="Позначка має бути строго всередині контуру. Чернетка положення не змінена.");return
        }
        stagePoint(drag.point.uuid,PointMountingEdit(freeX=drag.xMm.roundToLong(),freeY=drag.yMm.roundToLong()))
    }
    fun stagePoint(uuid: String,edit: PointMountingEdit) {
        if (state.value.saving || state.value.checking || state.value.uncertain || state.value.saved || state.value.exitReady) return
        commitInput();undo.add(form());redo.clear()
        mutableState.value=state.value.copy(pointEdits=state.value.pointEdits+(uuid to edit),mountingInput=null,drag=null,error=null)
        publish()
        if(!canSaveMountingDraft()) mutableState.value=state.value.copy(error="Це лише попередня позиція. Задайте коректні виміряні відступи, щоб уточнити нерозв'язану позначку й зберегти всю чернетку.")
    }
    fun openMounting() {
        if (state.value.saving || state.value.checking || state.value.uncertain || validateMeasurementCreate(state.value.draft).geometry==null) return
        val point=displayPoints().find { it.uuid==state.value.selectedPointUuid } ?: return
        updateArea {it.copy(mountingIndex=null)}
        mutableState.value=state.value.copy(pointElementUuid=null,
            mountingInput=WallAnchorInput.from(point,draftWalls()),mountingLineStart=null)
    }
    fun updateMounting(change: (WallAnchorInput)->WallAnchorInput) {
        mutableState.value=state.value.copy(mountingInput=state.value.mountingInput?.let(change),error=null)
    }
    fun applyMounting() {
        val input=state.value.mountingInput ?: return
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        val anchor=input.anchor() ?: return
        if (solveWallAnchor(geometry,draftWalls(),anchor)==null) return
        val areaIndex=state.value.area.mountingIndex
        if(areaIndex!=null) {
            val a=state.value.area.edits[input.pointUuid]?.value ?: displayAreas().find {it.uuid==input.pointUuid} ?: return
            val position=checkNotNull(solveWallAnchor(geometry,draftWalls(),anchor)).position
            val points=a.shape.positions.toMutableList().also {it[areaIndex]=position}
            val mounts=a.mountings.toMutableList().also {it[areaIndex]=PointMounting(anchor=anchor)}
            val shape=a.shape.withPositions(points)
            if(!a.unresolved && !areaStrictlyInside(geometry,shape)) {
                mutableState.value=state.value.copy(error="Виміряна вершина утворює недопустиму фігуру. Увесь контур фігури має бути всередині стелі без самоперетинів.")
                return
            }
            stageArea(a.copy(shape=shape,mountings=mounts),remeasured=state.value.area.edits[a.uuid]?.remeasured.orEmpty()+areaIndex)
            return
        }
        val lineFirst=state.value.mountingLineStart
        if(lineFirst!=null) {
            val line=state.value.lineEdits[input.pointUuid]?.value ?: displayLines().find {it.uuid==input.pointUuid} ?: return
            val position=checkNotNull(solveWallAnchor(geometry,draftWalls(),anchor)).position
            val mounting=PointMounting(anchor=anchor,anchorGeometryJson=MeasurementGeometryCodec.write(geometry))
            stageLine(if(lineFirst)line.copy(start=position,startMounting=mounting) else line.copy(end=position,endMounting=mounting),
                remeasuredStart=if(lineFirst)true else null,remeasuredEnd=if(!lineFirst)true else null)
        } else stagePoint(input.pointUuid,PointMountingEdit(anchor=anchor))
    }
    fun dismissMounting() {
        if(state.value.mountingInput?.dirty==true) mutableState.value=state.value.copy(mountingDismiss=true)
        else discardMounting()
    }
    fun discardMounting() { updateArea {it.copy(mountingIndex=null)}; mutableState.value=state.value.copy(mountingInput=null,mountingLineStart=null,mountingDismiss=false) }
    fun continueMounting() { mutableState.value=state.value.copy(mountingDismiss=false) }

    private fun PointMounting.freeMoved() = copy(anchor=null,confirmedAt=null,anchorGeometryJson=null,
        needsReview=needsReview || anchor!=null)
    private fun editable() = !state.value.saving && !state.value.checking && !state.value.saved && !state.value.exitReady && !state.value.uncertain
    internal suspend fun placementCatalog(): List<ElementCatalogItem> = store?.placementCatalog().orEmpty()

    internal fun materials(role: BusinessRole): List<MeasurementElement> {
        val state = state.value
        return state.original.materialElements.filter { it.businessRole == role && it.uuid !in state.additions.replacedMaterials }
            .map { state.additions.editedWorks[it.uuid] ?: it } +
            state.additions.elements.values.filter { it.businessRole == role }
    }

    internal fun chooseMaterial(role: BusinessRole, item: ElementCatalogItem): Boolean {
        if (!editable() || item.materialRole() != role) return false
        val state = state.value
        val original = state.original.materialElements.filter { it.businessRole == role }
        if (original.size > 1) return false
        val previous = original.singleOrNull()
        val current = materials(role).singleOrNull()
        val sameCurrent = (current?.source as? ElementSource.CatalogSelection)?.priceItemUuid == item.uuid
        if (sameCurrent && current.approval == ElementApproval.APPROVED) return true
        val isOriginal = (previous?.source as? ElementSource.CatalogSelection)?.priceItemUuid == item.uuid
        if (!isOriginal && previous != null && (state.original.placements.points.any { it.elementUuid == previous.uuid } ||
                state.original.placements.lines.any { it.elementUuid == previous.uuid } ||
                state.original.placements.areas.any { it.elementUuid == previous.uuid })) return false
        commitInput()
        undo.add(form())
        redo.clear()
        val pending = state.additions.elements.filterValues { it.businessRole != role }
        val replaced = state.additions.replacedMaterials - listOfNotNull(previous?.uuid).toSet()
        val candidate = if (isOriginal) checkNotNull(previous).copy(approval = ElementApproval.APPROVED)
            else item.toWorkspaceElement(current?.uuid?.takeIf { sameCurrent } ?: newRecordUuid(),
                state.draft.recordUuid, item.mode.workspaceQuantity())
                .copy(businessRole = role, approval = ElementApproval.APPROVED)
        val edited = state.additions.editedWorks - listOfNotNull(previous?.uuid).toSet()
        mutableState.value = state.copy(additions = state.additions.copy(
            elements = if (isOriginal) pending else pending + (candidate.uuid to candidate),
            replacedMaterials = if (!isOriginal && previous != null) replaced + (previous.uuid to previous.version) else replaced,
            editedWorks = if (isOriginal && candidate != previous) edited + (candidate.uuid to candidate) else edited
        ))
        publish()
        return true
    }

    internal fun workspaceWorks(): List<MeasurementElement> {
        val state = state.value
        return (state.original.pointElements + state.original.lineElements + state.original.areaElements +
            state.original.otherElements + state.additions.elements.values)
            .filter { it.businessRole?.isWorkspaceMaterial() != true && it.uuid !in state.additions.removedWorks }
            .distinctBy { it.uuid }.map { state.additions.editedWorks[it.uuid] ?: it }
    }

    internal fun pointElements() = workspaceWorks().filter {
        (it.source as? ElementSource.CatalogSelection)?.placementKind == PlacementKind.POINT
    }
    internal fun lineElements() = workspaceWorks().filter { it.supportsLinePlacement() }
    internal fun areaElements() = workspaceWorks().filter { it.supportsAreaPlacement() }

    internal fun editWorkspaceQuantity(uuid: String, quantity: ElementQuantity): Boolean {
        if (!editable()) return false
        val work = workspaceWorks().find { it.uuid == uuid } ?: return false
        val source = work.source as? ElementSource.CatalogSelection ?: return false
        if (runCatching { ElementQuantityRules.validate(quantity, work.unit, source.quantityMode) }.isFailure) return false
        val changed = work.copy(quantity = quantity)
        val pointCount = displayPoints().count { it.elementUuid == uuid }
        if (pointCount > 0 && (changed.pointCapacity() ?: 0) < pointCount) return false
        val capacity = changed.lineCapacityMm()
        if (capacity != null && (displayLines().filter { it.elementUuid == uuid }.sumOf { it.lengthMm } > capacity ||
                changed.supportsAreaPlacement() && areaQuantityMilli(displayAreas().filter { it.elementUuid == uuid },
                    checkNotNull(work.unit), includeUnresolved = true) > capacity)) return false
        if (changed == work) return true
        commitInput()
        undo.add(form())
        redo.clear()
        val state = state.value
        val additions = state.additions
        mutableState.value = state.copy(additions = if (uuid in additions.elements)
            additions.copy(elements = additions.elements + (uuid to changed))
        else additions.copy(editedWorks = additions.editedWorks + (uuid to changed)))
        publish()
        return true
    }

    internal fun deleteWorkspacePoint(uuid: String) {
        if (!editable()) return
        val state = state.value
        val original = state.original.placements.points.find { it.uuid == uuid }
        if (original == null && uuid !in state.additions.points) return
        commitInput()
        undo.add(form())
        redo.clear()
        mutableState.value = state.copy(selectedPointUuid = null, pointEdits = state.pointEdits - uuid,
            additions = state.additions.copy(points = state.additions.points - uuid,
                removedPoints = if (original == null) state.additions.removedPoints
                    else state.additions.removedPoints + (uuid to original)))
        publish()
    }

    internal fun deleteWorkspaceWork(uuid: String) {
        if (!editable()) return
        val work = workspaceWorks().find { it.uuid == uuid } ?: return
        cancelPlacement()
        clearDrawingSelection()
        commitInput()
        undo.add(form())
        redo.clear()
        val state = state.value
        val additions = state.additions
        val removedPoints = (state.original.placements.points + additions.points.values)
            .filter { it.elementUuid == uuid }.map { it.uuid }.toSet()
        mutableState.value = state.copy(
            additions = additions.copy(elements = additions.elements - uuid, points = additions.points - removedPoints,
                removedWorks = if (uuid in additions.elements) additions.removedWorks else additions.removedWorks + (uuid to work.version),
                removedPoints = additions.removedPoints - removedPoints, editedWorks = additions.editedWorks - uuid),
            pointEdits = state.pointEdits - removedPoints,
            lineEdits = state.lineEdits.filterValues { it.value.elementUuid != uuid },
            declaredLengths = state.declaredLengths - uuid,
            area = state.area.copy(edits = state.area.edits.filterValues { it.value.elementUuid != uuid },
                quantities = state.area.quantities - uuid)
        )
        publish()
    }

    /** Open the same geometry parameters from a work row without creating another paid work. */
    internal fun selectFirstWorkspacePlacement(workUuid: String): Boolean {
        val point = displayPoints().firstOrNull { it.elementUuid == workUuid }
        if (point != null) { selectPoint(point.uuid); return state.value.selectedPointUuid == point.uuid }
        val line = displayLines().firstOrNull { it.elementUuid == workUuid }
        if (line != null) { selectLine(line.uuid); return state.value.selectedLineUuid == line.uuid }
        val area = displayAreas().firstOrNull { it.elementUuid == workUuid }
        if (area != null) { selectArea(area.uuid); return state.value.area.selected == area.uuid }
        return false
    }

    internal fun pointCanBePlaced(uuid: String): Boolean {
        val capacity = pointElements().find { it.uuid == uuid }?.pointCapacity() ?: return false
        return displayPoints().count { it.elementUuid == uuid } < capacity
    }

    internal fun addCatalogWork(item: ElementCatalogItem, quantity: ElementQuantity, place: Boolean) {
        if (!editable() || (place && !item.supportsWorkspacePlacement())) return
        ElementQuantityRules.validate(quantity, item.unit, item.mode)
        val uuid = newRecordUuid()
        val itemDraft = item.toWorkspaceElement(uuid, state.value.draft.recordUuid, quantity)
        if (place && item.placementKind == PlacementKind.POINT && itemDraft.pointCapacity() == null) return
        cancelPlacement()
        commitInput()
        undo.add(form())
        redo.clear()
        val additions = state.value.additions
        mutableState.value = state.value.copy(additions = additions.copy(elements = additions.elements + (uuid to itemDraft)))
        publish()
        if (place) startWorkspacePlacement(itemDraft)
    }

    internal fun startWorkspacePlacement(element: MeasurementElement) {
        if (!editable()) return
        when ((element.source as? ElementSource.CatalogSelection)?.placementKind) {
            PlacementKind.POINT -> {
                if (!pointCanBePlaced(element.uuid)) return
                cancelPlacement()
                clearDrawingSelection()
                mutableState.value = state.value.copy(pointElementUuid = element.uuid)
            }
            PlacementKind.LINE -> beginLine(element.uuid)
            PlacementKind.AREA -> beginArea(AreaRole.GENERAL_AREA, element.uuid)
            else -> Unit
        }
    }

    internal fun cancelPlacement() {
        cancelLineCreation()
        cancelAreaCreation()
        mutableState.value = state.value.copy(pointElementUuid = null)
    }

    internal fun openWorkspaceCoordinates(kind: WorkspaceCoordinatesKind) {
        if (!editable() || state.value.coordinates != null) return
        val state = state.value
        val target = when (kind) {
            WorkspaceCoordinatesKind.POINT -> displayPoints().find { it.uuid == state.selectedPointUuid }
                ?.takeUnless { it.mounting.unresolved }?.let { point ->
                    Triple(point.uuid, listOf(PointPositionMm(point.xMm, point.yMm)), null)
                }
            WorkspaceCoordinatesKind.LINE -> displayLines().find { it.uuid == state.selectedLineUuid }
                ?.takeUnless { it.unresolved }?.let { line -> Triple(line.uuid, listOf(line.start, line.end), null) }
            WorkspaceCoordinatesKind.AREA -> displayAreas().find { it.uuid == state.area.selected }
                ?.takeUnless { it.unresolved }?.let { area ->
                    Triple(area.uuid, area.shape.positions, (area.shape as? AreaShape.Circle)?.radiusMm?.times(2))
                }
        } ?: return
        val (uuid, points, diameter) = target
        val fields = points.flatMap { listOf(formatPointMetres(it.xMm), formatPointMetres(it.yMm)) } +
            listOfNotNull(diameter?.let(::formatPointMetres))
        mutableState.value = state.copy(coordinates = WorkspaceCoordinatesInput(kind, uuid, fields,
            hasDiameter = diameter != null))
    }

    internal fun changeWorkspaceCoordinate(index: Int, value: String) {
        val input = state.value.coordinates ?: return
        if (!editable() || index !in input.values.indices) return
        val values = input.values.toMutableList().apply { this[index] = value }
        mutableState.value = state.value.copy(coordinates = input.copy(values = values, invalidGeometry = false), error = null)
    }

    internal fun closeWorkspaceCoordinates() {
        if (editable()) mutableState.value = state.value.copy(coordinates = null)
    }

    internal fun applyWorkspaceCoordinates() {
        if (!editable()) return
        val input = state.value.coordinates ?: return
        val coordinates = input.millimetres() ?: return
        val geometry = validateMeasurementCreate(state.value.draft).geometry ?: return
        val positions = coordinates.take(if (input.hasDiameter) coordinates.size - 1 else coordinates.size)
            .chunked(2).map { PointPositionMm(it[0], it[1]) }
        val valid = when (input.kind) {
            WorkspaceCoordinatesKind.POINT -> {
                val source = displayPoints().find { it.uuid == input.uuid } ?: return
                val target = positions.single()
                if (!pointStrictlyInside(geometry, target.xMm, target.yMm)) false else {
                    closeWorkspaceCoordinates()
                    if (target != PointPositionMm(source.xMm, source.yMm))
                        stagePoint(source.uuid, PointMountingEdit(freeX = target.xMm, freeY = target.yMm))
                    true
                }
            }
            WorkspaceCoordinatesKind.LINE -> {
                val source = displayLines().find { it.uuid == input.uuid } ?: return
                val (start, end) = positions
                if (!lineStrictlyInside(geometry, start, end)) false else {
                    val changedStart = start != source.start
                    val changedEnd = end != source.end
                    if (changedStart || changedEnd) stageLine(source.copy(
                        start = start, end = end,
                        startMounting = if (changedStart) source.startMounting.freeMoved() else source.startMounting,
                        endMounting = if (changedEnd) source.endMounting.freeMoved() else source.endMounting,
                        needsReview = source.needsReview || source.precision == PointPrecision.EXACT
                    ), remeasuredStart = false.takeIf { changedStart }, remeasuredEnd = false.takeIf { changedEnd })
                    true
                }
            }
            WorkspaceCoordinatesKind.AREA -> {
                val source = displayAreas().find { it.uuid == input.uuid } ?: return
                val shape = if (input.hasDiameter) AreaShape.circleFromDiameter(positions.single(), coordinates.last())
                    else source.shape.withPositions(positions)
                if (!areaStrictlyInside(geometry, shape)) false else {
                    val moved = positions.indices.filter { positions[it] != source.shape.positions[it] }
                    val radiusChanged = shape is AreaShape.Circle && shape.radiusMm != (source.shape as AreaShape.Circle).radiusMm
                    if (shape != source.shape) stageArea(source.copy(
                        shape = shape,
                        mountings = source.mountings.mapIndexed { index, mounting ->
                            if (index in moved) mounting.freeMoved() else mounting
                        },
                        radiusMeasured = source.radiusMeasured || radiusChanged,
                        needsReview = source.needsReview || moved.any { source.mountings[it].anchor != null }
                    ), remeasured = state.value.area.edits[source.uuid]?.remeasured.orEmpty() - moved.toSet(),
                        remeasuredRadius = true.takeIf { radiusChanged })
                    true
                }
            }
        }
        if (valid) closeWorkspaceCoordinates()
        else mutableState.value = state.value.copy(coordinates = input.copy(invalidGeometry = true))
    }

    internal fun placeWorkspacePoint(x: Long, y: Long): Boolean {
        val uuid = state.value.pointElementUuid ?: return false
        if (!editable() || !pointCanBePlaced(uuid)) return false
        val geometry = validateMeasurementCreate(state.value.draft).geometry ?: return false
        if (!pointStrictlyInside(geometry, x, y)) {
            mutableState.value = state.value.copy(error = pointFailureMessage(PointPlacementFailure.OutsideContour()))
            return false
        }
        val id = newRecordUuid()
        val point = PointPlacement(id, uuid, x, y, state.value.original.updatedAt, 0, 0, deletedAt = null)
        commitInput()
        undo.add(form())
        redo.clear()
        val additions = state.value.additions
        val placedCount = displayPoints().count { it.elementUuid == uuid }
        val remaining = (pointElements().find { it.uuid == uuid }?.pointCapacity() ?: 0) > placedCount + 1
        mutableState.value = state.value.copy(
            additions = additions.copy(points = additions.points + (id to point)),
            // One point per tap; preserve placement until the declared count is reached.
            pointElementUuid = uuid.takeIf { remaining },
            selectedPointUuid = id
        )
        publish()
        return true
    }

    fun displayLines(): List<LinePlacement> {
        val state=state.value
        val geometry=validateMeasurementCreate(state.draft).geometry ?: return state.original.placements.lines
        val changed=geometry!=validateMeasurementCreate(state.original.draft).geometry
        return (state.original.placements.lines.map {it.uuid}+state.lineEdits.keys).distinct().mapNotNull {id ->
            val edit=state.lineEdits[id]
            val line=state.original.placements.lines.find {it.uuid==id} ?: edit?.value ?: return@mapNotNull null
            if(edit?.value?.deletedAt!=null || line.elementUuid in state.additions.removedWorks) null else previewLine(line,geometry,draftWalls(),changed,edit)
        }
    }
    fun lineCapacityError(): String? {
        val lines=displayLines()
        if(state.value.declaredLengths.any { (id,length) -> lines.filter {it.elementUuid==id && !it.unresolved}.sumOf {it.lengthMm}!=length })
            return "Довжина креслення змінилася. Повторіть явну дію «Взяти довжину з креслення» або скасуйте її через Undo."
        return lineElements().firstOrNull {element ->
            val capacity=state.value.declaredLengths[element.uuid] ?: element.lineCapacityMm()
            capacity!=null && lines.filter {it.elementUuid==element.uuid}.sumOf {it.lengthMm}>capacity
        }?.let {LinePlacementFailure.CapacityReached().message}
    }
    internal fun lineHasCapacity(uuid: String, candidate: LinePlacement? = null): Boolean {
        val work = lineElements().find { it.uuid == uuid } ?: return false
        val used = displayLines().filter { it.elementUuid == uuid && it.uuid != candidate?.uuid }.sumOf { it.lengthMm }
        val cap = state.value.declaredLengths[uuid] ?: work.lineCapacityMm() ?: return true
        return if (candidate == null) used < cap else candidate.lengthMm <= cap - used
    }
    fun beginLine(element: String) {
        if (!editable() || !lineHasCapacity(element)) return
        cancelAreaCreation();updateArea {it.copy(selected=null)}
        mutableState.value=state.value.copy(pointElementUuid=null,lineElementUuid=element,lineStart=null,linePreview=null,selectedLineUuid=null,selectedPointUuid=null)
    }
    fun lineTap(x:Long,y:Long) {
        if(!editable() || state.value.linePreview!=null)return
        val element=state.value.lineElementUuid ?: return
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        if(!pointStrictlyInside(geometry,x,y)) {mutableState.value=state.value.copy(error=LinePlacementFailure.InvalidSegment().message);return}
        val start=state.value.lineStart
        if(start==null) mutableState.value=state.value.copy(lineStart=PointPositionMm(x,y)) else {
            val end=PointPositionMm(x,y)
            if(!lineStrictlyInside(geometry,start,end)) {mutableState.value=state.value.copy(error=LinePlacementFailure.InvalidSegment().message);return}
            mutableState.value=state.value.copy(linePreview=LinePlacement(newRecordUuid(),element,start,end,state.value.original.updatedAt,0,0))
        }
    }
    internal fun finishWorkspaceLine(start: PointPositionMm, end: PointPositionMm): Boolean {
        val element = state.value.lineElementUuid ?: return false
        if (!editable()) return false
        val geometry = validateMeasurementCreate(state.value.draft).geometry ?: return false
        if (!lineStrictlyInside(geometry, start, end)) {
            mutableState.value = state.value.copy(error = LinePlacementFailure.InvalidSegment().message)
            return false
        }
        val id = newRecordUuid()
        val line = LinePlacement(id, element, start, end, state.value.original.updatedAt, 0, 0)
        if (!lineHasCapacity(element, line)) {
            mutableState.value = state.value.copy(error = LinePlacementFailure.CapacityReached().message)
            return false
        }
        stageLine(line)
        mutableState.value = state.value.copy(
            lineStart = null, linePreview = null, lineElementUuid = null, selectedLineUuid = id, lineMoveTarget = 0
        )
        return true
    }
    fun cancelLineCreation() {mutableState.value=state.value.copy(lineStart=null,linePreview=null,lineElementUuid=null)}
    fun acceptLineCreation() {
        if(!editable())return
        val line=state.value.linePreview ?: return
        if (!lineHasCapacity(line.elementUuid, line)) {
            mutableState.value = state.value.copy(linePreview = null, error = LinePlacementFailure.CapacityReached().message)
            return
        }
        stageLine(line)
        mutableState.value=state.value.copy(lineStart=null,linePreview=null,lineElementUuid=null,selectedLineUuid=line.uuid)
    }
    fun selectLine(uuid:String) {
        if(!editable())return
        if(displayLines().any {it.uuid==uuid}) mutableState.value=state.value.copy(area=state.value.area.copy(selected=null,tool=null,vertices=emptyList()),selectedLineUuid=uuid,selectedPointUuid=null,pointElementUuid=null,lineElementUuid=null,lineStart=null,linePreview=null)
    }
    fun stageLine(line:LinePlacement,confirm:Boolean=false,defer:Boolean=false,remeasuredStart:Boolean?=null,remeasuredEnd:Boolean?=null) {
        if(!editable())return
        commitInput();undo.add(form());redo.clear()
        mutableState.value=state.value.copy(lineEdits=state.value.lineEdits+(line.uuid to LinePlacementEdit(line,confirm,defer,remeasuredStart ?: state.value.lineEdits[line.uuid]?.remeasuredStart ?: false,remeasuredEnd ?: state.value.lineEdits[line.uuid]?.remeasuredEnd ?: false)),mountingInput=null,lineDrag=null,error=null)
        publish()
        lineCapacityError()?.let {mutableState.value=state.value.copy(error=it)}
    }
    fun clearDrawingSelection() {
        if(!editable())return
        cancelAreaDrag();cancelLineDrag();cancelPointDrag()
        mutableState.value=state.value.copy(selectedPointUuid=null,selectedLineUuid=null,area=state.value.area.copy(selected=null))
    }
    fun setLineMoveTarget(target:Int) { if(editable())mutableState.value=state.value.copy(lineMoveTarget=target) }
    fun beginLineDrag(uuid:String) {if(editable() && state.value.selectedLineUuid==uuid)mutableState.value=state.value.copy(lineDrag=displayLines().find {it.uuid==uuid})}
    fun beginLineHandleDrag(uuid: String, handle: Int) {
        setLineMoveTarget(handle)
        beginLineDrag(uuid)
    }
    fun previewLineDrag(dx:Double,dy:Double) {
        val source=displayLines().find {it.uuid==state.value.lineDrag?.uuid} ?: return
        if(!dx.isFinite() || !dy.isFinite() || dx !in -2_000_000.0..2_000_000.0 || dy !in -2_000_000.0..2_000_000.0)return
        fun move(p:PointPositionMm)=PointPositionMm(p.xMm+dx.roundToLong(),p.yMm+dy.roundToLong())
        val target=state.value.lineMoveTarget
        mutableState.value=state.value.copy(lineDrag=source.copy(start=if(target!=2)move(source.start) else source.start,
            end=if(target!=1)move(source.end) else source.end,
            startMounting=if(target!=2 && move(source.start)!=source.start)source.startMounting.freeMoved() else source.startMounting,endMounting=if(target!=1 && move(source.end)!=source.end)source.endMounting.freeMoved() else source.endMounting,
            needsReview=source.needsReview || (target != 2 && source.startMounting.anchor != null) ||
                (target != 1 && source.endMounting.anchor != null)))
    }
    fun cancelLineDrag() {mutableState.value=state.value.copy(lineDrag=null)}
    fun finishLineDrag() {
        val line=state.value.lineDrag ?: return;cancelLineDrag()
        val source=displayLines().find {it.uuid==line.uuid} ?: return
        if(line.start==source.start && line.end==source.end)return
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        if(!lineStrictlyInside(geometry,line.start,line.end)){mutableState.value=state.value.copy(error=LinePlacementFailure.InvalidSegment().message);return}
        stageLine(line,remeasuredStart=if(state.value.lineMoveTarget!=2)false else null,remeasuredEnd=if(state.value.lineMoveTarget!=1)false else null)
    }
    fun openLineMounting(first:Boolean) {
        if(!editable())return
        val id=state.value.selectedLineUuid ?: return
        val line=state.value.lineEdits[id]?.value ?: displayLines().find {it.uuid==id} ?: return
        updateArea {it.copy(mountingIndex=null)}
        mutableState.value=state.value.copy(mountingInput=WallAnchorInput.from(line.endpoint(first),draftWalls()),mountingLineStart=first)
    }
    fun confirmLine() {
        val id=state.value.selectedLineUuid ?: return
        val line=state.value.lineEdits[id]?.value ?: displayLines().find {it.uuid==id} ?: return
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        val a=line.startMounting.anchor?.let {solveWallAnchor(geometry,draftWalls(),it)}
        val b=line.endMounting.anchor?.let {solveWallAnchor(geometry,draftWalls(),it)}
        if(state.value.original.placements.lines.find {it.uuid==id}?.unresolved==true && (a==null || b==null || state.value.lineEdits[id]?.remeasuredStart!=true || state.value.lineEdits[id]?.remeasuredEnd!=true)) {mutableState.value=state.value.copy(error=LinePlacementFailure.RequiresMeasuredAnchors().message);return}
        stageLine(line.copy(start=a?.position ?: line.start,end=b?.position ?: line.end,
            startMounting=line.startMounting.copy(unresolved=false),endMounting=line.endMounting.copy(unresolved=false),
            unresolved=false,needsReview=false),confirm=true)
    }
    suspend fun confirmLineAndContinueReview() {
        confirmLine()
        if(state.value.error!=null)return
        if(canSaveMountingDraft())save(keepEditing=true)
    }
    fun openLineProperties() {
        if(!editable())return
        val line=displayLines().find {it.uuid==state.value.selectedLineUuid} ?: return
        mutableState.value=state.value.copy(lineProperties=LinePropertiesInput(line.uuid, line.widthMm?.toString().orEmpty(), line.note,
            length = formatPointMetres(line.lengthMm)))
    }
    fun changeLineProperties(width: String? = null, note: String? = null, length: String? = null) {
        if(!editable())return
        mutableState.value=state.value.copy(lineProperties=state.value.lineProperties?.let {it.copy(width = width ?: it.width, note = note ?: it.note, length = length ?: it.length)})
    }
    fun dismissLineProperties() {
        if(state.value.lineProperties?.dirty==true)mutableState.value=state.value.copy(linePropertiesDismiss=true) else discardLineProperties()
    }
    fun discardLineProperties() {mutableState.value=state.value.copy(lineProperties=null,linePropertiesDismiss=false)}
    fun continueLineProperties() {mutableState.value=state.value.copy(linePropertiesDismiss=false)}
    fun applyLineProperties() {
        if(!editable())return
        val input=state.value.lineProperties?.takeIf {it.valid} ?: return
        val line = displayLines().find { it.uuid == input.uuid } ?: return
        val candidate = linePropertiesCandidate(line, input) ?: return
        stageLine(candidate, remeasuredEnd = if (candidate.end != line.end) false else null)
        discardLineProperties()
    }

    internal fun linePropertiesCandidate(line: LinePlacement, input: LinePropertiesInput): LinePlacement? {
        if (!input.valid) return null
        val length = input.length?.let { parsePointMetres(it, 3_000_000)?.takeIf { mm -> mm > 0 } ?: return null }
        val details = line.copy(widthMm = input.width.toLongOrNull(), note = input.note)
        if (length == null || length == line.lengthMm) return details
        if (line.unresolved) return null
        val geometry = validateMeasurementCreate(state.value.draft).geometry ?: return null
        val dx = (line.end.xMm - line.start.xMm).toDouble()
        val dy = (line.end.yMm - line.start.yMm).toDouble()
        val scale = length / hypot(dx, dy)
        val x = line.start.xMm + dx * scale
        val y = line.start.yMm + dy * scale
        val end = (-1L..1L).flatMap { ox ->
            (-1L..1L).map { oy -> PointPositionMm(x.roundToLong() + ox, y.roundToLong() + oy) }
        }.sortedBy { hypot(it.xMm - x, it.yMm - y) }.firstOrNull {
            lineStrictlyInside(geometry, line.start, it) && lineLengthMm(line.start, it) == length
        } ?: return null
        return details.copy(end = end, endMounting = line.endMounting.freeMoved(),
            needsReview = line.needsReview || line.endMounting.anchor != null)
            .takeIf { lineHasCapacity(line.elementUuid, it) }
    }
    fun requestLineDelete() {if(editable())mutableState.value=state.value.copy(lineDeleteConfirm=true)}
    fun cancelLineDelete() {mutableState.value=state.value.copy(lineDeleteConfirm=false)}
    fun deleteLine() {
        if(!editable())return
        val id=state.value.selectedLineUuid ?: return
        val line=displayLines().find {it.uuid==id} ?: return
        if(state.value.original.placements.lines.any {it.uuid==id})stageLine(line.copy(deletedAt=0)) else {
            commitInput();undo.add(form());redo.clear();mutableState.value=state.value.copy(lineEdits=state.value.lineEdits-id);publish()
        }
        mutableState.value=state.value.copy(lineDeleteConfirm=false,selectedLineUuid=null)
    }
    fun requestDeclaredLength(element:String?) {if(editable())mutableState.value=state.value.copy(lengthConfirm=element)}
    fun takeDeclaredLength() {
        if(!editable())return
        val element=state.value.lengthConfirm ?: return
        val lines=displayLines().filter {it.elementUuid==element && !it.unresolved}
        val length=lines.sumOf {it.lengthMm}
        if(length<=0)return
        commitInput();undo.add(form());redo.clear()
        mutableState.value=state.value.copy(declaredLengths=state.value.declaredLengths+(element to length),lengthConfirm=null);publish()
    }


    fun displayAreas(): List<AreaFeature> {
        val s=state.value
        val g=validateMeasurementCreate(s.draft).geometry ?: return s.original.placements.areas
        val changed=g!=validateMeasurementCreate(s.original.draft).geometry
        return (s.original.placements.areas.map {it.uuid}+s.area.edits.keys).distinct().mapNotNull {id ->
            val edit=s.area.edits[id]
            val old=s.original.placements.areas.find {it.uuid==id} ?: edit?.value ?: return@mapNotNull null
            if(edit?.value?.deletedAt!=null || old.elementUuid in s.additions.removedWorks)null else previewArea(old,g,draftWalls(),changed,edit)
        }
    }
    private fun updateArea(change:(AreaEditorState)->AreaEditorState) {
        mutableState.value=state.value.copy(area=change(state.value.area))
    }
    fun areaCapacityError(): String? {
        val areas=displayAreas()
        val stale=state.value.area.quantities.any { (id,qty) -> areaElements().find {it.uuid==id}?.let {
            areaQuantityMilli(areas.filter {a -> a.elementUuid==id},it.unit!!)!=qty
        }!=false }
        if(stale)return "Креслення змінилося. Повторіть «Взяти кількість з креслення» або скасуйте через Undo."
        return areaElements().firstOrNull {e ->
            val cap=state.value.area.quantities[e.uuid] ?: e.lineCapacityMm()
            cap!=null && areaQuantityMilli(areas.filter {it.elementUuid==e.uuid},e.unit!!,true)>cap
        }?.let { AreaCapacityMessage }
    }
    internal fun areaHasCapacity(uuid: String, candidate: AreaFeature? = null): Boolean {
        val work = areaElements().find { it.uuid == uuid } ?: return false
        val areas = displayAreas().filter { it.uuid != candidate?.uuid }
        if (areas.size >= 1000) return false
        val existing = areas.filter { it.elementUuid == uuid }
        val cap = state.value.area.quantities[uuid] ?: work.lineCapacityMm() ?: return true
        val unit = checkNotNull(work.unit)
        if (candidate != null) return areaQuantityMilli(existing + candidate, unit, includeUnresolved = true) <= cap
        val used = areaQuantityMilli(existing, unit, includeUnresolved = true)
        return if (unit == "шт.") cap - used >= 1000 else cap > used
    }
    fun beginArea(role:AreaRole,element:String?=null,tool:AreaTool=AreaTool.RECTANGLE) {
        if (!editable() || element != null && !areaHasCapacity(element)) return
        cancelLineCreation();cancelPointDrag();cancelLineDrag()
        mutableState.value=state.value.copy(selectedPointUuid=null,selectedLineUuid=null,pointElementUuid=null)
        updateArea {it.copy(selected=null,tool=tool,role=role,element=element,vertices=emptyList(),diameter="",drag=null)}
    }
    fun setAreaTool(tool:AreaTool) {if(editable())updateArea {it.copy(tool=tool,vertices=emptyList(),diameter="")}}
    fun setAreaDiameter(text:String) {if(editable())updateArea {it.copy(diameter=text)}}
    fun cancelAreaCreation() {updateArea {it.copy(tool=null,vertices=emptyList(),element=null)}}
    fun areaTap(x:Long,y:Long) {
        if(!editable())return
        val a=state.value.area; val tool=a.tool ?: return
        if(a.vertices.size>=if(tool==AreaTool.POLYGON)16 else if(tool==AreaTool.CIRCLE)1 else 2)return
        val g=validateMeasurementCreate(state.value.draft).geometry ?: return
        if(!pointStrictlyInside(g,x,y)){mutableState.value=state.value.copy(error="Точка має лежати всередині стелі.");return}
        updateArea {it.copy(vertices=it.vertices+PointPositionMm(x,y))}
    }
    fun areaCreationShape(): AreaShape? = runCatching {
        val a=state.value.area
        when(a.tool) {
            AreaTool.RECTANGLE -> {require(a.vertices.size==2);AreaShape.rectangle(a.vertices[0],a.vertices[1])}
            AreaTool.POLYGON -> AreaShape.Polygon(a.vertices)
            AreaTool.CIRCLE -> AreaShape.circleFromDiameter(a.vertices.single(),checkNotNull(parsePointMetres(a.diameter,2_000_000)))
            null -> return@runCatching null
        }.also {canonicalTwiceAreaMm2(it)}
    }.getOrNull()
    fun acceptAreaCreation() {
        if(!editable())return
        val shape=areaCreationShape() ?: return
        val g=validateMeasurementCreate(state.value.draft).geometry ?: return
        if(!areaStrictlyInside(g,shape)){mutableState.value=state.value.copy(error="Уся фігура має лежати всередині стелі, без самоперетинів і дотику до стін.");return}
        val a=state.value.area
        val feature=AreaFeature(newRecordUuid(),state.value.draft.recordUuid,shape,state.value.original.updatedAt,0,0,
            elementUuid=a.element,role=a.role,radiusMeasured=shape is AreaShape.Circle)
        if (a.element != null && !areaHasCapacity(a.element, feature)) {
            mutableState.value = state.value.copy(error = AreaCapacityMessage)
            return
        }
        stageArea(feature)
        updateArea {it.copy(tool=null,vertices=emptyList(),element=null,selected=feature.uuid)}
    }
    fun selectArea(uuid:String) {
        if(!editable() || displayAreas().none {it.uuid==uuid})return
        cancelAreaCreation();cancelLineCreation()
        mutableState.value=state.value.copy(selectedPointUuid=null,selectedLineUuid=null,pointElementUuid=null)
        updateArea {it.copy(selected=uuid,moveVertex=null)}
    }
    fun stageArea(value:AreaFeature,confirm:Boolean=false,defer:Boolean=false,remeasured:Set<Int>?=null,remeasuredRadius:Boolean?=null) {
        if(!editable())return
        commitInput();undo.add(form());if(undo.size>100)undo.removeAt(0);redo.clear()
        updateArea {it.copy(edits=it.edits+(value.uuid to AreaFeatureEdit(value,confirm,defer,
            remeasured ?: it.edits[value.uuid]?.remeasured.orEmpty(),remeasuredRadius ?: (it.edits[value.uuid]?.remeasuredRadius==true))),drag=null,mountingIndex=null)}
        mutableState.value=state.value.copy(mountingInput=null,error=null);publish()
        areaCapacityError()?.let {mutableState.value=state.value.copy(error=it)}
    }
    fun setAreaMoveVertex(index:Int?) {if(editable())updateArea {it.copy(moveVertex=index)}}
    fun beginAreaDrag(uuid:String) {
        if(editable() && state.value.area.selected==uuid)updateArea {it.copy(drag=displayAreas().find {a -> a.uuid==uuid})}
    }
    private var directAreaGrip = false
    fun beginAreaHandleDrag(uuid: String, vertex: Int?) {
        directAreaGrip = true
        setAreaMoveVertex(vertex)
        beginAreaDrag(uuid)
    }
    fun previewAreaDrag(dx:Double,dy:Double) {
        val a=state.value.area
        val source=displayAreas().find {it.uuid==a.drag?.uuid} ?: return
        if(!dx.isFinite() || !dy.isFinite() || dx !in -2_000_000.0..2_000_000.0 || dy !in -2_000_000.0..2_000_000.0)return
        val original = source.shape.positions
        val vertex = a.moveVertex
        val deltaX = dx.roundToLong()
        val deltaY = dy.roundToLong()
        val positions = if (directAreaGrip && vertex != null && original.isAxisAlignedRectangle()) {
            val corner = original[vertex]
            original.map { p ->
                PointPositionMm(p.xMm + if (p.xMm == corner.xMm) deltaX else 0,
                    p.yMm + if (p.yMm == corner.yMm) deltaY else 0)
            }
        } else original.mapIndexed { i, p ->
            if (vertex == null || vertex == i) PointPositionMm(p.xMm + deltaX, p.yMm + deltaY) else p
        }
        val moved=source.copy(shape=source.shape.withPositions(positions),mountings=source.mountings.mapIndexed {i,m -> if(positions[i]!=source.shape.positions[i])m.freeMoved() else m},
            needsReview=source.needsReview || source.mountings.indices.any { positions[it] != original[it] && source.mountings[it].anchor != null })
        updateArea {it.copy(drag=moved)}
    }
    fun cancelAreaDrag() {directAreaGrip = false; updateArea {it.copy(drag=null)}}
    fun finishAreaDrag() {
        val a=state.value.area; val moved=a.drag ?: return;cancelAreaDrag()
        val source=displayAreas().find {it.uuid==moved.uuid} ?: return
        if(source.shape==moved.shape)return
        if(source.unresolved) {
            mutableState.value=state.value.copy(error="Нерозв’язану фігуру потрібно повторно виміряти за стінними опорами. Перетягування не змінює збережене положення.")
            return
        }
        val g=validateMeasurementCreate(state.value.draft).geometry ?: return
        if(!areaStrictlyInside(g,moved.shape)){mutableState.value=state.value.copy(error="Фігура перетинає стіну або себе. Положення не змінено.");return}
        val measured=a.edits[moved.uuid]?.remeasured.orEmpty().filter { source.shape.positions[it] == moved.shape.positions[it] }.toSet()
        stageArea(moved,remeasured=measured)
    }
    fun openAreaMounting(index:Int) {
        if(!editable())return
        val area=state.value.area.edits[state.value.area.selected]?.value ?: displayAreas().find {it.uuid==state.value.area.selected} ?: return
        if(index !in area.shape.positions.indices)return
        mutableState.value=state.value.copy(mountingInput=WallAnchorInput.from(area.point(index),draftWalls()),mountingLineStart=null)
        updateArea {it.copy(mountingIndex=index)}
    }
    fun confirmArea() {
        val a=state.value.area
        val value=a.edits[a.selected]?.value ?: displayAreas().find {it.uuid==a.selected} ?: return
        val g=validateMeasurementCreate(state.value.draft).geometry ?: return
        val old=state.value.original.placements.areas.find {it.uuid==value.uuid} ?: value
        val candidate=previewArea(old,g,draftWalls(),false,AreaFeatureEdit(value,confirm=true,
            remeasured=a.edits[value.uuid]?.remeasured.orEmpty(),remeasuredRadius=a.edits[value.uuid]?.remeasuredRadius==true))
        if(candidate.unresolved){mutableState.value=state.value.copy(error="Повторно виміряйте всі вершини/центр і радіус, потім підтвердьте.");return}
        stageArea(candidate,confirm=true)
    }
    fun openAreaProperties() {
        if(!editable())return
        val area=state.value.area.edits[state.value.area.selected]?.value ?: displayAreas().find {it.uuid==state.value.area.selected} ?: return
        val rectangle = area.shape.positions.takeIf { it.isAxisAlignedRectangle() }
        updateArea { it.copy(properties = AreaPropertiesInput(
            area.uuid, area.note, area.role, (area.shape as? AreaShape.Circle)?.let { formatPointMetres(it.radiusMm * 2) }.orEmpty(),
            width = rectangle?.let { formatPointMetres(it.maxOf { p -> p.xMm } - it.minOf { p -> p.xMm }) },
            height = rectangle?.let { formatPointMetres(it.maxOf { p -> p.yMm } - it.minOf { p -> p.yMm }) }
        )) }
    }
    fun changeAreaProperties(change:(AreaPropertiesInput)->AreaPropertiesInput) {if(editable())updateArea {it.copy(properties=it.properties?.let(change))}}
    fun dismissAreaProperties() {if(state.value.area.properties?.dirty==true)updateArea {it.copy(dismissProperties=true)} else discardAreaProperties()}
    fun discardAreaProperties() {updateArea {it.copy(properties=null,dismissProperties=false)}}
    fun continueAreaProperties() {updateArea {it.copy(dismissProperties=false)}}
    fun applyAreaProperties() {
        if(!editable())return
        val input=state.value.area.properties ?: return
        val old = displayAreas().find { it.uuid == input.uuid } ?: return
        val candidate = areaPropertiesCandidate(old, input) ?: return
        val measured = state.value.area.edits[old.uuid]?.remeasured.orEmpty()
            .filter { old.shape.positions[it] == candidate.shape.positions[it] }.toSet()
        stageArea(candidate, remeasured = measured,
            remeasuredRadius = if (input.diameterMeasured) true else null)
        discardAreaProperties()
    }

    internal fun areaPropertiesCandidate(old: AreaFeature, input: AreaPropertiesInput): AreaFeature? {
        val circle = old.shape as? AreaShape.Circle
        val positions = old.shape.positions
        val shape = when {
            circle != null -> runCatching {
                AreaShape.circleFromDiameter(circle.center, checkNotNull(parsePointMetres(input.diameter, 2_000_000)))
            }.getOrNull() ?: return null
            input.width != null && input.height != null && positions.isAxisAlignedRectangle() -> {
                val width = parsePointMetres(input.width, 2_000_000)?.takeIf { it > 0 } ?: return null
                val height = parsePointMetres(input.height, 2_000_000)?.takeIf { it > 0 } ?: return null
                val first = positions.first()
                old.shape.withPositions(positions.map { vertex ->
                    PointPositionMm(first.xMm + (vertex.xMm - first.xMm).compareTo(0L) * width,
                        first.yMm + (vertex.yMm - first.yMm).compareTo(0L) * height)
                })
            }
            else -> old.shape
        }
        val moved = positions.indices.filter { positions[it] != shape.positions[it] }.toSet()
        val candidate = old.copy(shape = shape, note = input.note, role = input.role,
            radiusMeasured = circle != null && (input.diameterMeasured || shape == old.shape && old.radiusMeasured),
            mountings = old.mountings.mapIndexed { index, mounting -> if (index in moved) mounting.freeMoved() else mounting },
            needsReview = old.needsReview || moved.any { old.mountings[it].anchor != null })
        if (shape == old.shape) return candidate
        val geometry = validateMeasurementCreate(state.value.draft).geometry ?: return null
        if (old.unresolved || !areaStrictlyInside(geometry, shape)) return null
        return candidate.takeIf { old.elementUuid == null || areaHasCapacity(old.elementUuid, candidate) }
    }
    fun requestAreaDelete() {if(editable())updateArea {it.copy(deleteConfirm=true)}}
    fun cancelAreaDelete() {updateArea {it.copy(deleteConfirm=false)}}
    fun deleteArea() {
        if(!editable())return
        val a=displayAreas().find {it.uuid==state.value.area.selected} ?: return
        if(state.value.original.placements.areas.any {it.uuid==a.uuid})stageArea(a.copy(deletedAt=0)) else {
            commitInput();undo.add(form());redo.clear();updateArea {it.copy(edits=it.edits-a.uuid)};publish()
        }
        updateArea {it.copy(selected=null,deleteConfirm=false)}
    }
    fun requestAreaQuantity(id:String?) {if(editable())updateArea {it.copy(quantityConfirm=id)}}
    fun takeAreaQuantity() {
        if(!editable())return
        val id=state.value.area.quantityConfirm ?: return
        val e=areaElements().find {it.uuid==id} ?: return
        val qty=areaQuantityMilli(displayAreas().filter {it.elementUuid==id},e.unit!!)
        if(qty<=0)return
        commitInput();undo.add(form());redo.clear();updateArea {it.copy(quantities=it.quantities+(id to qty),quantityConfirm=null)};publish()
    }

    /** Local saveable data, never commands or route payloads. History is bounded. */
    fun snapshot(): String = buildJsonObject {
        put("id", state.value.original.measurementId); put("version", state.value.original.updatedAt); put("token", token)
        put("original", state.value.original.draft.snapshot()); put("draft", state.value.draft.snapshot())
        put("wallGeneration",state.value.wallGeneration)
        put("lineElements",encodeAreaElementSnapshots(state.value.original.lineElements))
        put("areaElements",encodeAreaElementSnapshots(state.value.original.areaElements))
        put("materialElements", mountingCodec.encodeToJsonElement(state.value.original.materialElements))
        put("otherElements", mountingCodec.encodeToJsonElement(state.value.original.otherElements))
        if (state.value.original.pointElements.isNotEmpty()) {
            put("pointElements", mountingCodec.encodeToJsonElement(state.value.original.pointElements))
        }
        if (!state.value.additions.isEmpty) put("workspaceAdditions", mountingCodec.encodeToString(state.value.additions))
        state.value.pointElementUuid?.let { put("pointElement", it) }
        state.value.coordinates?.let { put("coordinates", mountingCodec.encodeToJsonElement(WorkspaceCoordinatesInput.serializer(), it)) }
        put("areaEditor",mountingCodec.encodeToString(state.value.area.copy(drag=null)))
        put("placements",mountingCodec.encodeToString(state.value.original.placements))
        state.value.lineProperties?.let {put("lineProperties",mountingCodec.encodeToString(it))}
        put("lineMoveTarget",state.value.lineMoveTarget)
        put("lineEdits",mountingCodec.encodeToString(state.value.lineEdits))
        put("declaredLengths",mountingCodec.encodeToString(state.value.declaredLengths))
        state.value.selectedLineUuid?.let {put("selectedLine",it)}
        state.value.lineElementUuid?.let {put("lineElement",it)}
        state.value.lineStart?.let {put("lineStart",mountingCodec.encodeToString(it))}
        state.value.linePreview?.let {put("linePreview",mountingCodec.encodeToString(it))}
        state.value.mountingLineStart?.let {put("mountingLineStart",it)}
        put("pointEdits",mountingCodec.encodeToString(state.value.pointEdits))
        state.value.original.sourceDrawingJson?.let { put("sourceDrawingJson",it) }
        state.value.selectedPointUuid?.let { put("selectedPoint",it) }
        state.value.mountingInput?.let { put("mountingInput",mountingCodec.encodeToString(it)) }
        (attempt ?: reconcileAttempt)?.let { write ->
            put("receipt", write.operation)
            write.now?.let { put("writeTime", it) }
        }
        put("reviewPrompt",state.value.reviewPrompt);put("reviewing",state.value.reviewing)
        put("writeStarted", state.value.saving || state.value.saved || state.value.uncertain || reconcileAfterRestore)
        // Leave room for the draft and the rest of Android's saved instance state.
        fun bounded(history: List<CeilingFormHistory>): List<String> {
            var remaining = 64_000
            return history.takeLast(100).asReversed().map { mountingCodec.encodeToString(it) }.takeWhile {
                remaining -= it.length
                remaining >= 0
            }.asReversed()
        }
        putJsonArray("undo") { bounded(undo + listOfNotNull(inputStart?.takeIf { it != form() })).forEach { add(it) } }
        putJsonArray("redo") { bounded(redo).forEach { add(it) } }
    }.toString()

    companion object {
        fun restore(store: MeasurementEditStore?, snapshot: String, projectId: Long, measurementId: Long): MeasurementEditModel? = runCatching {
            require(snapshot.length <= 2_000_000)
            val json = Json.parseToJsonElement(snapshot).jsonObject
            require(json.getValue("id").jsonPrimitive.long == measurementId)
            val token = json.getValue("token").jsonPrimitive.content
            store?.retained(token)?.let { retained ->
                require(retained.state.value.original.measurementId == measurementId && retained.state.value.draft.projectId == projectId)
                return@runCatching retained
            }
            val original = requireNotNull(MeasurementCreateDraft.restore(json.getValue("original").jsonPrimitive.content, projectId))
            val draft = requireNotNull(MeasurementCreateDraft.restore(json.getValue("draft").jsonPrimitive.content, projectId))
            require(original.precision == draft.precision && original.recordUuid == draft.recordUuid)
            MeasurementEditModel(store, MeasurementEditDocument(measurementId, json.getValue("version").jsonPrimitive.long, original,
                json["placements"]?.jsonPrimitive?.content?.let { mountingCodec.decodeFromString<CeilingPlacementDocument>(it) } ?: CeilingPlacementDocument(),
                json["sourceDrawingJson"]?.jsonPrimitive?.content,
                lineElements = decodeAreaElementSnapshots(json["lineElements"]),
                areaElements = decodeAreaElementSnapshots(json["areaElements"]),
                pointElements = json["pointElements"]?.let {
                    mountingCodec.decodeFromJsonElement<List<MeasurementElement>>(it)
                }.orEmpty(),
                materialElements = json["materialElements"]?.let { mountingCodec.decodeFromJsonElement<List<MeasurementElement>>(it) }.orEmpty(),
                otherElements = json["otherElements"]?.let { mountingCodec.decodeFromJsonElement<List<MeasurementElement>>(it) }.orEmpty()), token).also {
                it.mutableState.value = it.state.value.copy(draft = draft,
                    additions = json["workspaceAdditions"]?.jsonPrimitive?.content?.let {
                        mountingCodec.decodeFromString<WorkspacePlacementDraft>(it)
                    } ?: WorkspacePlacementDraft(),
                    pointElementUuid = json["pointElement"]?.jsonPrimitive?.content,
                    coordinates = json["coordinates"]?.let { mountingCodec.decodeFromJsonElement(WorkspaceCoordinatesInput.serializer(), it) },
                    area=json["areaEditor"]?.jsonPrimitive?.content?.let {mountingCodec.decodeFromString<AreaEditorState>(it)} ?: AreaEditorState(),
                    lineProperties=json["lineProperties"]?.jsonPrimitive?.content?.let {mountingCodec.decodeFromString<LinePropertiesInput>(it)},
                    lineMoveTarget=json["lineMoveTarget"]?.jsonPrimitive?.intOrNull ?: 0,
                    lineEdits=json["lineEdits"]?.jsonPrimitive?.content?.let {mountingCodec.decodeFromString<Map<String,LinePlacementEdit>>(it)} ?: emptyMap(),
                    declaredLengths=json["declaredLengths"]?.jsonPrimitive?.content?.let {mountingCodec.decodeFromString<Map<String,Long>>(it)} ?: emptyMap(),
                    selectedLineUuid=json["selectedLine"]?.jsonPrimitive?.content,
                    lineElementUuid=json["lineElement"]?.jsonPrimitive?.content,
                    lineStart=json["lineStart"]?.jsonPrimitive?.content?.let {mountingCodec.decodeFromString<PointPositionMm>(it)},
                    linePreview=json["linePreview"]?.jsonPrimitive?.content?.let {mountingCodec.decodeFromString<LinePlacement>(it)},
                    mountingLineStart=json["mountingLineStart"]?.jsonPrimitive?.booleanOrNull,
                    pointEdits=json["pointEdits"]?.jsonPrimitive?.content?.let { mountingCodec.decodeFromString<Map<String,PointMountingEdit>>(it) } ?: emptyMap(),
                    selectedPointUuid=json["selectedPoint"]?.jsonPrimitive?.content,
                    mountingInput=json["mountingInput"]?.jsonPrimitive?.content?.let { mountingCodec.decodeFromString<WallAnchorInput>(it) },
                    reviewPrompt=json["reviewPrompt"]?.jsonPrimitive?.booleanOrNull ?: false,
                    reviewing=json["reviewing"]?.jsonPrimitive?.booleanOrNull ?: false,
                    wallGeneration=json["wallGeneration"]?.jsonPrimitive?.content ?: token)
                it.reconcileAttempt = json["receipt"]?.jsonPrimitive?.content?.let { receipt ->
                    CeilingWriteAttempt(receipt, json["writeTime"]?.jsonPrimitive?.longOrNull)
                }
                for ((key, history) in listOf("undo" to it.undo, "redo" to it.redo)) {
                    json.getValue(key).jsonArray.takeLast(100).forEach { value ->
                        val entry=runCatching { mountingCodec.decodeFromString<CeilingFormHistory>(value.jsonPrimitive.content) }.getOrElse { CeilingFormHistory(value.jsonPrimitive.content,emptyMap()) }
                        val restoredDraft=checkNotNull(MeasurementCreateDraft.restore(entry.draft,projectId))
                        require(restoredDraft.precision==original.precision && restoredDraft.recordUuid==original.recordUuid)
                        history.add(entry)
                    }
                }
                it.publish()
                // Reconciliation is performed by the screen as a READ, never a Save replay.
                it.reconcileAfterRestore = json.getValue("writeStarted").jsonPrimitive.boolean
                it.needsReadAfterRestore = true
                it.mutableState.value = it.state.value.copy(checking = store != null)
            }
        }.getOrNull()
    }
    private var needsReadAfterRestore: Boolean = false
    var reconcileAfterRestore: Boolean = false
        private set
    suspend fun reconcile(keepEditing: Boolean = false) {
        if (!needsReadAfterRestore || state.value.saving || state.value.saved || store == null) return
        needsReadAfterRestore = false
        val recoveringWrite = reconcileAfterRestore
        reconcileAfterRestore = false
        try {
            val before = state.value
            val loaded = withContext(Dispatchers.Default) { checkNotNull(store).load(before.draft.projectId, before.original.measurementId) }
            val pending = reconcileAttempt
            reconcileAttempt = null
            if (recoveringWrite && pending != null) {
                val committed = withContext(Dispatchers.Default) {
                    store.operationCommitted(pending.operation, loaded.draft.recordUuid, loaded.updatedAt)
                }
                if(committed) {
                    val changed=validateMeasurementCreate(loaded.draft).geometry!=validateMeasurementCreate(before.original.draft).geometry
                    val review=changed && (loaded.placements.points.any {it.mounting.needsReview} || loaded.placements.lines.any {it.needsReview} || loaded.placements.areas.any {it.needsReview})
                    mutableState.value=if (keepEditing) workspaceSaved(loaded, before, review)
                        else MeasurementEditState(loaded,saved=!review,reviewPrompt=review)
                } else if (keepEditing && loaded == before.original) {
                    attempt = pending
                    mutableState.value = before.copy(original = loaded, checking = false, uncertain = true,
                        error = "Результат збереження не підтверджено. Повторіть ту саму спробу або перезавантажте запис.")
                } else mutableState.value=before.copy(checking=false,conflict=true,error="Спробу не підтверджено. Чернетку збережено; перезавантажте актуальний запис перед новим Save.")
                return
            }
            val actual = validateMeasurementCreate(loaded.draft).geometry
            val intended = validateMeasurementCreate(before.draft).geometry
            if (recoveringWrite && before.additions.isEmpty && before.area.edits.isEmpty() && before.area.quantities.isEmpty() && before.original.placements.areas==loaded.placements.areas && before.lineEdits.isEmpty() && before.declaredLengths.isEmpty() && (loaded.updatedAt > before.original.updatedAt || loaded.updatedAt == before.original.updatedAt && !before.dirty) && actual == intended &&
                loaded.draft.name == before.draft.name.trim() && loaded.draft.notes == before.draft.notes.trim() &&
                loaded.placements.points.map { it.copy(updatedAt=0,lastCeilingEditUuid=null,mounting=it.mounting.copy(modifiedVersion=0,confirmedAt=null,deferredAt=null,anchorGeometryJson=null,lastValidGeometryJson=null)) } ==
                displayPoints().map { it.copy(updatedAt=0,lastCeilingEditUuid=null,mounting=it.mounting.copy(modifiedVersion=0,confirmedAt=null,deferredAt=null,anchorGeometryJson=null,lastValidGeometryJson=null)) }) {
                mutableState.value = if (keepEditing) workspaceSaved(loaded, before, false)
                    else MeasurementEditState(loaded, saved = true)
            } else if (loaded.updatedAt != before.original.updatedAt || loaded.draft.recordUuid != before.original.draft.recordUuid || loaded.placements!=before.original.placements) {
                mutableState.value = before.copy(checking = false, conflict = true, error = MeasurementEditConflict().message)
            } else mutableState.value = before.copy(original=loaded,checking = false)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, conflict = true, error = "Перевірте результат збереження: запис зараз недоступний. Перезавантажте його перед новим записом.")
        }
    }
}
