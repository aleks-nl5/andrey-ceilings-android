package com.andreyceilings.shared

import com.andreyceilings.shared.domain.Client
import com.andreyceilings.shared.domain.DomainValidation
import com.andreyceilings.shared.domain.Project
import com.andreyceilings.shared.domain.RoomMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainModelsTest {
    @Test
    fun validClientAndProjectHaveNoValidationErrors() {
        val client = Client(id = 1, name = "Олена", createdAtEpochMillis = 100L)
        val project = Project(
            id = 2,
            clientId = client.id,
            title = "Квартира",
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 200L
        )

        assertTrue(DomainValidation.validateClient(client).isEmpty())
        assertTrue(DomainValidation.validateProject(project).isEmpty())
    }

    @Test
    fun blankClientNameAndProjectTitleAreRejected() {
        assertEquals(
            listOf("Client name must not be blank"),
            DomainValidation.validateClient(Client(id = 1, name = "  ", createdAtEpochMillis = 0L))
        )
        assertEquals(
            listOf("Project title must not be blank"),
            DomainValidation.validateProject(
                Project(id = 2, clientId = null, title = "", createdAtEpochMillis = 0L, updatedAtEpochMillis = 0L)
            )
        )
    }

    @Test
    fun invalidMeasurementDimensionsAreRejectedWithoutCalculatingGeometry() {
        val measurement = RoomMeasurement(
            id = 1,
            projectId = 2,
            name = "Кімната",
            lengthMeters = Double.NaN,
            widthMeters = -1.0,
            areaSquareMeters = 20.0,
            perimeterMeters = 15.0,
            createdAtEpochMillis = 0L
        )

        assertEquals(
            listOf(
                "Length must be finite and non-negative",
                "Width must be finite and non-negative"
            ),
            DomainValidation.validateRoomMeasurement(measurement)
        )
        // Area/perimeter are preserved as supplied; the shared KMP calculator owns geometry.
        assertEquals(20.0, measurement.areaSquareMeters)
        assertEquals(15.0, measurement.perimeterMeters)
    }

    @Test
    fun measurementRejectsMissingProjectAndBlankName() {
        val measurement = RoomMeasurement(
            id = 1,
            projectId = 0,
            name = " ",
            createdAtEpochMillis = 0L
        )

        assertEquals(
            listOf("Project ID must be positive", "Measurement name must not be blank"),
            DomainValidation.validateRoomMeasurement(measurement)
        )
    }
}
