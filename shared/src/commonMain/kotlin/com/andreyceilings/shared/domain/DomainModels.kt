package com.andreyceilings.shared.domain

/**
 * Platform-independent domain representation of a client.
 *
 * This is deliberately separate from the Android Room entity. Persistence and
 * mapping stay in the Android app until a later, explicitly tested migration.
 */
data class Client(
    val id: Long,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val createdAtEpochMillis: Long
)

/** Platform-independent representation of a project/object. */
data class Project(
    val id: Long,
    val clientId: Long?,
    val title: String,
    val address: String = "",
    val status: String = "measurement",
    val notes: String = "",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val paymentStatus: String = ProjectPaymentStatus.UNPAID.wireId
)

/**
 * User-entered room measurement data.
 *
 * Geometry is intentionally not calculated here: Go remains the calculation
 * source of truth while the cross-platform implementation is being validated.
 */
data class RoomMeasurement(
    val id: Long,
    val projectId: Long,
    val name: String,
    val shape: String = "rectangle",
    val lengthMeters: Double = 0.0,
    val widthMeters: Double = 0.0,
    val areaSquareMeters: Double = 0.0,
    val perimeterMeters: Double = 0.0,
    val drawingJson: String = "{}",
    val notes: String = "",
    val createdAtEpochMillis: Long
)

/** Small pure validation helpers shared by all targets. */
object DomainValidation {
    fun validateClient(client: Client): List<String> = buildList {
        if (client.name.isBlank()) add("Client name must not be blank")
    }

    fun validateProject(project: Project): List<String> = buildList {
        if (project.title.isBlank()) add("Project title must not be blank")
        if (project.clientId != null && project.clientId <= 0L) {
            add("Client ID must be positive when provided")
        }
    }

    fun validateRoomMeasurement(measurement: RoomMeasurement): List<String> = buildList {
        if (measurement.projectId <= 0L) add("Project ID must be positive")
        if (measurement.name.isBlank()) add("Measurement name must not be blank")
        if (!measurement.lengthMeters.isFinite() || measurement.lengthMeters < 0.0) {
            add("Length must be finite and non-negative")
        }
        if (!measurement.widthMeters.isFinite() || measurement.widthMeters < 0.0) {
            add("Width must be finite and non-negative")
        }
        if (!measurement.areaSquareMeters.isFinite() || measurement.areaSquareMeters < 0.0) {
            add("Area must be finite and non-negative")
        }
        if (!measurement.perimeterMeters.isFinite() || measurement.perimeterMeters < 0.0) {
            add("Perimeter must be finite and non-negative")
        }
    }
}
