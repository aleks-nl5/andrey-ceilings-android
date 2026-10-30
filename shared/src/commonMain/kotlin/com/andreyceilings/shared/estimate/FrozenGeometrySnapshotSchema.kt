package com.andreyceilings.shared.estimate

/**
 * Explicit, versioned measurement fields stored in an immutable estimate.
 *
 * A new ceiling shape extends this registry with its own mandatory captured
 * inputs and a tested KMP replay implementation. Never derive missing sides
 * from a stored area/perimeter or a live mutable measurement.
 *
 * Coordinates of a future polygon or curved contour may be nested in a
 * shape-specific field; that field must be validated by its own KMP replay
 * contract before it can be priced. Listing a shape here alone is NOT enough.
 */
enum class FrozenCeilingShape(
    val wireId: String,
    val requiredInputFields: Set<String>
) {
    RECTANGLE("rectangle", setOf("lengthMeters", "widthMeters")),
    L_SHAPED(
        "l_shaped",
        setOf("sideA", "sideB", "sideC", "sideD", "sideE", "sideF")
    );

    companion object {
        fun fromWireId(value: String): FrozenCeilingShape? =
            entries.firstOrNull { it.wireId == value }
    }
}

object FrozenGeometrySnapshotSchema {
    const val CURRENT_VERSION: Long = 1L

    private val commonFields = setOf(
        "name", "material", "profileType", "complexity",
        "areaSquareMeters", "perimeterMeters"
    )

    /**
     * Exact-key validation for frozen input. New estimates carry an explicit
     * shape and schema version. Only already-written test snapshots can use
     * the former unversioned rectangle / complete L-shaped layout.
     *
     * This function validates the schema (not numeric values). Actual side
     * lengths, closure, areas, precision and costs must also be verified by
     * the shape-specific KMP candidate before appending a new revision.
     */
    fun requireShape(
        keys: Set<String>,
        shapeId: String?,
        schemaVersion: Long?
    ): FrozenCeilingShape {
        val versioned = schemaVersion != null
        if (versioned) {
            require(schemaVersion == CURRENT_VERSION) {
                "Unsupported frozen geometry schema version"
            }
        } else {
            require(shapeId == null || shapeId == FrozenCeilingShape.L_SHAPED.wireId) {
                "Unversioned geometry input is not a supported historical snapshot"
            }
        }
        val shape = if (shapeId == null && !versioned) {
            FrozenCeilingShape.RECTANGLE
        } else {
            requireNotNull(shapeId?.let(FrozenCeilingShape::fromWireId)) {
                "Unknown or missing frozen ceiling shape"
            }
        }
        val metadata = when {
            versioned -> setOf("shape", "geometrySchemaVersion")
            shapeId != null -> setOf("shape")
            else -> emptySet()
        }
        require(keys == commonFields + shape.requiredInputFields + metadata) {
            "Incomplete or unrecognized frozen geometry fields"
        }
        return shape
    }
}
