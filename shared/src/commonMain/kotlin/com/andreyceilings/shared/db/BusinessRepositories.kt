package com.andreyceilings.shared.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.coroutines.Dispatchers

/**
 * Shared business-data access, independent of platform SQLCipher drivers.
 * Timestamps are supplied by callers to keep common code deterministic.
 */
class ClientRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun tombstones() = queries.selectDeletedClients().executeAsList()
    fun all() = queries.selectClients().executeAsList()
    fun observeQuery() = queries.selectClients()
    fun observeAll() = queries.selectClients().asFlow().mapToList(Dispatchers.Default)
    fun count() = queries.countClients().executeAsOne()
    fun findByName(name: String) = queries.findClientByName(name).executeAsOneOrNull()
    fun createReturningId(name: String, phone: String = "", email: String = "", notes: String = "", createdAt: Long): Long =
        queries.insertClientReturningId(name, phone, email, notes, createdAt, newRecordUuid(), createdAt).executeAsOne()
    fun find(id: Long) = queries.selectClientById(id).executeAsOneOrNull()
    fun create(name: String, phone: String = "", email: String = "", notes: String = "", createdAt: Long) {
        queries.insertClient(name, phone, email, notes, createdAt, newRecordUuid(), createdAt)
    }
    fun update(id: Long, name: String, phone: String, email: String, notes: String) {
        queries.updateClient(name, phone, email, notes, id)
    }
    fun delete(id: Long) = queries.deleteClient(id)
}

class ProjectRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun tombstones() = queries.selectDeletedProjects().executeAsList()
    fun all() = queries.selectProjects().executeAsList()
    fun observeQuery() = queries.selectProjects()
    fun observeAll() = queries.selectProjects().asFlow().mapToList(Dispatchers.Default)
    fun count() = queries.countProjects().executeAsOne()
    fun updateStatus(id: Long, status: String, updatedAt: Long) =
        queries.updateProjectStatus(status, updatedAt, id)
    fun createReturningId(
        clientId: Long?, title: String, address: String = "", status: String = "measurement",
        notes: String = "", createdAt: Long, updatedAt: Long = createdAt
    ): Long = queries.insertProjectReturningId(clientId, title, address, status, notes, createdAt, updatedAt, newRecordUuid()).executeAsOne()
    fun find(id: Long) = queries.selectProjectById(id).executeAsOneOrNull()
    fun byClient(clientId: Long) = queries.selectProjectsByClient(clientId).executeAsList()
    fun create(
        clientId: Long?, title: String, address: String = "", status: String = "measurement",
        notes: String = "", createdAt: Long, updatedAt: Long = createdAt
    ) {
        queries.insertProject(clientId, title, address, status, notes, createdAt, updatedAt, newRecordUuid())
    }
    fun update(
        id: Long, clientId: Long?, title: String, address: String,
        status: String, notes: String, updatedAt: Long
    ) {
        queries.updateProject(clientId, title, address, status, notes, updatedAt, id)
    }
    fun delete(id: Long) = database.transaction {
        queries.softDeleteEstimatesForProject(id)
        database.estimateHistoryQueries.softDeleteEstimateSeriesForProject(id)
        queries.deleteProject(id)
    }
}

class MeasurementRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun forProject(projectId: Long) = queries.selectMeasurementsForProject(projectId).executeAsList()
    fun forProjectQuery(projectId: Long) = queries.selectMeasurementsForProject(projectId)
    fun observeForProject(projectId: Long) = queries.selectMeasurementsForProject(projectId).asFlow().mapToList(Dispatchers.Default)
    fun tombstones() = queries.selectDeletedMeasurements().executeAsList()
    fun all() = queries.selectAllMeasurements().executeAsList()
    fun observeQuery() = queries.selectAllMeasurements()
    fun observeAll() = queries.selectAllMeasurements().asFlow().mapToList(Dispatchers.Default)
    fun count() = queries.countMeasurements().executeAsOne()
    fun createReturningId(
        projectId: Long, name: String, shape: String = "rectangle",
        lengthMeters: Double = 0.0, widthMeters: Double = 0.0,
        areaSquareMeters: Double = 0.0, perimeterMeters: Double = 0.0,
        drawingJson: String = "{}", notes: String = "", createdAt: Long
    ): Long = queries.insertMeasurementReturningId(projectId, name, shape, lengthMeters, widthMeters,
        areaSquareMeters, perimeterMeters, drawingJson, notes, createdAt, newRecordUuid(), createdAt).executeAsOne()
    fun find(id: Long) = queries.selectMeasurementById(id).executeAsOneOrNull()
    fun create(
        projectId: Long, name: String, shape: String = "rectangle",
        lengthMeters: Double = 0.0, widthMeters: Double = 0.0,
        areaSquareMeters: Double = 0.0, perimeterMeters: Double = 0.0,
        drawingJson: String = "{}", notes: String = "", createdAt: Long
    ) = queries.insertMeasurement(projectId, name, shape, lengthMeters, widthMeters,
        areaSquareMeters, perimeterMeters, drawingJson, notes, createdAt, newRecordUuid(), createdAt)
    fun update(
        id: Long, name: String, shape: String, lengthMeters: Double, widthMeters: Double,
        areaSquareMeters: Double, perimeterMeters: Double, drawingJson: String, notes: String
    ) = queries.updateMeasurement(name, shape, lengthMeters, widthMeters, areaSquareMeters,
        perimeterMeters, drawingJson, notes, id)
    fun delete(id: Long) = queries.deleteMeasurement(id)
}

class PriceRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun tombstones() = queries.selectDeletedPriceItems().executeAsList()
    fun all() = queries.selectPriceItems().executeAsList()
    fun observeQuery() = queries.selectPriceItems()
    fun observeAll() = queries.selectPriceItems().asFlow().mapToList(Dispatchers.Default)
    fun count() = queries.countPriceItems().executeAsOne()
    fun upsertReturningId(
        id: Long = 0, code: String, title: String, category: String,
        unit: String, price: Double, updatedAt: Long
    ): Long {
        val existingId = if (id > 0L) {
            checkNotNull(find(id)) { "Cannot update missing or deleted price item: $id" }.id
        } else {
            queries.selectActivePriceByKey(category, code).executeAsOneOrNull()?.id
        }
        return if (existingId != null) {
            queries.updatePriceItem(code, title, category, unit, price, updatedAt, existingId)
            existingId
        } else {
            queries.insertPriceItemReturningId(code, title, category, unit, price, updatedAt, newRecordUuid())
                .executeAsOne()
        }
    }
    fun find(id: Long) = queries.selectPriceItemById(id).executeAsOneOrNull()
    fun create(code: String, title: String, category: String, unit: String, price: Double, updatedAt: Long) =
        queries.insertPriceItem(code, title, category, unit, price, updatedAt, newRecordUuid())
    fun update(id: Long, code: String, title: String, category: String, unit: String, price: Double, updatedAt: Long) =
        queries.updatePriceItem(code, title, category, unit, price, updatedAt, id)
    fun delete(id: Long) = queries.deletePriceItem(id)
}

class EstimateRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun forProject(projectId: Long) = queries.selectEstimatesForProject(projectId).executeAsList()
    fun forProjectQuery(projectId: Long) = queries.selectEstimatesForProject(projectId)
    fun observeForProject(projectId: Long) = queries.selectEstimatesForProject(projectId).asFlow().mapToList(Dispatchers.Default)
    fun tombstones() = queries.selectDeletedEstimates().executeAsList()
    fun all() = queries.selectAllEstimates().executeAsList()
    fun observeQuery() = queries.selectAllEstimates()
    fun observeAll() = queries.selectAllEstimates().asFlow().mapToList(Dispatchers.Default)
    fun count() = queries.countEstimates().executeAsOne()
    fun createReturningId(
        projectId: Long, name: String, material: String, profileType: String, complexity: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double, perimeterMeters: Double,
        canvasCost: Double, profileCost: Double, complexityCost: Double, optionsCost: Double,
        totalCost: Double, detailsJson: String = "{}", createdAt: Long
    ): Long = queries.insertEstimateReturningId(projectId, name, material, profileType, complexity,
        lengthMeters, widthMeters, areaSquareMeters, perimeterMeters, canvasCost, profileCost,
        complexityCost, optionsCost, totalCost, detailsJson, createdAt, newRecordUuid(), createdAt).executeAsOne()
    /**
     * Android compatibility write: both the displayed estimate and its permanent
     * number/v1/lines are committed or rolled back together. Callers must supply
     * frozen line-item and measurement inputs; current price list is never read.
     */
    fun createNumberedReturningId(
        projectId: Long, name: String, material: String, profileType: String, complexity: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double, perimeterMeters: Double,
        canvasCost: Double, profileCost: Double, complexityCost: Double, optionsCost: Double,
        totalCost: Double, detailsJson: String, createdAt: Long, year: Int,
        snapshot: ImmutableEstimateDraft,
        newSeriesUuid: () -> String = ::newRecordUuid,
        newRevisionUuid: () -> String = ::newRecordUuid
    ): Long = database.transactionWithResult {
        check(snapshot.detailsJson == detailsJson && snapshot.totalCost == totalCost) {
            "Numbered history must match the frozen estimate exactly"
        }
        val id = createReturningId(projectId, name, material, profileType, complexity,
            lengthMeters, widthMeters, areaSquareMeters, perimeterMeters, canvasCost,
            profileCost, complexityCost, optionsCost, totalCost, detailsJson, createdAt)
        val result = ImmutableEstimateRepository(database).createSeries(
            projectId, year, createdAt, snapshot,
            newSeriesUuid = newSeriesUuid, newRevisionUuid = newRevisionUuid
        )
        queries.linkLegacyEstimateToSeries(result.seriesUuid, id)
        check(find(id)?.seriesUuid == result.seriesUuid) { "Failed to link numbered estimate" }
        id
    }

    fun documentNumberForId(id: Long): String? =
        queries.selectDocumentNumberForEstimateId(id).executeAsOneOrNull()

    fun find(id: Long) = queries.selectEstimateById(id).executeAsOneOrNull()
    fun create(
        projectId: Long, name: String, material: String, profileType: String, complexity: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double, perimeterMeters: Double,
        canvasCost: Double, profileCost: Double, complexityCost: Double, optionsCost: Double,
        totalCost: Double, detailsJson: String = "{}", createdAt: Long
    ) = queries.insertEstimate(projectId, name, material, profileType, complexity, lengthMeters,
        widthMeters, areaSquareMeters, perimeterMeters, canvasCost, profileCost, complexityCost,
        optionsCost, totalCost, detailsJson, createdAt, newRecordUuid(), createdAt)
    fun update(
        id: Long, name: String, material: String, profileType: String, complexity: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double, perimeterMeters: Double,
        canvasCost: Double, profileCost: Double, complexityCost: Double, optionsCost: Double,
        totalCost: Double, detailsJson: String
    ) {
        val stored = checkNotNull(find(id)) { "Cannot update a missing estimate" }
        if (stored.seriesUuid != null) {
            check(stored.material == material && stored.profileType == profileType &&
                stored.complexity == complexity && stored.lengthMeters == lengthMeters &&
                stored.widthMeters == widthMeters && stored.areaSquareMeters == areaSquareMeters &&
                stored.perimeterMeters == perimeterMeters && stored.canvasCost == canvasCost &&
                stored.profileCost == profileCost && stored.complexityCost == complexityCost &&
                stored.optionsCost == optionsCost && stored.totalCost == totalCost &&
                stored.detailsJson == detailsJson
            ) { "Numbered estimate calculations are immutable; create a revision instead" }
            queries.updateEstimateDisplayName(name, id)
        } else {
            queries.updateEstimate(name, material, profileType, complexity, lengthMeters,
                widthMeters, areaSquareMeters, perimeterMeters, canvasCost, profileCost,
                complexityCost, optionsCost, totalCost, detailsJson, id)
        }
    }

    fun delete(id: Long) = database.transaction {
        queries.softDeleteNumberedSeriesForEstimate(id)
        queries.deleteEstimate(id)
    }
}
