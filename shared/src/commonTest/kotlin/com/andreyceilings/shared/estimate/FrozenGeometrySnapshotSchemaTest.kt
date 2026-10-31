package com.andreyceilings.shared.estimate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrozenGeometrySnapshotSchemaTest {
    private val core = setOf(
        "name", "material", "profileType", "complexity",
        "areaSquareMeters", "perimeterMeters"
    )
    private val metadata = setOf("shape", "geometrySchemaVersion")

    @Test fun versionedRectangleIsAcceptedWithoutShapeSpecificGuesses() {
        val keys = core + metadata + setOf("lengthMeters", "widthMeters")
        assertEquals(
            FrozenCeilingShape.RECTANGLE,
            FrozenGeometrySnapshotSchema.requireShape(
                keys, "rectangle", FrozenGeometrySnapshotSchema.CURRENT_VERSION
            )
        )
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(
                keys - "widthMeters", "rectangle", 1L
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(
                keys + "diagonal1", "rectangle", 1L
            )
        }
    }

    @Test fun versionedLShapeRequiresAllSixFrozenSides() {
        val keys = core + metadata + setOf(
            "sideA", "sideB", "sideC", "sideD", "sideE", "sideF"
        )
        assertEquals(
            FrozenCeilingShape.L_SHAPED,
            FrozenGeometrySnapshotSchema.requireShape(keys, "l_shaped", 1L)
        )
        for (side in FrozenCeilingShape.L_SHAPED.requiredInputFields) {
            assertFailsWith<IllegalArgumentException> {
                FrozenGeometrySnapshotSchema.requireShape(keys - side, "l_shaped", 1L)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(
                keys + "lengthMeters", "l_shaped", 1L
            )
        }
    }

    @Test fun futureGeometryAndUnknownSchemaMustBeRejectedFailClosed() {
        val keys = core + metadata + setOf("lengthMeters", "widthMeters")
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(keys, "polygon", 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(keys, "rectangle", 2L)
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(keys, null, 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            FrozenGeometrySnapshotSchema.requireShape(keys, "rectangle", null)
        }
    }

    @Test fun previouslySavedTestShapesAreReadWithoutMutatingTheirContents() {
        val legacyRectangle = core + setOf("lengthMeters", "widthMeters")
        assertEquals(
            FrozenCeilingShape.RECTANGLE,
            FrozenGeometrySnapshotSchema.requireShape(legacyRectangle, null, null)
        )
        val earlierLShape = core + setOf(
            "shape", "sideA", "sideB", "sideC", "sideD", "sideE", "sideF"
        )
        assertEquals(
            FrozenCeilingShape.L_SHAPED,
            FrozenGeometrySnapshotSchema.requireShape(earlierLShape, "l_shaped", null)
        )
    }
}
