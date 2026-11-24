package com.andreyceilings.shared.calculation

import kotlin.math.abs
import kotlin.math.round

/** Every field is a measured side, including the final closing side. */
enum class OrthogonalTurn { LEFT, RIGHT }
data class MeasuredOrthogonalSide(val lengthMm: Long, val turnAfter: OrthogonalTurn)

/**
 * The first measured wall points east in a local drawing coordinate system.
 * Every later vertex is derived from lengths and 90° turns; there is no
 * invented/adjusted closing wall. Consecutive turns, endpoint closure and
 * self-intersections are verified in common KMP.
 */
object OrthogonalSideContour {
    fun fromMeters(
        sides: List<Pair<Double, OrthogonalTurn>>,
        precisionStepMeters: Double
    ): List<MeasuredOrthogonalSide> {
        val precision = requireNotNull(MeasurementPrecision.fromStep(precisionStepMeters)) {
            "Невідома точність вимірювання"
        }
        val gridMm = round(precision.stepMeters * 1000.0).toLong()
        require(sides.size in 4..CeilingPolygonCalculator.MAX_VERTICES) {
            "Для багатокутника потрібно від 4 до 128 сторін"
        }
        return sides.map { (meters, turn) ->
            require(meters.isFinite() && meters > 0.0 &&
                meters * 1000.0 <= CeilingPolygonCalculator.MAX_COORDINATE_MM.toDouble()) {
                "Довжина сторони має бути додатною і в допустимих межах"
            }
            val scaled = meters * 1000.0
            val gridUnits = round(scaled / gridMm)
            require(abs(scaled - gridUnits * gridMm) < 0.000001) {
                "Довжина сторони має відповідати вибраній точності"
            }
            val mm = gridUnits.toLong() * gridMm
            require(mm > 0L) { "Нульова сторона неприпустима" }
            MeasuredOrthogonalSide(mm, turn)
        }
    }

    fun build(
        sides: List<MeasuredOrthogonalSide>,
        precisionStepMeters: Double
    ): CeilingPolygon {
        val precision = requireNotNull(MeasurementPrecision.fromStep(precisionStepMeters)) {
            "Невідома точність вимірювання"
        }
        val gridMm = round(precision.stepMeters * 1000.0).toLong()
        require(sides.size in 4..CeilingPolygonCalculator.MAX_VERTICES) {
            "Для багатокутника потрібно від 4 до 128 сторін"
        }
        require(sides.all { it.lengthMm > 0L &&
            it.lengthMm <= CeilingPolygonCalculator.MAX_COORDINATE_MM &&
            it.lengthMm % gridMm == 0L }) {
            "Виміри сторін не відповідають вибраній точності"
        }
        val trace = trace(sides)
        val walls = sides.indices.toSet()
        if (trace.end != CeilingPointMm(0, 0)) throw ContourGeometryException(
            ContourProblem.OPEN, walls, "Контур не замкнено: перевірте довжини всіх сторін")
        if (trace.direction != 0) throw ContourGeometryException(
            ContourProblem.OPEN, walls, "Напрям після останнього повороту не збігається з початковим")
        val polygon = CeilingPolygon("polygon", trace.vertices)
        CeilingPolygonCalculator.calculate(polygon, precisionStepMeters)
        return polygon
    }

    internal data class Trace(val vertices: List<CeilingPointMm>, val end: CeilingPointMm, val direction: Int)

    internal fun trace(sides: List<MeasuredOrthogonalSide>): Trace {
        val vertices = ArrayList<CeilingPointMm>(sides.size)
        var x = 0L
        var y = 0L
        var direction = 0
        for (side in sides) {
            vertices += CeilingPointMm(x, y)
            when (direction) {
                0 -> x += side.lengthMm
                1 -> y += side.lengthMm
                2 -> x -= side.lengthMm
                else -> y -= side.lengthMm
            }
            direction = (direction + if (side.turnAfter == OrthogonalTurn.RIGHT) 1 else 3) % 4
        }
        return Trace(vertices, CeilingPointMm(x, y), direction)
    }

    /** Old X/Y records can be shown, but never silently converted to measured sides. */
    fun calculatedGeometry(
        sides: List<MeasuredOrthogonalSide>,
        precisionStepMeters: Double
    ): RoomGeometry = CeilingPolygonCalculator.calculate(
        build(sides, precisionStepMeters), precisionStepMeters
    ).geometry
}
