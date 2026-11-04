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
    val pointEdits: Map<String,PointMountingEdit> = emptyMap(), val selectedPointUuid: String? = null,
    val drag: PointDragPreview? = null, val mountingInput: WallAnchorInput? = null,
    val mountingDismiss: Boolean = false, val uncertain: Boolean = false,
    val reviewPrompt: Boolean = false, val reviewing: Boolean = false, val wallGeneration: String = newRecordUuid()
) {
    val dirty: Boolean get() = if (pointEdits.isNotEmpty() || mountingInput?.dirty==true) true else if (draft == original.draft) false else {
        val current = validateMeasurementCreate(draft)
        val baseline = validateMeasurementCreate(original.draft)
        draft.name.trim() != original.draft.name.trim() || draft.notes.trim() != original.draft.notes.trim() ||
            !current.canSave || current.geometry != baseline.geometry
    }
}

@Serializable
private data class CeilingFormHistory(val draft: String, val edits: Map<String,PointMountingEdit>, val wallGeneration:String="")
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
    private fun form() = CeilingFormHistory(state.value.draft.snapshot(),state.value.pointEdits,state.value.wallGeneration)
    private var exitAction: (() -> Unit)? = null
    fun observePoints(): Flow<PointOverlayState> = store?.observePoints(state.value.original)
        ?: flowOf(PointOverlayState.Unavailable)

    private fun publish(draft: MeasurementCreateDraft = state.value.draft) {
        mutableState.value = state.value.copy(draft = draft, canUndo = inputStart?.let { it != CeilingFormHistory(draft.snapshot(),state.value.pointEdits,state.value.wallGeneration) } == true || undo.isNotEmpty(),
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
        cancelPointDrag(); commitInput()
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
            if (state.value.saving || state.value.checking || state.value.saved || state.value.conflict || state.value.mountingInput!=null) return
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
                    val result=checkNotNull(store).update(before.original,before.draft,before.pointEdits,command.operation,before.wallGeneration,timestamp,completion)
                    val geometryChanged=validateMeasurementCreate(before.original.draft).geometry!=validateMeasurementCreate(result.draft).geometry
                    undo.clear();redo.clear();attempt=null
                    mutableState.value=MeasurementEditState(result,
                        saved=!keepEditing && !(geometryChanged && result.placements.points.any { it.mounting.needsReview }),
                        reviewPrompt=geometryChanged && result.placements.points.any { it.mounting.needsReview },
                        reviewing=keepEditing || before.reviewing,
                        selectedPointUuid=if (keepEditing) result.placements.points.firstOrNull { it.mounting.needsReview }?.uuid else null)
                } catch (conflict: MeasurementEditConflict) {
                    attempt=null;mutableState.value=before.copy(error=conflict.message,conflict=true);store?.release(token)
                } catch (failure: PointPlacementFailure.RetryConflict) {
                    attempt=null;mutableState.value=before.copy(error=pointFailureMessage(failure),conflict=true);store?.release(token)
                } catch (_: IllegalArgumentException) {
                    attempt=null;mutableState.value=before.copy(error="Перевірте контур, стінні опори й відступи. Чернетку збережено; недопустимі дані не записано.");store?.release(token)
                } catch (_: Exception) {
                    mutableState.value=before.copy(uncertain=true,error="Результат збереження не підтверджено. Повторіть ту саму спробу або перезавантажте запис; чернетку збережено.")
                } finally { store?.endWrite(completion) }
            }
        } finally { mutex.unlock() }
    }
    fun reviewNow() {
        mutableState.value=state.value.copy(reviewPrompt=false,reviewing=true,
            selectedPointUuid=state.value.original.placements.points.firstOrNull { it.mounting.needsReview }?.uuid)
    }
    suspend fun reviewLater() {
        if (state.value.saving) return
        mutableState.value=state.value.copy(reviewPrompt=false,reviewing=false,pointEdits=state.value.original.placements.points
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
        mutableState.value=state.value.copy(draft=draft,pointEdits=value.edits,wallGeneration=value.wallGeneration.ifEmpty { token },drag=null,mountingInput=null)
        publish()
    }
    fun markPointsChanged() {
        if(!state.value.saving && !state.value.saved) mutableState.value=state.value.copy(conflict=true,error="Позначки змінено іншим редактором. Чернетку збережено; перезавантажте актуальні дані.",drag=null)
    }
    fun draftWalls() = ceilingDraftWalls(state.value.original,state.value.draft,state.value.wallGeneration)
    fun canSaveMountingDraft():Boolean = displayPoints().none { point -> point.mounting.unresolved &&
        state.value.pointEdits[point.uuid]?.let {it.anchor!=null || it.freeX!=null}==true }
    fun displayPoints(): List<PointPlacement> {
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return state.value.original.placements.points
        val changed=geometry!=validateMeasurementCreate(state.value.original.draft).geometry
        return state.value.original.placements.points.map { previewMounting(it,geometry,draftWalls(),changed,state.value.pointEdits[it.uuid]) }
    }
    fun selectPoint(uuid: String) {
        if (state.value.saving || state.value.uncertain || state.value.checking) return
        if (displayPoints().any { it.uuid==uuid }) mutableState.value=state.value.copy(selectedPointUuid=uuid)
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
        mutableState.value=state.value.copy(mountingInput=WallAnchorInput.from(point,draftWalls()))
    }
    fun updateMounting(change: (WallAnchorInput)->WallAnchorInput) {
        mutableState.value=state.value.copy(mountingInput=state.value.mountingInput?.let(change),error=null)
    }
    fun applyMounting() {
        val input=state.value.mountingInput ?: return
        val geometry=validateMeasurementCreate(state.value.draft).geometry ?: return
        val anchor=input.anchor() ?: return
        if (solveWallAnchor(geometry,draftWalls(),anchor)==null) return
        stagePoint(input.pointUuid,PointMountingEdit(anchor=anchor))
    }
    fun dismissMounting() {
        if(state.value.mountingInput?.dirty==true) mutableState.value=state.value.copy(mountingDismiss=true)
        else discardMounting()
    }
    fun discardMounting() { mutableState.value=state.value.copy(mountingInput=null,mountingDismiss=false) }
    fun continueMounting() { mutableState.value=state.value.copy(mountingDismiss=false) }

    /** Local saveable data, never commands or route payloads. History is bounded. */
    fun snapshot(): String = buildJsonObject {
        put("id", state.value.original.measurementId); put("version", state.value.original.updatedAt); put("token", token)
        put("original", state.value.original.draft.snapshot()); put("draft", state.value.draft.snapshot())
        put("wallGeneration",state.value.wallGeneration)
        put("placements",mountingCodec.encodeToString(state.value.original.placements))
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
                    val review=changed && loaded.placements.points.any {it.mounting.needsReview}
                    mutableState.value=MeasurementEditState(loaded,saved=!review,reviewPrompt=review)
                } else mutableState.value=before.copy(checking=false,conflict=true,error="Спробу не підтверджено. Чернетку збережено; перезавантажте актуальний запис перед новим Save.")
                return
            }
            val actual = validateMeasurementCreate(loaded.draft).geometry
            val intended = validateMeasurementCreate(before.draft).geometry
            if (recoveringWrite && (loaded.updatedAt > before.original.updatedAt || loaded.updatedAt == before.original.updatedAt && !before.dirty) && actual == intended &&
                loaded.draft.name == before.draft.name.trim() && loaded.draft.notes == before.draft.notes.trim() &&
                loaded.placements.points.map { it.copy(updatedAt=0,lastCeilingEditUuid=null,mounting=it.mounting.copy(modifiedVersion=0,confirmedAt=null,deferredAt=null,anchorGeometryJson=null,lastValidGeometryJson=null)) } ==
                displayPoints().map { it.copy(updatedAt=0,lastCeilingEditUuid=null,mounting=it.mounting.copy(modifiedVersion=0,confirmedAt=null,deferredAt=null,anchorGeometryJson=null,lastValidGeometryJson=null)) }) {
                mutableState.value = MeasurementEditState(loaded, saved = true)
            } else if (loaded.updatedAt != before.original.updatedAt || loaded.draft.recordUuid != before.original.draft.recordUuid || loaded.placements!=before.original.placements) {
                mutableState.value = before.copy(checking = false, conflict = true, error = MeasurementEditConflict().message)
            } else mutableState.value = before.copy(checking = false)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, conflict = true, error = "Перевірте результат збереження: запис зараз недоступний. Перезавантажте його перед новим записом.")
        }
    }
}
