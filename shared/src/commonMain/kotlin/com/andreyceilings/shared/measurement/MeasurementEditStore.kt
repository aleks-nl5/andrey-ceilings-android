package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList

class MeasurementEditConflict : IllegalStateException(
    "Замір змінено іншим редактором, видалено або об'єкт недоступний. Перезавантажте запис; чернетку не записано.")

/** A version and its exact source inputs travel together, never from current global settings. */
data class MeasurementEditDocument(val measurementId: Long, val updatedAt: Long, val draft: MeasurementCreateDraft)

class MeasurementEditStore(private val database: PrototypeDatabase, private val nowMillis: () -> Long) {
    private val sessionClosed = MutableStateFlow(false)
    fun closePointReads() { sessionClosed.value = true }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePoints(document: MeasurementEditDocument): Flow<PointOverlayState> = sessionClosed.flatMapLatest { closed ->
        if (closed) flowOf(PointOverlayState.Unavailable) else database.pointPlacementsQueries.selectEditorOverlay(
            document.measurementId, document.draft.projectId, document.draft.recordUuid).asFlow()
            .mapToList(Dispatchers.Default).map { rows ->
                val first = rows.firstOrNull()
                if (first == null) PointOverlayState.Missing else {
                    val geometry = (measurementGeometryForViewing(first.shape, first.drawingJson,
                        first.lengthMeters, first.widthMeters) as? MeasurementGeometryView.Captured)?.geometry
                    if (geometry == null) PointOverlayState.Unavailable else PointOverlayState.Loaded(rows.mapNotNull { row ->
                        row.uuid?.let { uuid -> PointPlacement(uuid, checkNotNull(row.elementUuid), checkNotNull(row.xMm), checkNotNull(row.yMm),
                            checkNotNull(row.contourUpdatedAt), checkNotNull(row.createdAt), checkNotNull(row.updatedAt), row.deletedAt,
                            PointPrecision.entries.single { it.value == row.precision }, row.lastMoveUuid) }
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

    fun load(projectId: Long, measurementId: Long): MeasurementEditDocument = database.transactionWithResult {
        if (projects.find(projectId) == null) throw MeasurementEditConflict()
        val row = measurements.find(measurementId)?.takeIf { it.projectId == projectId } ?: throw MeasurementEditConflict()
        val geometry = MeasurementGeometryCodec.read(row.drawingJson)
        require(geometry.shapeType == row.shape)
        fun text(mm: Long) = formatMillimetres(mm).removeSuffix(" м")
        val outline = if (geometry.shapeType == "polygon") {
            require(geometry.sides.isNotEmpty()) { "Немає виміряних сторін. Редагування недоступне." }
            MeasurementOutlineDraft.Orthogonal(geometry.sides.mapIndexed { i, side ->
                MeasurementSideInput(text(side.lengthMm), side.turnAfter, "saved-side-$i")
            })
        } else MeasurementOutlineDraft.Template(geometry.shapeType, geometry.measurementsMm.mapValues { text(it.value) })
        val draft = MeasurementCreateDraft(projectId, requireNotNull(MeasurementPrecision.fromStep(geometry.precisionStepMeters)),
            row.name, row.notes, outline, row.uuid)
        require(validateMeasurementCreate(draft).geometry == geometry) { "Неповні вимірювання. Редагування недоступне." }
        MeasurementEditDocument(row.id, row.updatedAt, draft)
    }

    fun update(original: MeasurementEditDocument, draft: MeasurementCreateDraft): MeasurementEditDocument {
        require(draft.projectId == original.draft.projectId && draft.recordUuid == original.draft.recordUuid &&
            draft.precision == original.draft.precision)
        val validation = validateMeasurementCreate(draft)
        require(validation.canSave)
        val geometry = requireNotNull(validation.geometry)
        return database.transactionWithResult {
            database.measurementElementsQueries.claimElementWrite("")
            // Check readability as well as enforcing ownership/active-parent/version in the UPDATE itself.
            val live = measurements.find(original.measurementId)
            if (projects.find(draft.projectId) == null || live == null || live.projectId != draft.projectId ||
                live.uuid != draft.recordUuid || live.updatedAt != original.updatedAt) throw MeasurementEditConflict()
            val liveGeometry = MeasurementGeometryCodec.read(live.drawingJson)
            require(liveGeometry.precisionStepMeters == draft.precision.stepMeters) {
                "Точність існуючого заміру змінювати не можна."
            }
            val normalized = draft.copy(name = draft.name.trim(), notes = draft.notes.trim())
            // No-op still checks live ownership, parent and version, but never writes.
            // Compare the validated document, not JDBC REAL conversion's binary tails.
            if (live.name == normalized.name && live.notes == normalized.notes && live.shape == geometry.shapeType &&
                liveGeometry == geometry) {
                return@transactionWithResult MeasurementEditDocument(original.measurementId, live.updatedAt, normalized)
            }
            val version = measurements.updateIfUnchanged(original.measurementId, draft.projectId, original.updatedAt,
                draft.name.trim(), geometry.shapeType,
                if (liveGeometry == geometry) live.lengthMeters else (geometry.vertices.maxOf { it.xMm } - geometry.vertices.minOf { it.xMm }) / 1000.0,
                if (liveGeometry == geometry) live.widthMeters else (geometry.vertices.maxOf { it.yMm } - geometry.vertices.minOf { it.yMm }) / 1000.0,
                if (liveGeometry == geometry) live.areaSquareMeters else geometry.areaSquareMeters,
                if (liveGeometry == geometry) live.perimeterMeters else geometry.perimeterMeters,
                if (liveGeometry == geometry) live.drawingJson else MeasurementGeometryCodec.write(geometry),
                draft.notes.trim(), nowMillis()) ?: throw MeasurementEditConflict()
            MeasurementEditDocument(original.measurementId, version, normalized)
        }
    }
}
