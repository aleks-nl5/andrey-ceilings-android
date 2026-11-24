package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val elements: Map<String, Long> = emptyMap(), val walls: List<CeilingWall> = emptyList(), val quantities: Map<String,Long> = emptyMap(), val lines: List<LinePlacement> = emptyList(), val areas: List<AreaFeature> = emptyList())
data class MeasurementEditDocument(
    val measurementId: Long,
    val updatedAt: Long,
    val draft: MeasurementCreateDraft,
    val placements: CeilingPlacementDocument = CeilingPlacementDocument(),
    val sourceDrawingJson: String? = null,
    val lineElements: List<MeasurementElement> = emptyList(),
    val areaElements: List<MeasurementElement> = emptyList(),
    val pointElements: List<MeasurementElement> = emptyList(),
    val materialElements: List<MeasurementElement> = emptyList(),
    val otherElements: List<MeasurementElement> = emptyList()
)

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
    internal suspend fun placementCatalog(): List<ElementCatalogItem> = withContext(Dispatchers.Default) {
        pointRead.withLock {
            check(!sessionClosed.value)
            database.transactionWithResult {
                val prices = PriceRepository(database)
                val groupIds = prices.groups.all().map { it.uuid }.toSet()
                prices.all().filter { it.groupUuid in groupIds }.map { item ->
                    ElementCatalogItem(item.uuid, item.groupUuid, item.title, item.code, item.unit,
                        QuantityMode.entries.single { it.value == item.quantityMode },
                        PlacementKind.entries.single { it.value == item.placementKind })
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeDraftPricingBasis(projectId: Long, measurementUuid: String, creating: Boolean): Flow<DraftPricingBasis?> =
        sessionClosed.flatMapLatest { closed ->
            val unavailable: DraftPricingBasis = DraftPricingBasis.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE)
            if (closed) flowOf<DraftPricingBasis?>(unavailable)
            else database.livePricingQueries.selectPricingInvalidations().asFlow().transformLatest {
                emit(null)
                val basis = withContext(Dispatchers.Default) {
                    pointRead.withLock {
                        if (sessionClosed.value) return@withLock unavailable
                        database.transactionWithResult {
                            val reader = LivePricingReader(database)
                            if (creating) {
                                val project = projects.find(projectId)
                                if (project == null) DraftPricingBasis.Unavailable(PricingProblemCode.PARENT_UNAVAILABLE)
                                else DraftPricingBasis.Created(project.uuid, reader.readActiveCatalog())
                            } else when (val read = reader.readInTransaction(projectId, measurementUuid)) {
                                is PricingReadResult.Loaded -> DraftPricingBasis.Saved(read)
                                is PricingReadResult.Unavailable -> DraftPricingBasis.Unavailable(read.problem)
                            }
                        }
                    }
                }
                emit(if (sessionClosed.value) unavailable else basis)
            }.catch { if (it is CancellationException) throw it else emit(unavailable) }
        }

    private val measurements = MeasurementRepository(database)
    private val projects = ProjectRepository(database)
    private val creation = MeasurementCreateStore(database, nowMillis)

    internal fun observeCreateParent(projectId: Long): Flow<MeasurementCreateParent> = creation.observeParent(projectId)
    // Only an in-flight/delivered write is retained, scoped to this unlocked database session.
    // Android Activity recreation can reattach to the same operation rather than replaying Save.
    private val pending = MutableStateFlow<Map<String, MeasurementEditModel>>(emptyMap())
    internal fun retain(token: String, model: MeasurementEditModel) { pending.update { it + (token to model) } }
    internal fun release(token: String) { pending.update { it - token } }
    internal fun retained(token: String): MeasurementEditModel? = pending.value[token]

    fun load(projectId: Long, measurementId: Long): MeasurementEditDocument = loadDocument(projectId,measurementId)

    internal suspend fun readDocument(projectId:Long,measurementId:Long):MeasurementEditDocument=withContext(Dispatchers.Default) {
        pointRead.withLock {
            if(sessionClosed.value)throw MeasurementEditConflict()
            loadDocument(projectId,measurementId)
        }
    }

    /** One transactional document, rather than a fingerprint of independently loading UI lists.
     * The existing overlay query observes POINT/element changes and every ceiling commit
     * through its measurement row. Accepted reads share the session shutdown drain.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeDocument(projectId:Long,measurementId:Long,uuid:String):Flow<MeasurementEditDocument?> =
        sessionClosed.flatMapLatest {closed ->
            if(closed)flowOf(null) else database.pointPlacementsQueries.selectEditorOverlay(measurementId,projectId,uuid).asFlow()
                .map {readDocument(projectId,measurementId).takeUnless {sessionClosed.value}}
                .catch {if(it is CancellationException)throw it else emit(null)}
        }
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
        val areas = AreaFeatureRepository(database).forMeasurement(row.uuid)
        val measurementElements = MeasurementElementRepository(database).forMeasurement(row.uuid)
        val areaElements = measurementElements.filter { it.supportsAreaPlacement() }
        val lineElements = measurementElements.filter { it.supportsLinePlacement() }
        val pointElements = measurementElements.filter {
            (it.source as? ElementSource.CatalogSelection)?.placementKind == PlacementKind.POINT
        }
        val elements = (points.map { it.elementUuid } + lines.map { it.elementUuid } + lineElements.map { it.uuid } + areaElements.map {it.uuid}).distinct().associateWith { MeasurementElementRepository(database).find(it)!!.updatedAt }
        val walls = database.ceilingEditsQueries.selectWalls(row.uuid).executeAsList().map { CeilingWall(it.uuid,it.ordinal.toInt()) }
        require(walls.size == geometry.vertices.size) { "Неповні ідентичності стін." }
        val quantities = points.map { it.elementUuid }.distinct().associateWith {
            MeasurementElementRepository(database).find(it)!!.pointCapacity()!!
        }
        MeasurementEditDocument(row.id, row.updatedAt, draft,
            CeilingPlacementDocument(points, elements, walls, quantities, lines, areas),
            row.drawingJson, lineElements, areaElements, pointElements,
            measurementElements.filter { it.businessRole?.isWorkspaceMaterial() == true },
            measurementElements.filter { it.businessRole?.isWorkspaceMaterial() != true &&
                it !in lineElements && it !in areaElements && it !in pointElements })
    }

    fun update(original: MeasurementEditDocument, draft: MeasurementCreateDraft,
        edits: Map<String, PointMountingEdit> = emptyMap(), operationUuid: String = newRecordUuid(),
        wallToken: String = operationUuid, now: Long = nowMillis(), acceptedWrite: kotlinx.coroutines.CompletableDeferred<Unit>? = null, lineEdits: Map<String,LinePlacementEdit> = emptyMap(), declaredLengths: Map<String,Long> = emptyMap(), areaEdits: Map<String,AreaFeatureEdit> = emptyMap(), declaredAreas: Map<String,Long> = emptyMap(), additions: WorkspacePlacementDraft = WorkspacePlacementDraft(),
        contourAnchorReference: ContourAnchorReference? = null): MeasurementEditDocument {
        check(!sessionClosed.value || acceptedWrite in writes.value.pending) { "Сесію закрито." }
        var rolledBack = false
        try {
            return database.transactionWithResult {
                // Runs after endTransaction succeeds, only for the outer rollback. Do not infer
                // this from a platform exception class/message or from absence of a receipt.
                afterRollback { rolledBack = true }
                val walls = ceilingDraftWalls(original, draft, wallToken)
                val geometry = requireNotNull(validateMeasurementCreate(draft).geometry)
                val correspondence = contourAnchorReference.correspondence(geometry, walls)
                fun rebind(anchor: WallAnchor): WallAnchor = anchor.rebind(correspondence, geometry, walls) ?: anchor
                fun rebindMounting(mounting: PointMounting): PointMounting =
                    mounting.copy(anchor = mounting.anchor?.let(::rebind))
                val requestedPoints = edits.mapValues { (_, edit) -> edit.copy(anchor = edit.anchor?.let(::rebind)) }
                val requestedLines = lineEdits.mapValues { (_, edit) -> edit.copy(value = edit.value.copy(
                    startMounting = rebindMounting(edit.value.startMounting),
                    endMounting = rebindMounting(edit.value.endMounting)
                )) }
                val requestedAreas = areaEdits.mapValues { (_, edit) -> edit.copy(value = edit.value.copy(
                    mountings = edit.value.mountings.map(::rebindMounting)
                )) }
                if (original.measurementId == 0L) {
                    database.measurementElementsQueries.claimElementWrite("")
                    val existing = createdDocument(draft, operationUuid, allowClosing = true)
                    val initial = if (existing == null) {
                        loadDocument(draft.projectId, creation.createAt(draft, now), allowClosing = true)
                    } else {
                        // The existing journal still validates the complete normalized request on replay.
                        val receipt = checkNotNull(database.ceilingEditsQueries.selectOperation(operationUuid).executeAsOneOrNull())
                        existing.copy(updatedAt = receipt.expectedUpdatedAt)
                    }
                    val id = initial.measurementId
                    val wallIds = walls.zip(initial.placements.walls).associate { (temporary, stored) -> temporary.uuid to stored.uuid }
                    fun remap(anchor: WallAnchor): WallAnchor = anchor.copy(
                        firstWall = requireNotNull(wallIds[anchor.firstWall]),
                        secondWall = anchor.secondWall?.let { requireNotNull(wallIds[it]) }
                    )
                    fun remapMounting(mounting: PointMounting): PointMounting =
                        mounting.copy(anchor = mounting.anchor?.let(::remap))
                    val pointEdits = requestedPoints.mapValues { (_, edit) -> edit.copy(anchor = edit.anchor?.let(::remap)) }
                    val lines = requestedLines.mapValues { (_, edit) -> edit.copy(value = edit.value.copy(
                        startMounting = remapMounting(edit.value.startMounting),
                        endMounting = remapMounting(edit.value.endMounting)
                    )) }
                    val areas = requestedAreas.mapValues { (_, edit) -> edit.copy(value = edit.value.copy(
                        mountings = edit.value.mountings.map(::remapMounting)
                    )) }
                    WorkspacePlacementWriter(database) {
                        loadDocument(draft.projectId, id, allowClosing = true)
                    }.save(initial, draft, pointEdits, operationUuid, wallToken, now, lines, declaredLengths,
                        areas, declaredAreas, additions)
                    return@transactionWithResult loadDocument(draft.projectId, id, allowClosing = true)
                }
                if (additions.isEmpty) {
                    CeilingEditRepository(database).save(original, draft, requestedPoints, operationUuid, wallToken, now,
                        requestedLines, declaredLengths, requestedAreas, declaredAreas)
                } else {
                    WorkspacePlacementWriter(database) {
                        loadDocument(draft.projectId, original.measurementId, allowClosing = true)
                    }.save(original, draft, requestedPoints, operationUuid, wallToken, now, requestedLines, declaredLengths,
                        requestedAreas, declaredAreas, additions, correspondence)
                }
                loadDocument(draft.projectId, original.measurementId, allowClosing = true)
            }
        } catch (failure: Throwable) {
            if (!rolledBack) throw MeasurementEditUncertain(failure)
            when (failure) {
                is MeasurementEditConflict, is PointPlacementFailure.RetryConflict, is IllegalArgumentException -> throw failure
                else -> throw MeasurementEditRejected(failure)
            }
        }
    }
    internal fun createdDocument(draft: MeasurementCreateDraft, operation: String,
        allowClosing: Boolean = false): MeasurementEditDocument? = database.transactionWithResult {
        val row = measurements.findByUuid(draft.recordUuid) ?: return@transactionWithResult null
        if (!WorkspacePlacementWriter.currentCreationResult(database, operation, draft, row.updatedAt))
            throw MeasurementEditConflict()
        loadDocument(draft.projectId, row.id, allowClosing)
    }

    /** Receipt verification is mandatory for automatic recovery. Only an explicitly
     * confirmed reload (operation == null) may select the current record by UUID. */
    internal suspend fun readCreatedDocument(draft: MeasurementCreateDraft, operation: String? = null): MeasurementEditDocument? =
        withContext(Dispatchers.Default) {
            pointRead.withLock {
                if (sessionClosed.value) throw MeasurementEditConflict()
                database.transactionWithResult {
                    if (projects.find(draft.projectId) == null) throw MeasurementEditConflict()
                    if (operation != null) return@transactionWithResult createdDocument(draft, operation)
                    val row = measurements.findByUuid(draft.recordUuid) ?: return@transactionWithResult null
                    loadDocument(draft.projectId, row.id)
                }
            }
        }

    fun now(): Long = nowMillis()
    fun operationCommitted(uuid:String,measurement:String,version:Long):Boolean {
        if(sessionClosed.value)return false
        return database.transactionWithResult {
            measurements.findByUuid(measurement)?.updatedAt == version &&
                WorkspacePlacementWriter.currentResult(database, uuid, measurement, version)
        }
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
