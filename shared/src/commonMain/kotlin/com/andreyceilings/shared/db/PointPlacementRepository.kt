package com.andreyceilings.shared.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Each mutation claims the same SQLite writer lock as P3A before reading any invariant. */
class PointPlacementRepository(private val database: PrototypeDatabase) {
    private val queries = database.pointPlacementsQueries
    private val elements = MeasurementElementRepository(database)
    private val measurements = MeasurementRepository(database)

    fun observeForMeasurement(measurementUuid: String) = queries.selectForMeasurement(measurementUuid)
        .asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.model() } }
        .catch { if (it is CancellationException) throw it else throw PointPlacementFailure.DatabaseUnavailable(it) }
    fun forMeasurement(measurementUuid: String): List<PointPlacement> = access {
        queries.selectForMeasurement(measurementUuid).executeAsList().map { it.model() }
    }
    fun find(uuid: String): PointPlacement? = access { read(uuid) }
    fun tombstones(): List<PointPlacement> = access { queries.selectTombstones(::Point_placements).executeAsList().map { it.model() } }

    /** A UUID belongs to one logical tap. Retry must pass the same payload, even after an uncertain commit. */
    fun addPoint(original: ElementVersion, contourUpdatedAt: Long, xMm: Long, yMm: Long,
        now: Long, uuid: String = newRecordUuid()): PointPlacement = write(original.uuid) {
        if (uuid.isBlank() || now !in 0 until Long.MAX_VALUE || contourUpdatedAt < 0) throw PointPlacementFailure.InvalidInput()
        val element = parent(original)
        read(uuid)?.let {
            if (it.deletedAt != null || it.elementUuid != original.uuid || it.xMm != xMm || it.yMm != yMm ||
                it.contourUpdatedAt != contourUpdatedAt) throw PointPlacementFailure.RetryConflict()
            return@write it
        }
        if (element.updatedAt != original.expectedUpdatedAt || element.updatedAt == Long.MAX_VALUE)
            throw PointPlacementFailure.StaleVersion()
        val capacity = element.pointCapacity() ?: throw PointPlacementFailure.UnsupportedElement()
        val row = measurements.findByUuid(original.measurementUuid) ?: throw PointPlacementFailure.ParentUnavailable()
        if (row.updatedAt != contourUpdatedAt) throw PointPlacementFailure.StaleContour()
        val view = measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters)
            as? MeasurementGeometryView.Captured ?: throw PointPlacementFailure.InvalidContour()
        if (!pointStrictlyInside(view.geometry, xMm, yMm)) throw PointPlacementFailure.OutsideContour()
        if (queries.countForElement(element.uuid).executeAsOne() >= capacity) throw PointPlacementFailure.CapacityReached()
        queries.insertPoint(uuid, element.uuid, xMm, yMm, contourUpdatedAt, now)
        checkNotNull(read(uuid))
    }

    /** Tombstone retry is idempotent; an active point additionally requires its own and element CAS. */
    fun removePoint(original: ElementVersion, point: PointPlacement, now: Long): PointPlacement = write(original.uuid) {
        if (now !in 0 until Long.MAX_VALUE || point.elementUuid != original.uuid) throw PointPlacementFailure.InvalidInput()
        val element = parent(original)
        val actual = read(point.uuid) ?: throw PointPlacementFailure.StaleVersion()
        if (actual.elementUuid != element.uuid) throw PointPlacementFailure.StaleVersion()
        if (actual.deletedAt != null) return@write actual
        if (element.updatedAt != original.expectedUpdatedAt || element.updatedAt == Long.MAX_VALUE ||
            actual.updatedAt != point.updatedAt || actual.updatedAt == Long.MAX_VALUE) throw PointPlacementFailure.StaleVersion()
        queries.removePoint(now, point.uuid, element.uuid, point.updatedAt)
        checkNotNull(read(point.uuid))
    }

    /** One immutable operation receipt authorizes one CAS position update. Reuse it after uncertainty. */
    fun movePoint(original: ElementVersion, point: PointPlacement, contourUpdatedAt: Long,
        xMm: Long, yMm: Long, precision: PointPrecision, now: Long,
        operationUuid: String = newRecordUuid()): PointMoveResult = write(original.uuid) {
        if (operationUuid.isBlank() || now !in 0 until Long.MAX_VALUE || point.elementUuid != original.uuid)
            throw PointPlacementFailure.InvalidInput()
        val element = parent(original)
        val actual = read(point.uuid)?.takeIf { it.elementUuid == original.uuid && it.deletedAt == null }
            ?: throw PointPlacementFailure.StaleVersion()
        queries.selectMoveOperation(operationUuid).executeAsOneOrNull()?.let { receipt ->
            if (receipt.pointUuid != point.uuid || receipt.expectedPointUpdatedAt != point.updatedAt ||
                receipt.expectedElementUpdatedAt != original.expectedUpdatedAt || receipt.contourUpdatedAt != contourUpdatedAt ||
                receipt.xMm != xMm || receipt.yMm != yMm || receipt.precision != precision.value)
                throw PointPlacementFailure.RetryConflict()
            if (actual.lastMoveUuid != operationUuid || actual.updatedAt != maxOf(receipt.expectedPointUpdatedAt+1,receipt.createdAt) ||
                actual.lastCeilingEditUuid != point.lastCeilingEditUuid || actual.xMm != receipt.xMm || actual.yMm != receipt.yMm || actual.precision.value != receipt.precision)
                throw PointPlacementFailure.StaleVersion()
            return@write PointMoveResult(actual, element)
        }
        if (element.updatedAt != original.expectedUpdatedAt || actual.updatedAt != point.updatedAt ||
            actual.contourUpdatedAt != point.contourUpdatedAt || actual.updatedAt == Long.MAX_VALUE || element.updatedAt == Long.MAX_VALUE)
            throw PointPlacementFailure.StaleVersion()
        if(actual.mounting.unresolved) throw PointPlacementFailure.RequiresMeasuredAnchor()
        val capacity = element.pointCapacity() ?: throw PointPlacementFailure.UnsupportedElement()
        val count = queries.countForElement(element.uuid).executeAsOne()
        if (count <= 0 || count > capacity || element.placedQuantityMilli != queries.selectForMeasurement(element.measurementUuid).executeAsList().count { it.elementUuid == element.uuid && !decodeMounting(it.mountingJson).unresolved } * 1000L) throw PointPlacementFailure.StaleVersion()
        val row = measurements.findByUuid(original.measurementUuid) ?: throw PointPlacementFailure.ParentUnavailable()
        if (row.updatedAt != contourUpdatedAt) throw PointPlacementFailure.StaleContour()
        val geometry = (measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters)
            as? MeasurementGeometryView.Captured)?.geometry ?: throw PointPlacementFailure.InvalidContour()
        if (!pointStrictlyInside(geometry, xMm, yMm)) throw PointPlacementFailure.OutsideContour()
        queries.insertMoveOperation(operationUuid, point.uuid, point.updatedAt, original.expectedUpdatedAt,
            contourUpdatedAt, xMm, yMm, precision.value, now)
        queries.movePoint(xMm=xMm, yMm=yMm, precision=precision.value, operationUuid=operationUuid, now=now, pointUuid=point.uuid, elementUuid=element.uuid, expectedPointUpdatedAt=point.updatedAt)
        val saved = checkNotNull(read(point.uuid))
        if (saved.lastMoveUuid != operationUuid) throw PointPlacementFailure.StaleVersion()
        PointMoveResult(saved, checkNotNull(elements.find(element.uuid)))
    }

    private fun parent(original: ElementVersion): MeasurementElement {
        if (database.measurementElementsQueries.selectActiveParent(original.measurementUuid).executeAsOneOrNull() == null)
            throw PointPlacementFailure.ParentUnavailable()
        return elements.find(original.uuid)?.takeIf { it.measurementUuid == original.measurementUuid && it.deletedAt == null }
            ?: throw PointPlacementFailure.ParentUnavailable()
    }
    private fun read(uuid: String) = queries.selectByUuid(uuid).executeAsOneOrNull()?.model()
    private fun <T> write(uuid: String, block: () -> T): T = access {
        database.transactionWithResult { database.measurementElementsQueries.claimElementWrite(uuid); block() }
    }
    private fun <T> access(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: PointPlacementFailure) { throw failure }
        catch (failure: Exception) { throw PointPlacementFailure.DatabaseUnavailable(failure) }
}

private fun Point_placements.model() = PointPlacement(uuid, elementUuid, xMm, yMm, contourUpdatedAt,
    createdAt, updatedAt, deletedAt, PointPrecision.entries.single { it.value == precision }, lastMoveUuid, decodeMounting(mountingJson), lastCeilingEditUuid)
