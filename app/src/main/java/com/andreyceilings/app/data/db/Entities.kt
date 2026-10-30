package com.andreyceilings.app.data.db

/**
 * Android UI compatibility models. No Room annotations or Android persistence types.
 * The source of truth is the generated SQLDelight schema in :shared.
 */
data class ClientEntity(
    val id: Long = 0,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ProjectEntity(
    val id: Long = 0,
    val clientId: Long?,
    val title: String,
    val address: String = "",
    val status: String = "measurement",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class RoomMeasurementEntity(
    val id: Long = 0,
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

data class PriceItemEntity(
    val id: Long = 0,
    val code: String,
    val title: String,
    val category: String,
    val unit: String,
    val price: Double,
    val updatedAt: Long = System.currentTimeMillis()
)

data class EstimateEntity(
    val id: Long = 0,
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
    val createdAt: Long = System.currentTimeMillis(),
    val documentNumber: String? = null,
    val originalDocumentName: String? = null,
    val printableNumber: String? = null,
    /** Only during first INSERT; the exact geometry is frozen in SQLDelight history. */
    val frozenGeometryInput: com.andreyceilings.shared.calculation.GeometryInput? = null
)
