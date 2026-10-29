package com.andreyceilings.app.data.db

import kotlinx.coroutines.flow.first

/**
 * Immutable logical snapshot of the five Room-v2 business tables.
 *
 * capture() alone is NOT a transactionally consistent backup if the live Room
 * database is being modified concurrently. A production cutover must take a
 * verified offline snapshot first and only then capture it.
 */
internal data class LegacyRoomSnapshot(
    val clients: List<ClientEntity>,
    val projects: List<ProjectEntity>,
    val measurements: List<RoomMeasurementEntity>,
    val priceItems: List<PriceItemEntity>,
    val estimates: List<EstimateEntity>
)

internal data class StagedImportCounts(
    val clients: Long,
    val projects: Long,
    val measurements: Long,
    val priceItems: Long,
    val estimates: Long
)

/**
 * Migration proof: copy a Room snapshot into a FRESH, independently encrypted
 * SQLDelight database, keeping IDs/foreign keys. Never writes to the source Room
 * database, replaces live files, imports keys or changes Android startup behavior.
 *
 * This is NOT yet the production migration/restore mechanism. It must run on a
 * frozen source snapshot; the caller is responsible for backup and file lifecycle.
 */
internal object RoomToSqlDelightStagingImporter {
    suspend fun capture(legacy: CeilingsDatabase): LegacyRoomSnapshot =
        LegacyRoomSnapshot(
            clients = legacy.clients().observeAll().first(),
            projects = legacy.projects().observeAll().first(),
            measurements = legacy.measurements().observeAll().first(),
            priceItems = legacy.priceItems().observeAll().first(),
            estimates = legacy.estimates().observeAll().first()
        )

    fun importIntoEmptyStage(
        snapshot: LegacyRoomSnapshot,
        stage: SqlDelightDatabaseSession
    ): StagedImportCounts {
        val database = stage.database
        val queries = database.businessSchemaQueries
        return database.transactionWithResult {
            check(
                stage.clients.count() == 0L &&
                stage.projects.count() == 0L &&
                stage.measurements.count() == 0L &&
                stage.prices.count() == 0L &&
                stage.estimates.count() == 0L
            ) { "Staging database must be empty; refusing to overwrite existing rows" }

            snapshot.clients.forEach {
                queries.importClientWithId(it.id, it.name, it.phone, it.email, it.notes, it.createdAt)
            }
            snapshot.projects.forEach {
                queries.importProjectWithId(
                    it.id, it.clientId, it.title, it.address, it.status, it.notes,
                    it.createdAt, it.updatedAt
                )
            }
            snapshot.measurements.forEach {
                queries.importMeasurementWithId(
                    it.id, it.projectId, it.name, it.shape, it.lengthMeters, it.widthMeters,
                    it.areaSquareMeters, it.perimeterMeters, it.drawingJson, it.notes, it.createdAt
                )
            }
            snapshot.priceItems.forEach {
                queries.importPriceItemWithId(
                    it.id, it.code, it.title, it.category, it.unit, it.price, it.updatedAt
                )
            }
            snapshot.estimates.forEach {
                queries.importEstimateWithId(
                    it.id, it.projectId, it.name, it.material, it.profileType,
                    it.complexity, it.lengthMeters, it.widthMeters, it.areaSquareMeters,
                    it.perimeterMeters, it.canvasCost, it.profileCost, it.complexityCost,
                    it.optionsCost, it.totalCost, it.detailsJson, it.createdAt
                )
            }
            val counts = StagedImportCounts(
                clients = stage.clients.count(),
                projects = stage.projects.count(),
                measurements = stage.measurements.count(),
                priceItems = stage.prices.count(),
                estimates = stage.estimates.count()
            )
            check(
                counts.clients == snapshot.clients.size.toLong() &&
                counts.projects == snapshot.projects.size.toLong() &&
                counts.measurements == snapshot.measurements.size.toLong() &&
                counts.priceItems == snapshot.priceItems.size.toLong() &&
                counts.estimates == snapshot.estimates.size.toLong()
            ) { "Room → SQLDelight import count mismatch; rolling back transaction" }
            counts
        }
    }
}
