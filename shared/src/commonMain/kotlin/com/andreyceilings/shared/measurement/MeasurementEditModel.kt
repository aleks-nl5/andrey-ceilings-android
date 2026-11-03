package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.newRecordUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

data class MeasurementEditState(
    val original: MeasurementEditDocument, val draft: MeasurementCreateDraft = original.draft,
    val saving: Boolean = false, val checking: Boolean = false, val saved: Boolean = false, val error: String? = null,
    val conflict: Boolean = false, val exitRequested: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false
) {
    val dirty: Boolean get() = if (draft == original.draft) false else {
        val current = validateMeasurementCreate(draft)
        val baseline = validateMeasurementCreate(original.draft)
        draft.name.trim() != original.draft.name.trim() || draft.notes.trim() != original.draft.notes.trim() ||
            !current.canSave || current.geometry != baseline.geometry
    }
}

/** Input transactions are grouped by focus/Done. Shape, turn and topology changes are discrete. */
class MeasurementEditModel(
    private val store: MeasurementEditStore?, original: MeasurementEditDocument,
    val token: String = newRecordUuid()
) {
    private val mutableState = MutableStateFlow(MeasurementEditState(original))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val undo = mutableListOf<MeasurementCreateDraft>()
    private val redo = mutableListOf<MeasurementCreateDraft>()
    private var inputStart: MeasurementCreateDraft? = null
    private var exitAction: (() -> Unit)? = null

    private fun publish(draft: MeasurementCreateDraft = state.value.draft) {
        mutableState.value = state.value.copy(draft = draft, canUndo = inputStart?.let { it != draft } == true || undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(), error = null)
    }
    fun beginInput() { if (!state.value.saving && !state.value.checking && !state.value.saved && inputStart == null) inputStart = state.value.draft }
    fun commitInput() {
        val start = inputStart ?: return
        inputStart = null
        if (start != state.value.draft) { undo.add(start); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        publish()
    }
    fun accept(intent: MeasurementCreateIntent, grouped: Boolean = false) {
        if (state.value.saving || state.value.checking || state.value.saved) return
        if (grouped) beginInput() else commitInput()
        val before = state.value.draft
        val after = before.reduce(intent)
        if (before == after) return
        if (!grouped) { undo.add(before); if (undo.size > 100) undo.removeAt(0); redo.clear() }
        publish(after)
    }
    fun undo() {
        if (state.value.saving || state.value.checking || state.value.saved) return
        commitInput()
        if (undo.isEmpty()) return
        redo.add(state.value.draft); publish(undo.removeAt(undo.lastIndex))
    }
    fun redo() {
        if (state.value.saving || state.value.checking || state.value.saved) return
        commitInput()
        if (redo.isEmpty()) return
        undo.add(state.value.draft); publish(redo.removeAt(redo.lastIndex))
    }
    fun requestExit(action: () -> Unit) {
        if (state.value.saving || state.value.checking || state.value.saved) return
        commitInput()
        if (!state.value.dirty) action() else {
            exitAction = action
            mutableState.value = state.value.copy(exitRequested = true)
        }
    }
    fun continueEditing() { exitAction = null; mutableState.value = state.value.copy(exitRequested = false) }
    fun discardAndExit() {
        if (state.value.saving || state.value.checking || state.value.saved) return
        val action = exitAction
        exitAction = null; undo.clear(); redo.clear(); inputStart = null
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
            undo.clear(); redo.clear(); inputStart = null
            mutableState.value = MeasurementEditState(loaded)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, error = "Запис недоступний. Чернетку залишено на екрані.")
        }
    }
    suspend fun save() {
        if (!mutex.tryLock()) return
        try {
            if (state.value.saving || state.value.checking || state.value.saved || state.value.conflict) return
            commitInput()
            val before = state.value
            if (!validateMeasurementCreate(before.draft).canSave) return
            if (!before.dirty) {
                undo.clear(); redo.clear()
                mutableState.value = MeasurementEditState(before.original, saved = true)
                return
            }
            continueEditing()
            mutableState.value = before.copy(saving = true, exitRequested = false, error = null)
            store?.retain(token, this)
            withContext(NonCancellable) {
                try {
                    val result = withContext(Dispatchers.Default) { checkNotNull(store).update(before.original, before.draft) }
                    undo.clear(); redo.clear()
                    mutableState.value = MeasurementEditState(result, saved = true)
                } catch (conflict: MeasurementEditConflict) {
                    mutableState.value = before.copy(error = conflict.message, conflict = true)
                    store?.release(token)
                } catch (_: Exception) {
                    mutableState.value = before.copy(error = "Не вдалося зберегти замір у захищеній базі. Перевірте доступність БД та вільне місце.")
                    store?.release(token)
                }
            }
        } finally { mutex.unlock() }
    }
    fun delivered() { store?.release(token) }

    /** Local saveable data, never commands or route payloads. History is bounded. */
    fun snapshot(): String = buildJsonObject {
        put("id", state.value.original.measurementId); put("version", state.value.original.updatedAt); put("token", token)
        put("original", state.value.original.draft.snapshot()); put("draft", state.value.draft.snapshot())
        put("writeStarted", state.value.saving || state.value.saved || reconcileAfterRestore)
        // Leave room for the draft and the rest of Android's saved instance state.
        fun bounded(history: List<MeasurementCreateDraft>): List<String> {
            var remaining = 64_000
            return history.takeLast(100).asReversed().map { it.snapshot() }.takeWhile {
                remaining -= it.length
                remaining >= 0
            }.asReversed()
        }
        putJsonArray("undo") { bounded(undo + listOfNotNull(inputStart?.takeIf { it != state.value.draft })).forEach { add(it) } }
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
            MeasurementEditModel(store, MeasurementEditDocument(measurementId, json.getValue("version").jsonPrimitive.long, original), token).also {
                it.mutableState.value = it.state.value.copy(draft = draft)
                for ((key, history) in listOf("undo" to it.undo, "redo" to it.redo)) {
                    json.getValue(key).jsonArray.takeLast(100).forEach { value ->
                        val entry = requireNotNull(MeasurementCreateDraft.restore(value.jsonPrimitive.content, projectId))
                        require(entry.precision == original.precision && entry.recordUuid == original.recordUuid)
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
            val actual = validateMeasurementCreate(loaded.draft).geometry
            val intended = validateMeasurementCreate(before.draft).geometry
            if (recoveringWrite && (loaded.updatedAt > before.original.updatedAt || loaded.updatedAt == before.original.updatedAt && !before.dirty) && actual == intended &&
                loaded.draft.name == before.draft.name.trim() && loaded.draft.notes == before.draft.notes.trim()) {
                mutableState.value = MeasurementEditState(loaded, saved = true)
            } else if (loaded.updatedAt != before.original.updatedAt || loaded.draft.recordUuid != before.original.draft.recordUuid) {
                mutableState.value = before.copy(checking = false, conflict = true, error = MeasurementEditConflict().message)
            } else mutableState.value = before.copy(checking = false)
        } catch (_: Exception) {
            mutableState.value = state.value.copy(checking = false, conflict = true, error = "Перевірте результат збереження: запис зараз недоступний. Перезавантажте його перед новим записом.")
        }
    }
}
