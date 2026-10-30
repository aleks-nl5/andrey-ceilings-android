package com.andreyceilings.shared.calculation

import kotlin.math.abs
import kotlin.math.sqrt

/** Millimetres avoid geometry depending on floating-point equality or Android. */
data class CeilingPointMm(val xMm: Long, val yMm: Long)

/**
 * An ordered simple, closed-by-implication polygon. Do NOT repeat the first
 * vertex at the end. Input measurements remain separate from these vertices
 * in an immutable estimate snapshot.
 */
data class CeilingPolygon(
    val shapeType: String,
    val vertices: List<CeilingPointMm>
)

data class CeilingPolygonResult(
    val geometry: RoomGeometry,
    val twiceAreaSquareMm: Long
)

/**
 * One calculation for all straight-edged ceiling forms. Long arithmetic is
 * provably safe with the chosen coordinate/vertex bounds. Every edge,
 * including the last-to-first closing edge, is checked.
 */
object CeilingPolygonCalculator {
    const val MAX_VERTICES = 128
    const val MAX_COORDINATE_MM = 1_000_000L

    private fun cross(a: CeilingPointMm, b: CeilingPointMm, c: CeilingPointMm): Long =
        (b.xMm - a.xMm) * (c.yMm - a.yMm) -
            (b.yMm - a.yMm) * (c.xMm - a.xMm)

    private fun between(value: Long, a: Long, b: Long): Boolean =
        value >= minOf(a, b) && value <= maxOf(a, b)

    private fun onSegment(a: CeilingPointMm, b: CeilingPointMm, c: CeilingPointMm): Boolean =
        cross(a, b, c) == 0L && between(c.xMm, a.xMm, b.xMm) &&
            between(c.yMm, a.yMm, b.yMm)

    private fun intersects(
        a: CeilingPointMm, b: CeilingPointMm,
        c: CeilingPointMm, d: CeilingPointMm
    ): Boolean {
        val abC = cross(a, b, c)
        val abD = cross(a, b, d)
        val cdA = cross(c, d, a)
        val cdB = cross(c, d, b)
        if (abC == 0L && onSegment(a, b, c)) return true
        if (abD == 0L && onSegment(a, b, d)) return true
        if (cdA == 0L && onSegment(c, d, a)) return true
        if (cdB == 0L && onSegment(c, d, b)) return true
        return (abC > 0L) != (abD > 0L) && (cdA > 0L) != (cdB > 0L)
    }

    fun calculate(
        polygon: CeilingPolygon,
        precisionStepMeters: Double
    ): CeilingPolygonResult {
        val precision = requireNotNull(MeasurementPrecision.fromStep(precisionStepMeters)) {
            "Unsupported geometry precision"
        }
        val points = polygon.vertices
        require(polygon.shapeType.isNotBlank() &&
            points.size in 3..MAX_VERTICES &&
            points.toSet().size == points.size) {
            "A ceiling needs 3–128 distinct, ordered vertices"
        }
        require(points.all { it.xMm in -MAX_COORDINATE_MM..MAX_COORDINATE_MM &&
            it.yMm in -MAX_COORDINATE_MM..MAX_COORDINATE_MM }) {
            "Ceiling coordinates exceed safe measurement bounds"
        }
        val stepMm = (precision.stepMeters * 1000.0).toLong()
        require(points.all { it.xMm % stepMm == 0L && it.yMm % stepMm == 0L }) {
            "Ceiling vertices do not match frozen measurement precision"
        }

        var signedTwiceArea = 0L
        var perimeterMm = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val next = points[(i + 2) % points.size]
            require(cross(a, b, next) != 0L ||
                !onSegment(a, b, next) && !onSegment(b, next, a)) {
                "The ceiling perimeter doubles back along an edge"
            }
            signedTwiceArea += a.xMm * b.yMm - b.xMm * a.yMm
            val dx = (b.xMm - a.xMm).toDouble()
            val dy = (b.yMm - a.yMm).toDouble()
            perimeterMm += sqrt(dx * dx + dy * dy)
            for (j in i + 1 until points.size) {
                // Neighboring edges meet at exactly their shared vertex.
                if (j == i + 1 || (i == 0 && j == points.lastIndex)) continue
                val c = points[j]
                val d = points[(j + 1) % points.size]
                require(!intersects(a, b, c, d)) {
                    "Ceiling contour crosses or touches itself"
                }
            }
        }
        require(signedTwiceArea != 0L) { "Ceiling area must be positive" }
        val area = abs(signedTwiceArea).toDouble() / 2_000_000.0
        val perimeter = perimeterMm / 1000.0
        val geometry = RoomGeometry(
            area = precision.area(area),
            perimeter = precision.length(perimeter),
            cornersCount = points.size
        )
        require(geometry.area > 0.0 && geometry.perimeter > 0.0) {
            "Ceiling dimensions are below measurement precision"
        }
        return CeilingPolygonResult(geometry, abs(signedTwiceArea))
    }
}

/**
 * Each future straight-edge room contributes only a parameter schema and a
 * vertex builder; polygon validation, area, perimeter, estimate pricing, and
 * immutable storage remain common.
 */
class CeilingShapeTemplate(
    val shapeType: String,
    val parameterKeys: Set<String>,
    private val vertexBuilder: (Map<String, Long>) -> List<CeilingPointMm>
) {
    fun build(measurementsMm: Map<String, Long>): CeilingPolygon {
        require(measurementsMm.keys == parameterKeys &&
            measurementsMm.values.all { it > 0L }) {
            "Incomplete or invalid ceiling template measurements"
        }
        return CeilingPolygon(shapeType, vertexBuilder(measurementsMm))
    }
}

object CeilingShapeTemplates {
    private val rectangle = CeilingShapeTemplate(
        shapeType = "rectangle",
        parameterKeys = setOf("length", "width")
    ) { m ->
        val a = m.getValue("length")
        val b = m.getValue("width")
        listOf(
            CeilingPointMm(0, 0), CeilingPointMm(a, 0),
            CeilingPointMm(a, b), CeilingPointMm(0, b)
        )
    }

    private val lShape = CeilingShapeTemplate(
        shapeType = "l_shaped",
        parameterKeys = setOf("A", "B", "C", "D", "E", "F")
    ) { m ->
        val a = m.getValue("A")
        val b = m.getValue("B")
        val c = m.getValue("C")
        val d = m.getValue("D")
        val e = m.getValue("E")
        val f = m.getValue("F")
        require(a == c + e && b == d + f) {
            "L-shaped template must satisfy A=C+E and B=D+F"
        }
        listOf(
            CeilingPointMm(0, 0), CeilingPointMm(a, 0),
            CeilingPointMm(a, d), CeilingPointMm(c, d),
            CeilingPointMm(c, b), CeilingPointMm(0, b)
        )
    }

    /** New right-angled forms can be registered without changing the engine. */
    val templates: List<CeilingShapeTemplate> = listOf(rectangle, lShape)

    fun template(shapeType: String): CeilingShapeTemplate? =
        templates.firstOrNull { it.shapeType == shapeType }

    fun fromMeasurements(shapeType: String, measurementsMm: Map<String, Long>): CeilingPolygon =
        requireNotNull(template(shapeType)) { "No geometric template for $shapeType" }
            .build(measurementsMm)

    fun fromVertices(vertices: List<CeilingPointMm>): CeilingPolygon =
        CeilingPolygon("polygon", vertices)
}
