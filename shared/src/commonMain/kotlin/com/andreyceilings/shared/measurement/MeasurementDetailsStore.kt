package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

data class MeasurementDetailsData(
    val id: Long, val projectId: Long, val name: String,
    val projectTitle: String, val projectAddress: String, val shape: String,
    val areaSquareMeters: Double, val perimeterMeters: Double, val notes: String,
    val geometry: MeasurementGeometryView, val uuid: String = ""
)

sealed interface MeasurementDetailsState {
    data object Loading : MeasurementDetailsState
    data object Unavailable : MeasurementDetailsState
    data object Missing : MeasurementDetailsState
    data class Loaded(val data: MeasurementDetailsData) : MeasurementDetailsState
}

/** Reactive read-only boundary. Neither opening nor restoring can perform a write. */
class MeasurementDetailsStore(database: PrototypeDatabase, nowMillis: () -> Long =
    { com.andreyceilings.shared.dashboard.dashboardClockMillis() }) {
    val elements = MeasurementElementsStore(database, nowMillis)
    private val measurements = MeasurementRepository(database)
    private val projects = ProjectRepository(database)

    fun observe(projectId: Long, measurementId: Long): Flow<MeasurementDetailsState> {
        if (projectId <= 0 || measurementId <= 0) return flowOf(MeasurementDetailsState.Missing)
        return combine(projects.observeById(projectId), measurements.observeById(measurementId)) { project, row ->
            if (project == null || row == null || row.projectId != projectId) MeasurementDetailsState.Missing
            else MeasurementDetailsState.Loaded(MeasurementDetailsData(
                row.id, projectId, row.name, project.title, project.address, row.shape,
                row.areaSquareMeters, row.perimeterMeters, row.notes,
                measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters), row.uuid
            ))
        }.onStart<MeasurementDetailsState> { emit(MeasurementDetailsState.Loading) }
            .catch { emit(MeasurementDetailsState.Unavailable) }
            .flowOn(Dispatchers.Default)
    }
}
