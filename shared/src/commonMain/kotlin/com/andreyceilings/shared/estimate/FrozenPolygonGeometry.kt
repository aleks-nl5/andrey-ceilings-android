package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.CeilingPolygon
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.calculation.RoomGeometry
import kotlin.math.abs

/**
 * Version 2 immutable, platform-independent geometry for straight-edged rooms.
 *
 * Coordinates and captured template parameters are integers in millimetres.
 * Shape-specific UI does NOT affect saved documents. Future contour formats
 * (holes/arcs) must use a NEW schema and calculation-rule version.
 */
data class FrozenPolygonGeometry(
    val shapeType: String,
    val vertices: List<CeilingPointMm>,
    val measurementsMm: Map<String, Long>,
    val precisionStepMeters: Double,
    val areaSquareMeters: Double,
    val perimeterMeters: Double
)

object FrozenPolygonGeometryContract {
    const val SCHEMA_VERSION = 2L
    const val CALCULATION_RULE_VERSION = "kmp-polygon-v1"
    val sourceFields: Set<String> = setOf(
        "name", "material", "profileType", "complexity",
        "shape", "geometrySchemaVersion", "vertices",
        "measurementsMm", "areaSquareMeters", "perimeterMeters"
    )

    fun capture(
        shapeType: String,
        vertices: List<CeilingPointMm>,
        measurementsMm: Map<String, Long>,
        precisionStepMeters: Double
    ): FrozenPolygonGeometry {
        val geometry = CeilingPolygonCalculator.calculate(
            CeilingPolygon(shapeType, vertices), precisionStepMeters
        ).geometry
        return requireValid(FrozenPolygonGeometry(
            shapeType, vertices.toList(), measurementsMm.toMap(),
            precisionStepMeters, geometry.area, geometry.perimeter
        ))
    }

    fun requireValid(frozen: FrozenPolygonGeometry): FrozenPolygonGeometry {
        val precision = requireNotNull(
            MeasurementPrecision.fromStep(frozen.precisionStepMeters)
        ) { "Unknown frozen polygon precision" }
        val stepMm = (precision.stepMeters * 1000.0).toLong()
        require(stepMm > 0L) { "Invalid millimetre step" }
        require(frozen.measurementsMm.values.all { value ->
            value > 0L && value <= CeilingPolygonCalculator.MAX_COORDINATE_MM &&
                value % stepMm == 0L
        }) { "Invalid frozen template measurements" }
        val template = CeilingShapeTemplates.template(frozen.shapeType)
        if (template == null) {
            require(frozen.shapeType == "polygon" && frozen.measurementsMm.isEmpty()) {
                "Unregistered ceiling shape requires a tested template"
            }
        } else {
            require(frozen.measurementsMm.keys == template.parameterKeys.toSet()) {
                "Frozen template dimensions are incomplete or unexpected"
            }
            val rebuilt = template.build(frozen.measurementsMm)
            require(rebuilt.vertices == frozen.vertices) {
                "Frozen vertices must exactly match their original measured template"
            }
        }
        val current: RoomGeometry = CeilingPolygonCalculator.calculate(
            CeilingPolygon(frozen.shapeType, frozen.vertices),
            precision.stepMeters
        ).geometry
        require(frozen.areaSquareMeters.isFinite() &&
            frozen.perimeterMeters.isFinite() &&
            frozen.areaSquareMeters > 0.0 && frozen.perimeterMeters > 0.0 &&
            abs(current.area - frozen.areaSquareMeters) < 0.000001 &&
            abs(current.perimeter - frozen.perimeterMeters) < 0.000001) {
            "Saved polygon area/perimeter cannot be reproduced"
        }
        return frozen
    }
}
