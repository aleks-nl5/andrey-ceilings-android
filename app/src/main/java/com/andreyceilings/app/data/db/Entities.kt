package com.andreyceilings.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ForeignKey

@Entity(tableName = "clients", indices = [Index(value = ["name"])])
data class ClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "projects", indices = [Index(value = ["clientId"]), Index(value = ["status"])])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long?,
    val title: String,
    val address: String = "",
    val status: String = "measurement",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "room_measurements", indices = [Index(value = ["projectId"])])
data class RoomMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val shape: String = "rectangle",
    val lengthMeters: Double = 0.0,
    val widthMeters: Double = 0.0,
    val areaSquareMeters: Double = 0.0,
    val perimeterMeters: Double = 0.0,
    val drawingJson: String = "{}",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "price_items", indices = [Index(value = ["category", "code"], unique = true)])
data class PriceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val title: String,
    val category: String,
    val unit: String,
    val price: Double,
    val updatedAt: Long = System.currentTimeMillis()
)


@Entity(
    tableName = "estimates",
    indices = [Index(value = ["projectId"]), Index(value = ["createdAt"])],
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class EstimateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val material: String,
    val profileType: String,
    val complexity: String,
    val lengthMeters: Double,
    val widthMeters: Double,
    val areaSquareMeters: Double,
    val perimeterMeters: Double,
    val canvasCost: Double,
    val profileCost: Double,
    val complexityCost: Double,
    val optionsCost: Double,
    val totalCost: Double,
    val detailsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)
