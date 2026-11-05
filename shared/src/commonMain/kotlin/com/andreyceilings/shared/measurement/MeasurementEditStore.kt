package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.cash.sqldelight.coroutines.asFlow

class MeasurementEditConflict : IllegalStateException(
    "Замір змінено іншим редактором, видалено або об'єкт недоступний. Перезавантажте запис; чернетку не записано.")

/** Issued only after SQLDelight's outer physical rollback has completed successfully. */
class MeasurementEditRejected(cause: Throwable) : IllegalStateException(
    "Зміни не записано. Перевірте контур, стінні опори й відступи та повторіть збереження. Чернетку залишено для редагування.", cause)

/** A commit/rollback/notification failure is not evidence that the write was rejected. */
internal class MeasurementEditUncertain(cause: Throwable) : IllegalStateException("Результат запису не підтверджено.", cause)

/** A version and its exact source inputs travel together, never from current global settings. */
@kotlinx.serialization.Serializable
data class CeilingPlacementDocument(val points: List<PointPlacement> = emptyList(),
    val elements: Map<String, Long> = emptyMap(), val walls: List<CeilingWall> = emptyList(), val quantities: Map<String,Long> = emptyMap(), val lines: List<LinePlacement> = emptyList())
data class MeasurementEditDocument(val measurementId: Long, val updatedAt: Long, val draft: MeasurementCreateDraft,
    val placements: CeilingPlacementDocument = CeilingPlacementDocument(), val sourceDrawingJson: String? = null, val lineElements: List<MeasurementElement> = emptyList())

class MeasurementEditStore(private val database: PrototypeDatabase, private val nowMillis: () -> Long) {
    private val sessionClosed = MutableStateFlow(false)
    private val pointRead = Mutex()
    fun closePointReads() { sessionClosed.value = true; writes.update { it.copy(closed=true) }; pending.value=emptyMap() }
    /** JDBC close waits for the mapper; Android's production restore gate already drains reads. */
    suspend fun closeAndAwaitPointReads() {
        closePointReads()
        writes.value.pending.forEach { it.await() }
        pointRead.withLock { /* Any accepted SQL mapper has now finished. */ }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePoints(document: MeasurementEditDocument): Flow<PointOverlayState> = sessionClosed.flatMapLatest { closed ->
        if (closed) flowOf(PointOverlayState.Unavailable) else database.pointPlacementsQueries.selectEditorOverlay(
            document.measurementId, document.draft.projectId, document.draft.recordUuid).asFlow()
            .map { query -> kotlinx.coroutines.withContext(Dispatchers.Default) {
                pointRead.withLock { if (sessionClosed.value) null else query.executeAsList() }
            } }.map { rows ->
                if (rows == null || sessionClosed.value) return@map PointOverlayState.Unavailable
                val first = rows.firstOrNull()
                if (first == null) PointOverlayState.Missing else {
                    val geometry = (measurementGeometryForViewing(first.shape, first.drawingJson,
                        first.lengthMeters, first.widthMeters) as? MeasurementGeometryView.Captured)?.geometry
                    if (geometry == null) PointOverlayState.Unavailable else PointOverlayState.Loaded(rows.mapNotNull { row ->
                        row.uuid?.let { uuid -> PointPlacement(uuid, checkNotNull(row.elementUuid), checkNotNull(row.xMm), checkNotNull(row.yMm),
                            checkNotNull(row.contourUpdatedAt), checkNotNull(row.createdAt), checkNotNull(row.updatedAt), row.deletedAt,
                            PointPrecision.entries.single { it.value == row.precision }, row.lastMoveUuid, decodeMounting(checkNotNull(row.mountingJson)), row.lastCeilingEditUuid) }
                    }, geometry)
                }
            }.onStart<PointOverlayState> { emit(PointOverlayState.Loading) }
                .catch { if (it is CancellationException) throw it else emit(PointOverlayState.Unavailable) }
    }
    private val measurements = MeasurementRepository(database)
    private val projects = ProjectRepository(database)
    // Only an in-flight/delivered write is retained, scoped to this unlocked database session.
    // Android Activity recreation can reattach to the same operation rather than replaying Save.
    private val pending = MutableStateFlow<Map<String, MeasurementEditModel>>(emptyMap())
    internal fun retain(token: String, model: MeasurementEditModel) { pending.update { it + (token to model) } }
    internal fun release(token: String) { pending.update { it - token } }
    internal fun retained(token: String): MeasurementEditModel? = pending.value[token]

    fun load(projectId: Long, measurementId: Long): MeasurementEditDocument = loadDocument(projectId,measurementId)
    private fun loadDocument(projectId: Long, measurementId: Long, allowClosing: Boolean = false): MeasurementEditDocument = database.transactionWithResult {
        if ((!allowClosing && sessionClosed.value) || projects.find(projectId) == null) throw MeasurementEditConflict()
        val row = measurements.find(measurementId)?.takeIf { it.projectId == projectId } ?: throw MeasurementEditConflict()
        val geometry = MeasurementGeometryCodec.read(row.drawingJson)
        require(geometry.shapeType == row.shape)
        fun text(mm: Long) = formatMillimetres(mm).removeSuffix(" м")
        val outline = if(geometry.shapeType=="polygon" && geometry.sides.isEmpty()) MeasurementOutlineDraft.Captured(geometry)
        else if (geometry.shapeType == "polygon") {
            MeasurementOutlineDraft.Orthogonal(geometry.sides.mapIndexed { i, side ->
                MeasurementSideInput(text(side.lengthMm), side.turnAfter, "saved-side-$i")
            })
        } else MeasurementOutlineDraft.Template(geometry.shapeType, geometry.measurementsMm.mapValues { text(it.value) })
        val draft = MeasurementCreateDraft(projectId, requireNotNull(MeasurementPrecision.fromStep(geometry.precisionStepMeters)),
            row.name, row.notes, outline, row.uuid)
        require(validateMeasurementCreate(draft).geometry == geometry) { "Неповні вимірювання. Редагування недоступне." }
        val points = PointPlacementRepository(database).forMeasurement(row.uuid)
        val lines = LinePlacementRepository(database).forMeasurement(row.uuid)
        val lineElements = MeasurementElementRepository(database).active().filter { it.measurementUuid==row.uuid && it.supportsLinePlacement() }
        val elements = (points.map { it.elementUuid } + lines.map { it.elementUuid } + lineElements.map { it.uuid }).distinct().associateWith { MeasurementElementRepository(database).find(it)!!.updatedAt }
        val walls = database.ceilingEditsQueries.selectWalls(row.uuid).executeAsList().map { CeilingWall(it.uuid,it.ordinal.toInt()) }
        require(walls.size == geometry.vertices.size) { "Неповні ідентичності стін." }
        MeasurementEditDocument(row.id, row.updatedAt, draft, CeilingPlacementDocument(points,elements,walls,points.map {it.elementUuid}.distinct().associateWith { MeasurementElementRepository(database).find(it)!!.pointCapacity()!! },lines),row.drawingJson,lineElements)
    }

    fun update(original: MeasurementEditDocument, draft: MeasurementCreateDraft,
        edits: Map<String, PointMountingEdit> = emptyMap(), operationUuid: String = newRecordUuid(),
        wallToken: String = operationUuid, now: Long = nowMillis(), acceptedWrite: kotlinx.coroutines.CompletableDeferred<Unit>? = null, lineEdits: Map<String,LinePlacementEdit> = emptyMap(), declaredLengths: Map<String,Long> = emptyMap()): MeasurementEditDocument {
        check(!sessionClosed.value || acceptedWrite in writes.value.pending) { "Сесію закрито." }
        var rolledBack = false
        try {
            return database.transactionWithResult {
                // Runs after endTransaction succeeds, only for the outer rollback. Do not infer
                // this from a platform exception class/message or from absence of a receipt.
                afterRollback { rolledBack = true }
                CeilingEditRepository(database).save(original,draft,edits,operationUuid,wallToken,now,lineEdits,declaredLengths)
                loadDocument(draft.projectId,original.measurementId,allowClosing = true)
            }
        } catch (failure: Throwable) {
            if (!rolledBack) throw MeasurementEditUncertain(failure)
            when (failure) {
                is MeasurementEditConflict, is PointPlacementFailure.RetryConflict, is IllegalArgumentException -> throw failure
                else -> throw MeasurementEditRejected(failure)
            }
        }
    }
    fun now(): Long = nowMillis()
    fun operationCommitted(uuid:String,measurement:String,version:Long):Boolean {
        if(sessionClosed.value)return false
        val op=database.ceilingEditsQueries.selectOperation(uuid).executeAsOneOrNull() ?: return false
        return op.measurementUuid==measurement && maxOf(op.expectedUpdatedAt+1,op.createdAt)==version && CeilingEditRepository(database).isCurrentResult(uuid,measurement,version)
    }

    private data class Writes(val closed: Boolean = false, val pending: Set<kotlinx.coroutines.CompletableDeferred<Unit>> = emptySet())
    private val writes = MutableStateFlow(Writes())
    internal fun beginWrite(): kotlinx.coroutines.CompletableDeferred<Unit>? {
        val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        return completion.takeIf { it in writes.updateAndGet { state -> if (state.closed) state else state.copy(pending=state.pending+completion) }.pending }
    }
    internal fun endWrite(completion: kotlinx.coroutines.CompletableDeferred<Unit>) {
        completion.complete(Unit); writes.update { it.copy(pending=it.pending-completion) }
    }
}
