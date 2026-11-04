package com.andreyceilings.shared.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.prices.QuantityMode
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.PriceCatalogConflict
import com.andreyceilings.shared.prices.PriceParameters
import com.andreyceilings.shared.prices.normalizedNameKey
import com.andreyceilings.shared.prices.DuplicateItemTitle
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
    fun updatePaymentStatus(id: Long, paymentStatus: String, updatedAt: Long) {
        require(com.andreyceilings.shared.domain.ProjectStatusRules.isKnownPaymentStatus(paymentStatus)) {
            "Unknown payment status"
        }
        queries.updateProjectPaymentStatus(paymentStatus, updatedAt, id)
    }
    fun createReturningId(
        clientId: Long?, title: String, address: String = "", status: String = "measurement",
        notes: String = "", createdAt: Long, updatedAt: Long = createdAt
    ): Long = queries.insertProjectReturningId(clientId, title, address, status, notes, createdAt, updatedAt, newRecordUuid()).executeAsOne()
    fun find(id: Long) = queries.selectProjectById(id).executeAsOneOrNull()
    fun observeById(id: Long) = queries.selectProjectById(id).asFlow().mapToOneOrNull(Dispatchers.Default)
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
    fun findByUuid(uuid: String) = queries.selectMeasurementByUuid(uuid).executeAsOneOrNull()
    fun createReturningId(
        projectId: Long, name: String, shape: String = "rectangle",
        lengthMeters: Double = 0.0, widthMeters: Double = 0.0,
        areaSquareMeters: Double = 0.0, perimeterMeters: Double = 0.0,
        drawingJson: String = "{}", notes: String = "", createdAt: Long,
        recordUuid: String = newRecordUuid()
    ): Long = queries.insertMeasurementReturningId(projectId, name, shape, lengthMeters, widthMeters,
        areaSquareMeters, perimeterMeters, drawingJson, notes, createdAt, recordUuid, createdAt).executeAsOne()
    fun observeById(id: Long) = queries.selectMeasurementById(id).asFlow().mapToOneOrNull(Dispatchers.Default)
    fun find(id: Long) = queries.selectMeasurementById(id).executeAsOneOrNull()
    fun create(
        projectId: Long, name: String, shape: String = "rectangle",
        lengthMeters: Double = 0.0, widthMeters: Double = 0.0,
        areaSquareMeters: Double = 0.0, perimeterMeters: Double = 0.0,
        drawingJson: String = "{}", notes: String = "", createdAt: Long
    ) = queries.insertMeasurement(projectId, name, shape, lengthMeters, widthMeters,
        areaSquareMeters, perimeterMeters, drawingJson, notes, createdAt, newRecordUuid(), createdAt)
    /** Atomic CAS: immutable identity/ownership columns are deliberately absent from SET. */
    fun updateIfUnchanged(
        id: Long, projectId: Long, expectedUpdatedAt: Long, name: String, shape: String,
        lengthMeters: Double, widthMeters: Double, areaSquareMeters: Double,
        perimeterMeters: Double, drawingJson: String, notes: String, nowMillis: Long
    ): Long? = queries.updateMeasurementIfVersion(name, shape, lengthMeters, widthMeters,
        areaSquareMeters, perimeterMeters, drawingJson, notes, nowMillis, id, projectId,
        expectedUpdatedAt).executeAsOneOrNull()

    fun update(
        id: Long, name: String, shape: String, lengthMeters: Double, widthMeters: Double,
        areaSquareMeters: Double, perimeterMeters: Double, drawingJson: String, notes: String
    ) = queries.updateMeasurement(name, shape, lengthMeters, widthMeters, areaSquareMeters,
        perimeterMeters, drawingJson, notes, id)
    fun delete(id: Long) = database.transaction {
        database.measurementElementsQueries.softDeleteForMeasurement(id)
        queries.deleteMeasurement(id)
    }
}

class PriceRepository(private val database: PrototypeDatabase) {
    private val queries = database.businessSchemaQueries
    internal val groups = PriceGroupRepository(database)
    private val parameters = database.priceGroupsQueries

    fun tombstones() = queries.selectDeletedPriceItems().executeAsList()
    fun all() = queries.selectPriceItems().executeAsList()
    fun observeQuery() = queries.selectPriceItems()
    fun observeAll() = queries.selectPriceItems().asFlow().mapToList(Dispatchers.Default)
    fun count() = queries.countPriceItems().executeAsOne()
    fun find(id: Long) = queries.selectPriceItemById(id).executeAsOneOrNull()

    /** Copy defaults once, with independent overrides. No automatic quantity/price calculation. */
    fun createInGroup(
        code: String, title: String, groupUuid: String, price: Double, updatedAt: Long,
        unit: String? = null,
        quantityMode: QuantityMode? = null,
        placementKind: PlacementKind? = null
    ): Long = database.transactionWithResult {
        parameters.claimCatalogWrite()
        val group = groups.find(groupUuid) ?: throw PriceCatalogConflict()
        val ownUnit = (unit ?: group.defaultUnit)?.trim()
        require(!ownUnit.isNullOrBlank()) { "Для групи без типової одиниці вкажіть одиницю позиції." }
        val mode = quantityMode ?: QuantityMode.entries.single { it.value == group.defaultQuantityMode }
        val placement = placementKind ?: PlacementKind.entries.single { it.value == group.defaultPlacementKind }
        validate(code, title, ownUnit, price, mode)
        checkNotNull(parameters.insertConfiguredItem(code, title.trim(), normalizedNameKey(title), groupUuid, ownUnit,
            mode.value, placement.value, price, updatedAt, newRecordUuid()).executeAsOneOrNull()) {
            if (parameters.selectItemByTitleKey(normalizedNameKey(title)).executeAsOneOrNull() != null)
                throw DuplicateItemTitle()
            throw PriceCatalogConflict()
        }
    }

    fun updateConfigured(
        original: Price_catalog_items, groupUuid: String, title: String, unit: String,
        quantityMode: QuantityMode,
        placementKind: PlacementKind, price: Double, now: Long
    ): Long {
        validate(original.code, title, unit, price, quantityMode)
        return database.transactionWithResult {
            val written = parameters.updateConfiguredItemIfVersion(title.trim(), normalizedNameKey(title), groupUuid, unit.trim(),
                quantityMode.value, placementKind.value, price, now, original.id, original.uuid, original.code,
                original.updatedAt).executeAsOneOrNull()
            if (written == null) {
                val current = find(original.id)
                check(current != null && current.uuid == original.uuid && current.code == original.code &&
                    current.updatedAt == original.updatedAt && groups.find(groupUuid) != null) {
                    throw PriceCatalogConflict()
                }
                if (parameters.selectItemByTitleKey(normalizedNameKey(title)).executeAsOneOrNull()?.id?.let { it != original.id } == true)
                    throw DuplicateItemTitle()
                throw PriceCatalogConflict()
            }
            written
        }
    }

    /** CAS-protected quick-price never rewrites labels, membership or parameters. */
    fun updateSalePrice(original: Price_catalog_items, price: Double, now: Long): Long {
        require(price.isFinite() && price >= 0) { "Неприпустима ціна продажу." }
        return parameters.updateSalePriceIfVersion(price, now, original.id, original.uuid, original.code,
            original.updatedAt).executeAsOneOrNull() ?: throw PriceCatalogConflict()
    }

    /** Moving changes membership only; all item parameters and identity survive. */
    fun move(original: Price_catalog_items, groupUuid: String, now: Long): Long =
        checkNotNull(parameters.moveItemIfVersion(groupUuid, now, original.id, original.uuid,
            original.updatedAt).executeAsOneOrNull()) { throw PriceCatalogConflict() }

    // Compatibility boundary for the existing CMP/Android editor. New rows use
    // group defaults, with the unit explicitly chosen in that editor.
    fun upsertReturningId(
        id: Long = 0, code: String, title: String, category: String,
        unit: String, price: Double, updatedAt: Long
    ): Long {
        val existing = if (id > 0L) checkNotNull(find(id)) {
            "Cannot update missing or deleted price item: $id"
        } else queries.selectActivePriceByKey(normalizedNameKey(category), code).executeAsOneOrNull()
        return if (existing != null) {
            require(existing.code == code) { "Price code is immutable" }
            updateIfUnchanged(existing.id, code, existing.updatedAt, title, category, unit, price, updatedAt)
        } else database.transactionWithResult {
            createInGroup(code, title, groups.ensureByName(category, updatedAt), price, updatedAt, unit)
        }
    }

    /** CAS also protects fields absent from the P1 compatibility UI. */
    fun updateIfUnchanged(
        id: Long, expectedCode: String, expectedUpdatedAt: Long,
        title: String, category: String, unit: String, price: Double, updatedAt: Long,
        unchangedGroupUuid: String? = null
    ): Long {
        // Read outside a deferred write transaction. Concurrent JDBC callers
        // must reach the atomic UPDATE, rather than fail while upgrading two
        // shared read locks to writer locks. SQL CAS remains authoritative.
        val original = checkNotNull(find(id)) { "Price item changed or deleted; reload before saving." }
        check(original.code == expectedCode && original.updatedAt == expectedUpdatedAt) {
            "Price item changed or deleted; reload before saving."
        }
        fun write(groupUuid: String) = updateConfigured(original, groupUuid, title, unit,
            QuantityMode.entries.single { it.value == original.quantityMode },
            PlacementKind.entries.single { it.value == original.placementKind }, price, updatedAt)
        val target = unchangedGroupUuid ?: groups.findByName(category)?.uuid
        return if (target != null) write(target) else database.transactionWithResult {
            parameters.claimCatalogWrite()
            check(find(id)?.let { it.uuid == original.uuid && it.updatedAt == expectedUpdatedAt } == true) {
                "Price item changed or deleted; reload before saving."
            }
            // INSERT acquires the writer lock before the CAS. If it loses, the
            // new category is rolled back along with the failed item edit.
            write(groups.ensureByName(category, updatedAt))
        }
    }

    fun deleteIfUnchanged(id: Long, expectedCode: String, expectedUpdatedAt: Long): Long =
        checkNotNull(queries.deletePriceItemIfVersion(id, expectedCode, expectedUpdatedAt).executeAsOneOrNull()) {
            throw PriceCatalogConflict()
        }

    fun create(code: String, title: String, category: String, unit: String, price: Double, updatedAt: Long) {
        database.transaction {
            // Legacy callers explicitly supply their own unit and manual basis.
            createInGroup(code, title, groups.ensureByName(category, updatedAt), price, updatedAt,
                unit, QuantityMode.MANUAL)
        }
    }
    fun update(id: Long, code: String, title: String, category: String, unit: String, price: Double, updatedAt: Long) {
        val original = checkNotNull(find(id)) { "Price item missing" }
        updateIfUnchanged(id, code, original.updatedAt, title, category, unit, price, updatedAt)
    }
    fun delete(id: Long) = queries.deletePriceItem(id)

    private fun validate(code: String, title: String, unit: String, price: Double,
        mode: QuantityMode) {
        require(code.isNotBlank() && title.isNotBlank()) { "Вкажіть код і назву позиції." }
        require(price.isFinite() && price >= 0) { "Неприпустима ціна продажу." }
        PriceParameters.validate(unit.trim(), mode)
    }
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
    fun printableNumberForId(id: Long): String? =
        queries.selectPrintableNumberIdentityForEstimateId(id).executeAsOneOrNull()?.let {
            com.andreyceilings.shared.estimate.EstimateNumbering.printable(
                it.documentNumber, it.localSequence, it.policyPrefix, it.policySequenceWidth.toInt()
            )
        }

    fun originalRevisionInputForId(id: Long): String? =
        queries.selectFirstRevisionInputForEstimateId(id).executeAsOneOrNull()

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
