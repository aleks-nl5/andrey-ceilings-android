package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

sealed interface MeasurementsState {
    data object Loading : MeasurementsState
    data object Unavailable : MeasurementsState
    data class Loaded(val measurements: List<Room_measurements>, val projects: List<Projects>) : MeasurementsState
}

/** Global list uses the same active SQLDelight records and tombstones as Project Details. */
class MeasurementsStore(private val database: PrototypeDatabase) {
    private val measurements = MeasurementRepository(database)
    private val projects = ProjectRepository(database)

    fun observe(): Flow<MeasurementsState> = combine(measurements.observeAll(), projects.observeAll()) { rows, parents ->
        val activeIds = parents.map { it.id }.toSet()
        MeasurementsState.Loaded(rows.filter { it.projectId in activeIds }, parents)
    }.onStart<MeasurementsState> { emit(MeasurementsState.Loading) }
        .catch { emit(MeasurementsState.Unavailable) }
        .flowOn(Dispatchers.Default)

    suspend fun delete(projectId: Long, measurementId: Long) = withContext(Dispatchers.Default) {
        database.transaction {
            checkNotNull(projects.find(projectId))
            check(measurements.find(measurementId)?.projectId == projectId)
            measurements.delete(measurementId)
        }
    }

    /** Reload at the action boundary, so a stale card cannot calculate a deleted/changed row. */
    suspend fun geometry(projectId: Long, measurementId: Long) = withContext(Dispatchers.Default) {
        database.transactionWithResult {
            checkNotNull(projects.find(projectId))
            val row = checkNotNull(measurements.find(measurementId))
            check(row.projectId == projectId)
            MeasurementGeometryCodec.read(row.drawingJson).also { check(it.shapeType == row.shape) }
        }
    }
}
