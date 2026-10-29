package com.andreyceilings.shared.db

/**
 * Shared business-data access, independent of platform SQLCipher drivers.
 * Timestamps are supplied by callers to keep common code deterministic.
 */
class ClientRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun all() = queries.selectClients().executeAsList()
    fun find(id: Long) = queries.selectClientById(id).executeAsOneOrNull()
    fun create(name: String, phone: String = "", email: String = "", notes: String = "", createdAt: Long) {
        queries.insertClient(name, phone, email, notes, createdAt)
    }
    fun update(id: Long, name: String, phone: String, email: String, notes: String) {
        queries.updateClient(name, phone, email, notes, id)
    }
    fun delete(id: Long) = queries.deleteClient(id)
}

class ProjectRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun all() = queries.selectProjects().executeAsList()
    fun find(id: Long) = queries.selectProjectById(id).executeAsOneOrNull()
    fun byClient(clientId: Long) = queries.selectProjectsByClient(clientId).executeAsList()
    fun create(
        clientId: Long?, title: String, address: String = "", status: String = "measurement",
        notes: String = "", createdAt: Long, updatedAt: Long = createdAt
    ) {
        queries.insertProject(clientId, title, address, status, notes, createdAt, updatedAt)
    }
    fun update(
        id: Long, clientId: Long?, title: String, address: String,
        status: String, notes: String, updatedAt: Long
    ) {
        queries.updateProject(clientId, title, address, status, notes, updatedAt, id)
    }
    fun delete(id: Long) = queries.deleteProject(id)
}

class MeasurementRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun forProject(projectId: Long) = queries.selectMeasurementsForProject(projectId).executeAsList()
    fun find(id: Long) = queries.selectMeasurementById(id).executeAsOneOrNull()
    fun create(
        projectId: Long, name: String, shape: String = "rectangle",
        lengthMeters: Double = 0.0, widthMeters: Double = 0.0,
        areaSquareMeters: Double = 0.0, perimeterMeters: Double = 0.0,
        drawingJson: String = "{}", notes: String = "", createdAt: Long
    ) = queries.insertMeasurement(projectId, name, shape, lengthMeters, widthMeters,
        areaSquareMeters, perimeterMeters, drawingJson, notes, createdAt)
    fun update(
        id: Long, name: String, shape: String, lengthMeters: Double, widthMeters: Double,
        areaSquareMeters: Double, perimeterMeters: Double, drawingJson: String, notes: String
    ) = queries.updateMeasurement(name, shape, lengthMeters, widthMeters, areaSquareMeters,
        perimeterMeters, drawingJson, notes, id)
    fun delete(id: Long) = queries.deleteMeasurement(id)
}

class PriceRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun all() = queries.selectPriceItems().executeAsList()
    fun find(id: Long) = queries.selectPriceItemById(id).executeAsOneOrNull()
    fun create(code: String, title: String, category: String, unit: String, price: Double, updatedAt: Long) =
        queries.insertPriceItem(code, title, category, unit, price, updatedAt)
    fun update(id: Long, code: String, title: String, category: String, unit: String, price: Double, updatedAt: Long) =
        queries.updatePriceItem(code, title, category, unit, price, updatedAt, id)
    fun delete(id: Long) = queries.deletePriceItem(id)
}

class EstimateRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries

    fun forProject(projectId: Long) = queries.selectEstimatesForProject(projectId).executeAsList()
    fun find(id: Long) = queries.selectEstimateById(id).executeAsOneOrNull()
    fun create(
        projectId: Long, name: String, material: String, profileType: String, complexity: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double, perimeterMeters: Double,
        canvasCost: Double, profileCost: Double, complexityCost: Double, optionsCost: Double,
        totalCost: Double, detailsJson: String = "{}", createdAt: Long
    ) = queries.insertEstimate(projectId, name, material, profileType, complexity, lengthMeters,
        widthMeters, areaSquareMeters, perimeterMeters, canvasCost, profileCost, complexityCost,
        optionsCost, totalCost, detailsJson, createdAt)
    fun update(
        id: Long, name: String, material: String, profileType: String, complexity: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double, perimeterMeters: Double,
        canvasCost: Double, profileCost: Double, complexityCost: Double, optionsCost: Double,
        totalCost: Double, detailsJson: String
    ) = queries.updateEstimate(name, material, profileType, complexity, lengthMeters,
        widthMeters, areaSquareMeters, perimeterMeters, canvasCost, profileCost, complexityCost,
        optionsCost, totalCost, detailsJson, id)
    fun delete(id: Long) = queries.deleteEstimate(id)
}
