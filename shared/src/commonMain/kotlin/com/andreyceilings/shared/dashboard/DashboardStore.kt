package com.andreyceilings.shared.dashboard

import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** No Android Context or OS-specific date logic; one encrypted database supplied by the host. */
class DashboardStore(database: PrototypeDatabase) {
    private val projects = ProjectRepository(database)
    private val measurements = MeasurementRepository(database)

    val summary: Flow<DashboardSummary> = combine(
        projects.observeAll(), measurements.observeAll()
    ) { activeProjects, activeMeasurements ->
        DashboardSummary.fromRecords(
            activeProjectIds = activeProjects.map { it.id }.toSet(),
            measurements = activeMeasurements.map {
                DashboardMeasurement(it.projectId, it.areaSquareMeters)
            }
        )
    }
}
