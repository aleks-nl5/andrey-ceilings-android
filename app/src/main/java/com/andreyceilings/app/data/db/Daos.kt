package com.andreyceilings.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<ClientEntity>>
    @Query("SELECT * FROM clients WHERE id = :id LIMIT 1") suspend fun findById(id: Long): ClientEntity?
    @Query("SELECT * FROM clients WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun findByName(name: String): ClientEntity?
    @Insert suspend fun insert(client: ClientEntity): Long
    @Update suspend fun update(client: ClientEntity)
    @Query("DELETE FROM clients WHERE id = :id") suspend fun deleteById(id: Long)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1") suspend fun findById(id: Long): ProjectEntity?
    @Insert suspend fun insert(project: ProjectEntity): Long
    @Update suspend fun update(project: ProjectEntity)
    @Query("UPDATE projects SET status = :status, updatedAt = :updatedAt WHERE id = :id") suspend fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteById(id: Long)
}

@Dao
interface RoomMeasurementDao {
    @Query("SELECT * FROM room_measurements ORDER BY createdAt DESC") fun observeAll(): Flow<List<RoomMeasurementEntity>>
    @Query("SELECT * FROM room_measurements WHERE projectId = :projectId ORDER BY createdAt DESC") fun observeForProject(projectId: Long): Flow<List<RoomMeasurementEntity>>
    @Insert suspend fun insert(measurement: RoomMeasurementEntity): Long
    @Update suspend fun update(measurement: RoomMeasurementEntity)
    @Query("DELETE FROM room_measurements WHERE id = :id") suspend fun deleteById(id: Long)
}

@Dao
interface PriceItemDao {
    @Query("SELECT * FROM price_items ORDER BY category, title") fun observeAll(): Flow<List<PriceItemEntity>>
    @Query("SELECT COUNT(*) FROM price_items") suspend fun count(): Int
    @Query("SELECT * FROM price_items WHERE id = :id LIMIT 1") suspend fun findById(id: Long): PriceItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: PriceItemEntity): Long
    @Query("DELETE FROM price_items WHERE id = :id") suspend fun deleteById(id: Long)
}

@Dao
interface EstimateDao {
    @Query("SELECT * FROM estimates ORDER BY createdAt DESC") fun observeAll(): Flow<List<EstimateEntity>>
    @Query("SELECT * FROM estimates WHERE projectId = :projectId ORDER BY createdAt DESC") fun observeForProject(projectId: Long): Flow<List<EstimateEntity>>
    @Insert suspend fun insert(estimate: EstimateEntity): Long
    @Update suspend fun update(estimate: EstimateEntity)
    @Query("DELETE FROM estimates WHERE id = :id") suspend fun deleteById(id: Long)
}
