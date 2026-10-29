package com.andreyceilings.app.data.db

import com.andreyceilings.shared.db.ClientRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.EstimateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Screen-facing adapters retained temporarily so all existing Compose UI can
 * migrate without changing its business models. The storage and observables
 * are entirely SQLDelight; these are NOT Room DAOs.
 */
internal class ClientDao(private val repo: ClientRepository) {
    fun observeAll(): Flow<List<ClientEntity>> = repo.observeAll().map { rows ->
        rows.map { ClientEntity(it.id, it.name, it.phone, it.email, it.notes, it.createdAt) }
    }
    suspend fun count() = withContext(Dispatchers.IO) { repo.count().toInt() }
    suspend fun findById(id: Long) = withContext(Dispatchers.IO) {
        repo.find(id)?.let { ClientEntity(it.id, it.name, it.phone, it.email, it.notes, it.createdAt) }
    }
    suspend fun findByName(name: String) = withContext(Dispatchers.IO) {
        repo.findByName(name)?.let { ClientEntity(it.id, it.name, it.phone, it.email, it.notes, it.createdAt) }
    }
    suspend fun insert(item: ClientEntity) = withContext(Dispatchers.IO) {
        repo.createReturningId(item.name, item.phone, item.email, item.notes, item.createdAt)
    }
    suspend fun update(item: ClientEntity) = withContext(Dispatchers.IO) {
        repo.update(item.id, item.name, item.phone, item.email, item.notes)
    }
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { repo.delete(id) }
}

internal class ProjectDao(private val repo: ProjectRepository) {
    fun observeAll(): Flow<List<ProjectEntity>> = repo.observeAll().map { rows ->
        rows.map { ProjectEntity(it.id, it.clientId, it.title, it.address, it.status, it.notes, it.createdAt, it.updatedAt) }
    }
    suspend fun count() = withContext(Dispatchers.IO) { repo.count().toInt() }
    suspend fun findById(id: Long) = withContext(Dispatchers.IO) {
        repo.find(id)?.let { ProjectEntity(it.id, it.clientId, it.title, it.address, it.status, it.notes, it.createdAt, it.updatedAt) }
    }
    suspend fun insert(item: ProjectEntity) = withContext(Dispatchers.IO) {
        repo.createReturningId(item.clientId, item.title, item.address, item.status, item.notes, item.createdAt, item.updatedAt)
    }
    suspend fun update(item: ProjectEntity) = withContext(Dispatchers.IO) {
        repo.update(item.id, item.clientId, item.title, item.address, item.status, item.notes, item.updatedAt)
    }
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { repo.updateStatus(id, status, updatedAt) }
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { repo.delete(id) }
}

internal class RoomMeasurementDao(private val repo: MeasurementRepository) {
    fun observeAll(): Flow<List<RoomMeasurementEntity>> = repo.observeAll().map { rows ->
        rows.map { RoomMeasurementEntity(it.id, it.projectId, it.name, it.shape,
            it.lengthMeters, it.widthMeters, it.areaSquareMeters, it.perimeterMeters,
            it.drawingJson, it.notes, it.createdAt) }
    }
    fun observeForProject(projectId: Long): Flow<List<RoomMeasurementEntity>> =
        repo.observeForProject(projectId).map { rows ->
            rows.map { RoomMeasurementEntity(it.id, it.projectId, it.name, it.shape,
                it.lengthMeters, it.widthMeters, it.areaSquareMeters, it.perimeterMeters,
                it.drawingJson, it.notes, it.createdAt) }
        }
    suspend fun count() = withContext(Dispatchers.IO) { repo.count().toInt() }
    suspend fun insert(item: RoomMeasurementEntity) = withContext(Dispatchers.IO) {
        repo.createReturningId(item.projectId, item.name, item.shape, item.lengthMeters,
            item.widthMeters, item.areaSquareMeters, item.perimeterMeters,
            item.drawingJson, item.notes, item.createdAt)
    }
    suspend fun update(item: RoomMeasurementEntity) = withContext(Dispatchers.IO) {
        repo.update(item.id, item.name, item.shape, item.lengthMeters, item.widthMeters,
            item.areaSquareMeters, item.perimeterMeters, item.drawingJson, item.notes)
    }
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { repo.delete(id) }
}

internal class PriceItemDao(private val repo: PriceRepository) {
    fun observeAll(): Flow<List<PriceItemEntity>> = repo.observeAll().map { rows ->
        rows.map { PriceItemEntity(it.id, it.code, it.title, it.category, it.unit, it.price, it.updatedAt) }
    }
    suspend fun count() = withContext(Dispatchers.IO) { repo.count().toInt() }
    suspend fun findById(id: Long) = withContext(Dispatchers.IO) {
        repo.find(id)?.let { PriceItemEntity(it.id, it.code, it.title, it.category, it.unit, it.price, it.updatedAt) }
    }
    suspend fun upsert(item: PriceItemEntity) = withContext(Dispatchers.IO) {
        repo.upsertReturningId(item.id, item.code, item.title, item.category, item.unit, item.price, item.updatedAt)
    }
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { repo.delete(id) }
}

internal class EstimateDao(private val repo: EstimateRepository) {
    fun observeAll(): Flow<List<EstimateEntity>> = repo.observeAll().map { rows ->
        rows.map { EstimateEntity(it.id, it.projectId, it.name, it.material, it.profileType,
            it.complexity, it.lengthMeters, it.widthMeters, it.areaSquareMeters,
            it.perimeterMeters, it.canvasCost, it.profileCost, it.complexityCost,
            it.optionsCost, it.totalCost, it.detailsJson, it.createdAt) }
    }
    fun observeForProject(projectId: Long): Flow<List<EstimateEntity>> =
        repo.observeForProject(projectId).map { rows ->
            rows.map { EstimateEntity(it.id, it.projectId, it.name, it.material, it.profileType,
                it.complexity, it.lengthMeters, it.widthMeters, it.areaSquareMeters,
                it.perimeterMeters, it.canvasCost, it.profileCost, it.complexityCost,
                it.optionsCost, it.totalCost, it.detailsJson, it.createdAt) }
        }
    suspend fun count() = withContext(Dispatchers.IO) { repo.count().toInt() }
    suspend fun insert(item: EstimateEntity) = withContext(Dispatchers.IO) {
        repo.createReturningId(item.projectId, item.name, item.material, item.profileType,
            item.complexity, item.lengthMeters, item.widthMeters, item.areaSquareMeters,
            item.perimeterMeters, item.canvasCost, item.profileCost, item.complexityCost,
            item.optionsCost, item.totalCost, item.detailsJson, item.createdAt)
    }
    suspend fun update(item: EstimateEntity) = withContext(Dispatchers.IO) {
        repo.update(item.id, item.name, item.material, item.profileType,
            item.complexity, item.lengthMeters, item.widthMeters, item.areaSquareMeters,
            item.perimeterMeters, item.canvasCost, item.profileCost, item.complexityCost,
            item.optionsCost, item.totalCost, item.detailsJson)
    }
    suspend fun deleteById(id: Long) = withContext(Dispatchers.IO) { repo.delete(id) }
}
