package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface PricingReadResult {
    data class Loaded(val source: LiveEstimateSource, val catalog: ActiveCatalogSnapshot) : PricingReadResult {
        /** Production roles originate only in this complete, coherent source document. */
        fun buildPreview(): LiveEstimatePreview = LivePricingEngine.buildPreview(source, catalog,
            BusinessRoleBindings(source.signature, source.elements.mapNotNull { element ->
                element.businessRole?.let { element.uuid to it }
            }.toMap()))
    }
    data class Unavailable(val problem: PricingProblemCode) : PricingReadResult
}

/** Does not own/initialize/close the database. Owner must close reads before replacing the DB session.
 * Every call reads a fresh physical transaction. No cached successful value survives any failure.
 */
class LivePricingReader(private val database: PrototypeDatabase) {
    private data class Session(val closed: Boolean = false, val readers: Long = 0)
    private val session = MutableStateFlow(Session())
    private val closed: Boolean get() = session.value.closed

    /** Close admission atomically, then drain every accepted physical transaction, including failures.
     * Cancellation cannot release the owner's DB replacement/restore barrier prematurely.
     */
    fun closeAdmission() { session.update { it.copy(closed = true) } }

    suspend fun closeReads() {
        closeAdmission()
        withContext(NonCancellable) { session.first { it.readers == 0L } }
    }

    private fun <T> inSession(unavailable: T, read: () -> T): T {
        while (true) {
            val before = session.value
            if (before.closed || before.readers == Long.MAX_VALUE) return unavailable
            if (session.compareAndSet(before, before.copy(readers = before.readers + 1))) break
        }
        try {
            val result = read()
            return if (closed) unavailable else result
        }
        finally { session.update { it.copy(readers = it.readers - 1) } }
    }

    fun read(projectId: Long, measurementUuid: String, expectedSourceSignature: String? = null,
        expectedCatalogSignature: String? = null): PricingReadResult {
        return inSession<PricingReadResult>(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE)) {
            access {
                database.transactionWithResult {
                    readInTransaction(projectId, measurementUuid, expectedSourceSignature, expectedCatalogSignature)
                }
            }
        }
    }

    /** Caller owns physical admission, writer lock and the outer transaction. Never acquires a reader lease. */
    internal fun readInTransaction(projectId: Long, measurementUuid: String,
        expectedSourceSignature: String? = null, expectedCatalogSignature: String? = null): PricingReadResult = run {
                    if (closed) return@run PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE)
                    val project = ProjectRepository(database).find(projectId)
                        ?: return@run PricingReadResult.Unavailable(PricingProblemCode.PARENT_UNAVAILABLE)
                    val measurement = MeasurementRepository(database).findByUuid(measurementUuid)?.takeIf { it.projectId == projectId && it.deletedAt == null }
                        ?: return@run PricingReadResult.Unavailable(PricingProblemCode.PARENT_UNAVAILABLE)
                    val geometry = try { MeasurementGeometryCodec.read(measurement.drawingJson) }
                        catch (_: IllegalArgumentException) { return@run PricingReadResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY) }
                    if (geometry.shapeType != measurement.shape || geometry.areaSquareMeters != measurement.areaSquareMeters ||
                        geometry.perimeterMeters != measurement.perimeterMeters)
                        return@run PricingReadResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY)
                    val elements = MeasurementElementRepository(database).forMeasurement(measurementUuid)
                    val walls = database.livePricingQueries.selectPricingWalls(measurementUuid).executeAsList()
                    val activeWalls = walls.filter { it.active == 1L }.sortedBy { it.ordinal }
                    if (activeWalls.size != geometry.vertices.size || activeWalls.withIndex().any { (i, wall) ->
                            val a = geometry.vertices[i]; val b = geometry.vertices[(i + 1) % geometry.vertices.size]
                            wall.ordinal != i.toLong() || wall.ax != a.xMm || wall.ay != a.yMm || wall.bx != b.xMm || wall.by_ != b.yMm || wall.geometryVersion != measurement.updatedAt
                        }) return@run PricingReadResult.Unavailable(PricingProblemCode.INVALID_SOURCE)
                    val receiptToken = database.livePricingQueries.selectPricingReceipts(measurementUuid).executeAsList().map { r ->
                        contentToken(listOf(r.uuid, r.expectedUpdatedAt.toString(), r.payload, r.createdAt.toString(),
                            r.measurementVersion.toString(), r.pointVersions.orEmpty(), r.elementVersions.orEmpty(), r.lineVersions.orEmpty(), r.areaVersions.orEmpty()))
                    }
                    val source = LiveEstimateSource(project.uuid, measurementUuid, geometry, elements,
                        PointPlacementRepository(database).forMeasurement(measurementUuid),
                        LinePlacementRepository(database).forMeasurement(measurementUuid), AreaFeatureRepository(database).forMeasurement(measurementUuid),
                        activeWalls.map { CeilingWall(it.uuid, it.ordinal.toInt()) },
                        mapOf("measurementName" to measurement.name, "projectId" to projectId.toString(), "projectUpdatedAt" to project.updatedAt.toString(), "measurementUpdatedAt" to measurement.updatedAt.toString(),
                            "measurementMetadata" to contentToken(listOf(measurement.name, measurement.notes, measurement.drawingJson)),
                            "walls" to contentToken(walls.map { w -> contentToken(listOf(w.uuid, w.ordinal.toString(), w.ax.toString(), w.ay.toString(),
                                w.bx.toString(), w.by_.toString(), w.geometryVersion.toString(), w.active.toString())) }),
                            "receipts" to contentToken(receiptToken)))
                    val catalog = ActiveCatalogSnapshot(database.livePricingQueries.selectActiveCatalog().executeAsList().map { i ->
                        ActiveCatalogItem(i.uuid, i.groupUuid, i.code, i.title, i.unit,
                            QuantityMode.entries.single { it.value == i.quantityMode }, PlacementKind.entries.single { it.value == i.placementKind },
                            i.price, i.updatedAt, i.groupUpdatedAt)
                    })
                    when {
                        closed -> PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE)
                        expectedSourceSignature != null && source.signature != expectedSourceSignature -> PricingReadResult.Unavailable(PricingProblemCode.STALE_SOURCE)
                        expectedCatalogSignature != null && catalog.signature != expectedCatalogSignature -> PricingReadResult.Unavailable(PricingProblemCode.STALE_CATALOG)
                        else -> PricingReadResult.Loaded(source, catalog)
                    }
    }

    /** Exact UUID lookup and ownership check, never latest revision and never repricing.
     * Current immutable history cannot establish leaf/element coverage: explicitly unavailable.
     */
    fun verifyFrozenRevision(projectId: Long, seriesUuid: String, revisionUuid: String): PricingProblemCode {
        return inSession(PricingProblemCode.DATABASE_UNAVAILABLE) {
            try {
                database.transactionWithResult {
                    if (closed) return@transactionWithResult PricingProblemCode.DATABASE_UNAVAILABLE
                    val project = ProjectRepository(database).find(projectId)
                        ?: return@transactionWithResult PricingProblemCode.PARENT_UNAVAILABLE
                    val series = database.estimateHistoryQueries.selectEstimateSeries(seriesUuid).executeAsOneOrNull()
                        ?.takeIf { it.deletedAt == null } ?: return@transactionWithResult PricingProblemCode.REVISION_UNAVAILABLE
                    if (series.projectId != project.id) return@transactionWithResult PricingProblemCode.CROSS_PROJECT
                    val revision = database.estimateHistoryQueries.selectEstimateRevision(revisionUuid).executeAsOneOrNull()
                        ?: return@transactionWithResult PricingProblemCode.REVISION_UNAVAILABLE
                    if (revision.seriesUuid != seriesUuid) return@transactionWithResult PricingProblemCode.REVISION_UNAVAILABLE
                    PricingProblemCode.PROVENANCE_MISSING
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { PricingProblemCode.DATABASE_UNAVAILABLE }
        }
    }

    private fun access(block: () -> PricingReadResult): PricingReadResult = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE) }
}
