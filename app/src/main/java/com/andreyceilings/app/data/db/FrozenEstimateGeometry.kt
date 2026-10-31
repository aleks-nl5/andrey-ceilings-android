package com.andreyceilings.app.data.db

import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.estimate.FrozenCeilingShape
import com.andreyceilings.shared.estimate.FrozenGeometrySnapshotSchema
import com.andreyceilings.shared.estimate.VerifiedCeilingGeometry
import org.json.JSONObject
import kotlin.math.abs

/**
 * Read the original immutable source from SQLDelight, not a mutable measurement
 * or the estimate compatibility row. Used for list duplication and PDF export.
 *
 * Returns null ONLY for a supported rectangle. Unknown/corrupt shapes fail
 * closed, never silently becoming rectangles.
 */
internal fun frozenLShapedGeometryFromSource(
    sourceJson: String,
    precisionStepMeters: Double
): GeometryInput? {
    val source = JSONObject(sourceJson)
    val shape = FrozenGeometrySnapshotSchema.requireShape(
        keys = source.keys().asSequence().toSet(),
        shapeId = if (source.has("shape")) source.getString("shape") else null,
        schemaVersion = if (source.has("geometrySchemaVersion")) {
            source.getLong("geometrySchemaVersion")
        } else null
    )
    if (shape == FrozenCeilingShape.RECTANGLE) return null
    check(shape == FrozenCeilingShape.L_SHAPED)
    val input = VerifiedCeilingGeometry.requireFrozenLShaped(
        GeometryInput(
            shape = shape.wireId,
            sideA = source.getDouble("sideA"),
            sideB = source.getDouble("sideB"),
            sideC = source.getDouble("sideC"),
            sideD = source.getDouble("sideD"),
            sideE = source.getDouble("sideE"),
            sideF = source.getDouble("sideF"),
            precisionStepMeters = precisionStepMeters
        )
    )
    val (area, perimeter) =
        VerifiedCeilingGeometry.checkedFrozenLShapedAreaAndPerimeter(input)
    check(abs(area - source.getDouble("areaSquareMeters")) < 0.000001 &&
        abs(perimeter - source.getDouble("perimeterMeters")) < 0.000001) {
        "Stored L-shaped area or perimeter differs from frozen sides"
    }
    return input
}
