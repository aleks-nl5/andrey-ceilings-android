package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

sealed interface MeasurementCreateParent {
    data object Loading : MeasurementCreateParent
    data object Missing : MeasurementCreateParent
    data object Unavailable : MeasurementCreateParent
    data class Active(val title: String) : MeasurementCreateParent
}

class MeasurementCreateParentMissing : IllegalStateException("Об'єкт не знайдено або його видалено. Замір не збережено.")
class MeasurementCreateAlreadySaved : IllegalStateException("Цю чернетку вже збережено з іншими даними. Поверніться до об'єкта й відкрийте збережений замір.")

/** Only this boundary writes. Parent check and returning-ID insert share one transaction. */
class MeasurementCreateStore(private val database: PrototypeDatabase, private val nowMillis: () -> Long) {
    private val projects = ProjectRepository(database)
    private val measurements = MeasurementRepository(database)

    // Activity recreation must reattach the actual save, including its exit guard.
    // Keep only pending results, within the unlocked session, just like the B1 edit store.
    private val pending = MutableStateFlow<Map<String, MeasurementCreateModel>>(emptyMap())
    internal fun retain(uuid: String, model: MeasurementCreateModel) { pending.update { it + (uuid to model) } }
    internal fun release(uuid: String) { pending.update { it - uuid } }
    internal fun retained(uuid: String): MeasurementCreateModel? = pending.value[uuid]

    fun observeParent(projectId: Long): Flow<MeasurementCreateParent> = projects.observeById(projectId)
        .map { row -> if (row == null) MeasurementCreateParent.Missing
            else MeasurementCreateParent.Active(row.address.ifBlank { row.title }) }
        .onStart<MeasurementCreateParent> { emit(MeasurementCreateParent.Loading) }
        .catch { emit(MeasurementCreateParent.Unavailable) }
        .flowOn(Dispatchers.Default)

    fun create(draft: MeasurementCreateDraft): Long {
        val validation = validateMeasurementCreate(draft)
        require(validation.canSave) { validation.nameError ?: validation.fieldErrors.values.firstOrNull() ?: validation.geometryError ?: "Некоректна форма." }
        val geometry = requireNotNull(validation.geometry)
        val drawing = MeasurementGeometryCodec.write(geometry)
        return database.transactionWithResult {
            if (projects.find(draft.projectId) == null) throw MeasurementCreateParentMissing()
            // A configuration/process restore can race a committed write. Reuse this
            // draft's UUID, never create a second row or overwrite the first one.
            measurements.findByUuid(draft.recordUuid)?.let { existing ->
                if (existing.deletedAt != null || existing.projectId != draft.projectId ||
                    existing.name != draft.name.trim() || existing.notes != draft.notes.trim() ||
                    existing.drawingJson != drawing) throw MeasurementCreateAlreadySaved()
                return@transactionWithResult existing.id
            }
            val lengthMm = geometry.vertices.maxOf { it.xMm } - geometry.vertices.minOf { it.xMm }
            val widthMm = geometry.vertices.maxOf { it.yMm } - geometry.vertices.minOf { it.yMm }
            measurements.createReturningId(draft.projectId, draft.name.trim(), geometry.shapeType,
                lengthMm / 1000.0, widthMm / 1000.0, geometry.areaSquareMeters, geometry.perimeterMeters,
                drawing, draft.notes.trim(), nowMillis(), recordUuid = draft.recordUuid)
        }
    }
}

data class MeasurementCreateState(
    val draft: MeasurementCreateDraft, val saving: Boolean = false,
    val savedId: Long? = null, val cancelled: Boolean = false, val error: String? = null
)

/** One editor lifetime, shared by all hosts; repeated Save/Cancel cannot replay a write. */
class MeasurementCreateModel(private val store: MeasurementCreateStore?, draft: MeasurementCreateDraft) {
    private val mutableState = MutableStateFlow(MeasurementCreateState(draft))
    val state: StateFlow<MeasurementCreateState> = mutableState.asStateFlow()
    private val saveMutex = Mutex()

    fun accept(intent: MeasurementCreateIntent) {
        mutableState.update { current ->
            if (current.saving || current.savedId != null || current.cancelled) current
            else current.copy(draft = current.draft.reduce(intent), error = null)
        }
    }

    fun cancel(): Boolean {
        val current = mutableState.value
        if (current.saving || current.savedId != null || current.cancelled) return false
        mutableState.value = current.copy(cancelled = true)
        return true
    }

    suspend fun save() {
        if (!saveMutex.tryLock()) return
        try {
            val current = mutableState.value
            if (current.savedId != null || current.cancelled) return
            if (!validateMeasurementCreate(current.draft).canSave) return
            store?.retain(current.draft.recordUuid, this)
            mutableState.value = current.copy(saving = true, error = null)
            // Hosts keep the editor visible through the shared navigation guard.
            // A started atomic transaction is not cancelled/retried by navigation.
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    val id = checkNotNull(store).create(current.draft)
                    mutableState.value = current.copy(savedId = id, error = null)
                } catch (missing: MeasurementCreateParentMissing) {
                    mutableState.value = current.copy(error = missing.message)
                } catch (existing: MeasurementCreateAlreadySaved) {
                    mutableState.value = current.copy(error = existing.message)
                } catch (_: Exception) {
                    // Native exceptions can contain paths/SQL/private values; do not expose them.
                    mutableState.value = current.copy(error = "Не вдалося записати замір у захищену базу. " +
                        "Перевірте доступність БД та вільне місце й повторіть збереження.")
                }
            }
        } finally {
            if (state.value.savedId == null) store?.release(state.value.draft.recordUuid)
            saveMutex.unlock()
        }
    }

    fun delivered() { store?.release(state.value.draft.recordUuid) }

    companion object {
        fun restore(store: MeasurementCreateStore?, snapshot: String, projectId: Long): MeasurementCreateModel? {
            val draft = MeasurementCreateDraft.restore(snapshot, projectId) ?: return null
            return store?.retained(draft.recordUuid)?.takeIf { it.state.value.draft.projectId == projectId }
                ?: MeasurementCreateModel(store, draft)
        }
    }
}
