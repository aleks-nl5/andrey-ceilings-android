package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.db.ceilingDraftWalls
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.roundToLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

data class MeasurementEditState(
    val original: MeasurementEditDocument, val draft: MeasurementCreateDraft = original.draft,
    val saving: Boolean = false, val checking: Boolean = false, val saved: Boolean = false, val error: String? = null,
    val conflict: Boolean = false, val exitRequested: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val lineEdits: Map<String,LinePlacementEdit> = emptyMap(), val declaredLengths: Map<String,Long> = emptyMap(),
    val selectedLineUuid: String? = null, val lineElementUuid: String? = null,
    val lineStart: PointPositionMm? = null, val linePreview: LinePlacement? = null,
    val lineDrag: LinePlacement? = null, val lineMoveTarget: Int = 0, val mountingLineStart: Boolean? = null,
    val lineProperties:LinePropertiesInput?=null,val linePropertiesDismiss:Boolean=false,
    val lineDeleteConfirm: Boolean = false, val lengthConfirm: String? = null,
    val pointEdits: Map<String,PointMountingEdit> = emptyMap(), val selectedPointUuid: String? = null,
    val drag: PointDragPreview? = null, val mountingInput: WallAnchorInput? = null,
    val mountingDismiss: Boolean = false, val uncertain: Boolean = false,
    val reviewPrompt: Boolean = false, val reviewing: Boolean = false, val wallGeneration: String = newRecordUuid()
) {
    val dirty: Boolean get() = if (lineProperties?.dirty==true || lineEdits.isNotEmpty() || declaredLengths.isNotEmpty() || lineStart!=null || linePreview!=null || pointEdits.isNotEmpty() || mountingInput?.dirty==true) true else if (draft == original.draft) false else {
        val current = validateMeasurementCreate(draft)
        val baseline = validateMeasurementCreate(original.draft)
        draft.name.trim() != original.draft.name.trim() || draft.notes.trim() != original.draft.notes.trim() ||
            !current.canSave || current.geometry != baseline.geometry
    }
}

@Serializable
private data class CeilingFormHistory(val draft: String, val edits: Map<String,PointMountingEdit>, val wallGeneration:String="", val lines: Map<String,LinePlacementEdit> = emptyMap(), val lengths: Map<String,Long> = emptyMap())
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
    private var reconcileReceipt: String? = null
    private fun form() = CeilingFormHistory(state.value.draft.snapshot(),state.value.pointEdits,state.value.wallGeneration,state.value.lineEdits,state.value.declaredLengths)
    private var exitAction: (() -> Unit)? = null
    fun observePoints(): Flow<PointOverlayState> = store?.observePoints(state.value.original)
        ?: flowOf(PointOverlayState.Unavailable)

    private fun publish(draft: MeasurementCreateDraft = state.value.draft) {
        mutableState.value = state.value.copy(draft = draft, canUndo = inputStart?.let { it != CeilingFormHistory(draft.snapshot(),state.value.pointEdits,state.value.wallGeneration,state.value.lineEdits,state.value.declaredLengths) } == true || undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(), error = null)
    }
    fun beginInput() { if (!state.value.saving && !state.value.checking && !state.value.saved && inputStart == null) inputStart = form() }
    fun commitInput() {
        val start = inputStart ?: return
        inputStart = null
        if (start != form()) { undo.add(start); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        publish()
    }
    fun accept(intent: MeasurementCreateIntent, grouped: Boolean = false) {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.uncertain) return
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
        if (state.value.saving || state.value.checking || state.value.saved || state.value.uncertain) return
        commitInput()
        if (undo.isEmpty()) return
        redo.add(form()); restoreForm(undo.removeAt(undo.lastIndex))
    }
    fun redo() {
        if (state.value.saving || state.value.checking || state.value.saved || state.value.uncertain) return
        commitInput()
        if (redo.isEmpty()) return
        undo.add(form()); restoreForm(redo.removeAt(redo.lastIndex))
    }
    fun requestExit(action: () -> Unit) {
        if (state.value.saving || state.value.checking || state.value.saved) return
        cancelPointDrag(); cancelLineDrag(); commitInput()
        if (!state.value.dirty && !state.value.uncertain) action() else {
            exitAction = action
            mutableState.value = state.value.copy(exitRequested = true)
        }
    }
    fun continueEditing() { exitAction = null; mutableState.value = state.value.copy(exitRequested = false) }
    fun discardAndExit() {
        if (state.value.saving || state.value.checking || state.value.saved) return
        val action = exitAction
        exitAction = null; attempt=null; store?.release(token); undo.clear(); redo.clear(); inputStart = null
        mutableState.value = MeasurementEditState(state.value.original)
        action?.invoke()
    }
    /** Reload is a read and is offered explicitly only after a conflict, with discard confirmation. */
    suspend fun reload() {
        if (state.value.saving || state.value.checking || state.value.saved) return
        mutableState.value = state.value.copy(checking = true)
        try {
            val original = state.value.original
            val loaded = withContext(Dispatchers.Default) { checkNotNull(store).load(original.draft.projectId, original.measurementId) }
            undo.clear(); redo.clear(); inputStart = null; attempt=null; store?.release(token)
            mutableState.value = MeasurementEditState(loaded)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, error = "Запис недоступний. Чернетку залишено на екрані.")
        }
    }
    suspend fun save(keepEditing: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            if (state.value.saving || state.value.checking || state.value.saved || state.value.conflict || state.value.mountingInput!=null || state.value.lineProperties!=null) return
            commitInput()
            val before=state.value
            if (!validateMeasurementCreate(before.draft).canSave || !canSaveMountingDraft()) return
            val completion=store?.beginWrite() ?: return
            val command=attempt ?: CeilingWriteAttempt(newRecordUuid(),null).also { attempt=it }
            exitAction=null
            mutableState.value=before.copy(saving=true,exitRequested=false,error=null,drag=null)
            store?.retain(token,this)
            withContext(NonCancellable+Dispatchers.Default) {
                try {
                    val timestamp=command.now ?: checkNotNull(store).now().also { attempt=command.copy(now=it) }
                    val result=checkNotNull(store).update(before.original,before.draft,before.pointEdits,command.operation,before.wallGeneration,timestamp,completion,before.lineEdits,before.declaredLengths)
                    val geometryChanged=validateMeasurementCreate(before.original.draft).geometry!=validateMeasurementCreate(result.draft).geometry
                    undo.clear();redo.clear();attempt=null
                    mutableState.value=MeasurementEditState(result,
                        saved=!keepEditing && !(geometryChanged && (result.placements.points.any { it.mounting.needsReview } || result.placements.lines.any {it.needsReview})),
                        reviewPrompt=geometryChanged && (result.placements.points.any { it.mounting.needsReview } || result.placements.lines.any {it.needsReview}),
                        reviewing=keepEditing || before.reviewing,
                        selectedLineUuid=if(keepEditing && result.placements.points.none {it.mounting.needsReview})result.placements.lines.firstOrNull {it.needsReview}?.uuid else null,
                        selectedPointUuid=if (keepEditing) result.placements.points.firstOrNull { it.mounting.needsReview }?.uuid else null)
                } catch (conflict: MeasurementEditConflict) {
                    attempt=null;mutableState.value=before.copy(error=conflict.message,conflict=true);store?.release(token)
                } catch (failure: PointPlacementFailure.RetryConflict) {
                    attempt=null;mutableState.value=before.copy(error=pointFailureMessage(failure),conflict=true);store?.release(token)
                } catch (failure: MeasurementEditRejected) {
                    attempt=null;mutableState.value=before.copy(uncertain=false,error=failure.message);store?.release(token)
                } catch (failure: LinePlacementFailure) {
                    attempt=null;mutableState.value=before.copy(uncertain=false,error=failure.message);store?.release(token)
                } catch (_: IllegalArgumentException) {
                    attempt=null;mutableState.value=before.copy(uncertain=false,error="Перевірте контур, стінні опори й відступи. Чернетку збережено; недопустимі дані не записано.");store?.release(token)
                } catch (_: Exception) {
                    mutableState.value=before.copy(uncertain=true,error="Результат збереження не підтверджено. Повторіть ту саму спробу або перезавантажте запис; чернетку збережено.")
                } finally { store?.endWrite(completion) }
            }
        } finally { mutex.unlock() }
    }
    fun reviewNow() {
        mutableState.value=state.value.copy(reviewPrompt=false,reviewing=true,
            selectedLineUuid=if(state.value.original.placements.points.none {it.mounting.needsReview})state.value.original.placements.lines.firstOrNull {it.needsReview}?.uuid else null,
            selectedPointUuid=state.value.original.placements.points.firstOrNull { it.mounting.needsReview }?.uuid)
    }
    suspend fun reviewLater() {
        if (state.value.saving) return
        mutableState.value=state.value.copy(reviewPrompt=false,reviewing=false,lineEdits=state.value.original.placements.lines.filter {it.needsReview}.associate {it.uuid to LinePlacementEdit(it,defer=true)},pointEdits=state.value.original.placements.points
            .filter { it.mounting.needsReview }.associate { it.uuid to PointMountingEdit(defer=true) })
        save()
    }
    suspend fun confirmSelected() {
        val point=displayPoints().find { it.uuid==state.value.selectedPointUuid } ?: return
        if (point.mounting.unresolved) return
        stagePoint(point.uuid,PointMountingEdit(confirm=true))
        save(keepEditing=true)
    }
    fun delivered() { store?.release(token) }

    private fun restoreForm(value: CeilingFormHistory) {
        val draft=checkNotNull(MeasurementCreateDraft.restore(value.draft,state.value.draft.projectId))
        mutableState.value=state.value.copy(draft=draft,lineEdits=value.lines,declaredLengths=value.lengths,pointEdits=value.edits,wallGeneration=value.wallGeneration.ifEmpty { token },drag=null,mountingInput=null,lineStart=null,linePreview=null,lineDrag=null)
        publish()
    }
    fun markPointsChanged() {
        if(!state.value.saving && !state.value.saved) mutableState.value=state.value.copy(conflict=true,error="Позначки змінено іншим редактором. Чернетку збережено; перезавантажте актуальні дані.",drag=null)
    }
    fun draftWalls() = ceilingDraftWalls(state.value.original,state.value.draft,state.value.wallGeneration)
    fun canSaveMountingDraft():Boolean = state.value.lineProperties==null && state.value.linePreview==null && state.value.lineStart==null &&
        displayLines().none { it.unresolved && state.value.lineEdits[it.uuid]?.let {e ->
            val old=state.value.original.placements.lines.find {line ->line.uuid==it.uuid}
            !e.defer && e.value.deletedAt==null && (old==null || e.confirm || e.remeasuredStart || e.remeasuredEnd ||
                e.value.start!=old.start || e.value.end!=old.end || e.value.startMounting.anchor!=old.startMounting.anchor || e.value.endMounting.anchor!=old.endMounting.anchor)
        }==true } &&
        lineCapacityError()==null && displayPoints().none { point -> point.mounting.unresolved &&
        state.value.pointEdits[point.uuid]?.let {it.anchor!=null || it.freeX!=null}==true }
    fun displayPoints(): List<PointPlacement> {
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return state.value.original.placements.points
        val changed=geometry!=validateMeasurementCreate(state.value.original.draft).geometry
        return state.value.original.placements.points.map { previewMounting(it,geometry,draftWalls(),changed,state.value.pointEdits[it.uuid]) }
    }
    fun selectPoint(uuid: String) {
        if (state.value.saving || state.value.uncertain || state.value.checking) return
        if (displayPoints().any { it.uuid==uuid }) mutableState.value=state.value.copy(selectedPointUuid=uuid,selectedLineUuid=null,lineElementUuid=null,lineStart=null,linePreview=null)
    }
    fun beginPointDrag(uuid: String) {
        if (state.value.saving || state.value.uncertain || state.value.checking || validateMeasurementCreate(state.value.draft).geometry==null || state.value.selectedPointUuid!=uuid) return
        val point=displayPoints().find { it.uuid==uuid } ?: return
        mutableState.value=state.value.copy(drag=PointDragPreview(point,ElementVersion(point.elementUuid,state.value.draft.recordUuid,
            state.value.original.placements.elements.getValue(point.elementUuid)),state.value.original.updatedAt))
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
        if (state.value.saving || state.value.checking || state.value.uncertain || state.value.saved) return
        commitInput();undo.add(form());redo.clear()
        mutableState.value=state.value.copy(pointEdits=state.value.pointEdits+(uuid to edit),mountingInput=null,drag=null,error=null)
        publish()
        if(!canSaveMountingDraft()) mutableState.value=state.value.copy(error="Це лише попередня позиція. Задайте коректні виміряні відступи, щоб уточнити нерозв'язану позначку й зберегти всю чернетку.")
    }
    fun openMounting() {
        if (state.value.saving || state.value.checking || state.value.uncertain || validateMeasurementCreate(state.value.draft).geometry==null) return
        val point=displayPoints().find { it.uuid==state.value.selectedPointUuid } ?: return
        mutableState.value=state.value.copy(mountingInput=WallAnchorInput.from(point,draftWalls()),mountingLineStart=null)
    }
    fun updateMounting(change: (WallAnchorInput)->WallAnchorInput) {
        mutableState.value=state.value.copy(mountingInput=state.value.mountingInput?.let(change),error=null)
    }
    fun applyMounting() {
        val input=state.value.mountingInput ?: return
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        val anchor=input.anchor() ?: return
        if (solveWallAnchor(geometry,draftWalls(),anchor)==null) return
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
    fun discardMounting() { mutableState.value=state.value.copy(mountingInput=null,mountingLineStart=null,mountingDismiss=false) }
    fun continueMounting() { mutableState.value=state.value.copy(mountingDismiss=false) }

    private fun editable() = !state.value.saving && !state.value.checking && !state.value.saved && !state.value.uncertain
    fun displayLines(): List<LinePlacement> {
        val state=state.value
        val geometry=validateMeasurementCreate(state.draft).geometry ?: return state.original.placements.lines
        val changed=geometry!=validateMeasurementCreate(state.original.draft).geometry
        return (state.original.placements.lines.map {it.uuid}+state.lineEdits.keys).distinct().mapNotNull {id ->
            val edit=state.lineEdits[id]
            val line=state.original.placements.lines.find {it.uuid==id} ?: edit?.value ?: return@mapNotNull null
            if(edit?.value?.deletedAt!=null) null else previewLine(line,geometry,draftWalls(),changed,edit)
        }
    }
    fun lineCapacityError(): String? {
        val lines=displayLines()
        if(state.value.declaredLengths.any { (id,length) -> lines.filter {it.elementUuid==id && !it.unresolved}.sumOf {it.lengthMm}!=length })
            return "Довжина креслення змінилася. Повторіть явну дію «Взяти довжину з креслення» або скасуйте її через Undo."
        return state.value.original.lineElements.firstOrNull {element ->
            val capacity=state.value.declaredLengths[element.uuid] ?: element.lineCapacityMm()
            capacity!=null && lines.filter {it.elementUuid==element.uuid}.sumOf {it.lengthMm}>capacity
        }?.let {LinePlacementFailure.CapacityReached().message}
    }
    fun beginLine(element: String) {
        if(!editable() || state.value.original.lineElements.none {it.uuid==element})return
        mutableState.value=state.value.copy(lineElementUuid=element,lineStart=null,linePreview=null,selectedLineUuid=null,selectedPointUuid=null)
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
    fun cancelLineCreation() {mutableState.value=state.value.copy(lineStart=null,linePreview=null,lineElementUuid=null)}
    fun acceptLineCreation() {
        if(!editable())return
        val line=state.value.linePreview ?: return
        stageLine(line)
        mutableState.value=state.value.copy(lineStart=null,linePreview=null,lineElementUuid=null,selectedLineUuid=line.uuid)
    }
    fun selectLine(uuid:String) {
        if(!editable())return
        if(displayLines().any {it.uuid==uuid}) mutableState.value=state.value.copy(selectedLineUuid=uuid,selectedPointUuid=null,lineElementUuid=null,lineStart=null,linePreview=null)
    }
    fun stageLine(line:LinePlacement,confirm:Boolean=false,defer:Boolean=false,remeasuredStart:Boolean?=null,remeasuredEnd:Boolean?=null) {
        if(!editable())return
        commitInput();undo.add(form());redo.clear()
        mutableState.value=state.value.copy(lineEdits=state.value.lineEdits+(line.uuid to LinePlacementEdit(line,confirm,defer,remeasuredStart ?: state.value.lineEdits[line.uuid]?.remeasuredStart ?: false,remeasuredEnd ?: state.value.lineEdits[line.uuid]?.remeasuredEnd ?: false)),mountingInput=null,lineDrag=null,error=null)
        publish()
        lineCapacityError()?.let {mutableState.value=state.value.copy(error=it)}
    }
    fun setLineMoveTarget(target:Int) { if(editable())mutableState.value=state.value.copy(lineMoveTarget=target) }
    fun beginLineDrag(uuid:String) {if(editable() && state.value.selectedLineUuid==uuid)mutableState.value=state.value.copy(lineDrag=displayLines().find {it.uuid==uuid})}
    fun previewLineDrag(dx:Double,dy:Double) {
        val source=displayLines().find {it.uuid==state.value.lineDrag?.uuid} ?: return
        if(!dx.isFinite() || !dy.isFinite() || dx !in -2_000_000.0..2_000_000.0 || dy !in -2_000_000.0..2_000_000.0)return
        fun move(p:PointPositionMm)=PointPositionMm(p.xMm+dx.roundToLong(),p.yMm+dy.roundToLong())
        val target=state.value.lineMoveTarget
        mutableState.value=state.value.copy(lineDrag=source.copy(start=if(target!=2)move(source.start) else source.start,
            end=if(target!=1)move(source.end) else source.end,
            startMounting=if(target!=2)PointMounting() else source.startMounting,endMounting=if(target!=1)PointMounting() else source.endMounting,
            needsReview=source.needsReview || source.precision==PointPrecision.EXACT))
    }
    fun cancelLineDrag() {mutableState.value=state.value.copy(lineDrag=null)}
    fun finishLineDrag() {
        val line=state.value.lineDrag ?: return;cancelLineDrag()
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        if(!lineStrictlyInside(geometry,line.start,line.end)){mutableState.value=state.value.copy(error=LinePlacementFailure.InvalidSegment().message);return}
        stageLine(line,remeasuredStart=if(state.value.lineMoveTarget!=2)false else null,remeasuredEnd=if(state.value.lineMoveTarget!=1)false else null)
        mutableState.value=state.value.copy(error="Переміщений кінець має приблизне положення. Активні виміряні прив'язки цього кінця скинуто.")
    }
    fun openLineMounting(first:Boolean) {
        if(!editable())return
        val id=state.value.selectedLineUuid ?: return
        val line=state.value.lineEdits[id]?.value ?: displayLines().find {it.uuid==id} ?: return
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
        mutableState.value=state.value.copy(lineProperties=LinePropertiesInput(line.uuid,line.widthMm?.toString().orEmpty(),line.note))
    }
    fun changeLineProperties(width:String?=null,note:String?=null) {
        if(!editable())return
        mutableState.value=state.value.copy(lineProperties=state.value.lineProperties?.let {it.copy(width=width ?: it.width,note=note ?: it.note)})
    }
    fun dismissLineProperties() {
        if(state.value.lineProperties?.dirty==true)mutableState.value=state.value.copy(linePropertiesDismiss=true) else discardLineProperties()
    }
    fun discardLineProperties() {mutableState.value=state.value.copy(lineProperties=null,linePropertiesDismiss=false)}
    fun continueLineProperties() {mutableState.value=state.value.copy(linePropertiesDismiss=false)}
    fun applyLineProperties() {
        if(!editable())return
        val input=state.value.lineProperties?.takeIf {it.valid} ?: return
        val line=state.value.lineEdits[input.uuid]?.value ?: displayLines().find {it.uuid==input.uuid} ?: return
        stageLine(line.copy(widthMm=input.width.toLongOrNull(),note=input.note));discardLineProperties()
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

    /** Local saveable data, never commands or route payloads. History is bounded. */
    fun snapshot(): String = buildJsonObject {
        put("id", state.value.original.measurementId); put("version", state.value.original.updatedAt); put("token", token)
        put("original", state.value.original.draft.snapshot()); put("draft", state.value.draft.snapshot())
        put("wallGeneration",state.value.wallGeneration)
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
        attempt?.let { put("receipt",it.operation) }
        put("reviewPrompt",state.value.reviewPrompt);put("reviewing",state.value.reviewing)
        put("writeStarted", state.value.saving || state.value.saved || reconcileAfterRestore)
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
                json["sourceDrawingJson"]?.jsonPrimitive?.content), token).also {
                it.mutableState.value = it.state.value.copy(draft = draft,
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
                it.reconcileReceipt=json["receipt"]?.jsonPrimitive?.content
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
    suspend fun reconcile() {
        if (!needsReadAfterRestore || state.value.saving || state.value.saved || store == null) return
        needsReadAfterRestore = false
        val recoveringWrite = reconcileAfterRestore
        reconcileAfterRestore = false
        try {
            val before = state.value
            val loaded = withContext(Dispatchers.Default) { checkNotNull(store).load(before.draft.projectId, before.original.measurementId) }
            val receipt=reconcileReceipt
            if(recoveringWrite && receipt!=null) {
                val committed=withContext(Dispatchers.Default) { store.operationCommitted(receipt,loaded.draft.recordUuid,loaded.updatedAt) }
                if(committed) {
                    val changed=validateMeasurementCreate(loaded.draft).geometry!=validateMeasurementCreate(before.original.draft).geometry
                    val review=changed && (loaded.placements.points.any {it.mounting.needsReview} || loaded.placements.lines.any {it.needsReview})
                    mutableState.value=MeasurementEditState(loaded,saved=!review,reviewPrompt=review)
                } else mutableState.value=before.copy(checking=false,conflict=true,error="Спробу не підтверджено. Чернетку збережено; перезавантажте актуальний запис перед новим Save.")
                return
            }
            val actual = validateMeasurementCreate(loaded.draft).geometry
            val intended = validateMeasurementCreate(before.draft).geometry
            if (recoveringWrite && before.lineEdits.isEmpty() && before.declaredLengths.isEmpty() && (loaded.updatedAt > before.original.updatedAt || loaded.updatedAt == before.original.updatedAt && !before.dirty) && actual == intended &&
                loaded.draft.name == before.draft.name.trim() && loaded.draft.notes == before.draft.notes.trim() &&
                loaded.placements.points.map { it.copy(updatedAt=0,lastCeilingEditUuid=null,mounting=it.mounting.copy(modifiedVersion=0,confirmedAt=null,deferredAt=null,anchorGeometryJson=null,lastValidGeometryJson=null)) } ==
                displayPoints().map { it.copy(updatedAt=0,lastCeilingEditUuid=null,mounting=it.mounting.copy(modifiedVersion=0,confirmedAt=null,deferredAt=null,anchorGeometryJson=null,lastValidGeometryJson=null)) }) {
                mutableState.value = MeasurementEditState(loaded, saved = true)
            } else if (loaded.updatedAt != before.original.updatedAt || loaded.draft.recordUuid != before.original.draft.recordUuid || loaded.placements!=before.original.placements) {
                mutableState.value = before.copy(checking = false, conflict = true, error = MeasurementEditConflict().message)
            } else mutableState.value = before.copy(original=loaded,checking = false)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, conflict = true, error = "Перевірте результат збереження: запис зараз недоступний. Перезавантажте його перед новим записом.")
        }
    }
}
