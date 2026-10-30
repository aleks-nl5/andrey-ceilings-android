package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.calculation.GeometryInput
import com.andreyceilings.shared.calculation.KmpCeilingCalculator
import com.andreyceilings.shared.calculation.MeasurementPrecision
import kotlin.math.abs

/**
 * Mandatory validation at the boundary between captured user geometry,
 * the shared calculator and an immutable estimate source snapshot.
 *
 * KmpCeilingCalculator.geometry retains legacy convenience defaults for its
 * old callers. Never use those defaults when creating a commercial L-shape.
 */
object VerifiedCeilingGeometry {
    fun capturedLShaped(
        sides: List<Double>,
        precisionStepMeters: Double
    ): GeometryInput {
        val precision = requireNotNull(MeasurementPrecision.fromStep(precisionStepMeters)) {
            "Unsupported measurement precision"
        }
        require(sides.size == 6 && sides.all { it.isFinite() && it > 0.0 }) {
            "L-подібна стеля потребує шість додатних сторін A–F"
        }
        val rounded = sides.map(precision::length)
        require(rounded.all { it.isFinite() && it > 0.0 }) {
            "Розміри L-подібної стелі занадто малі для вибраної точності"
        }
        val a = rounded[0]
        val b = rounded[1]
        val c = rounded[2]
        val d = rounded[3]
        val e = rounded[4]
        val f = rounded[5]
        require(abs(a - c - e) < 0.000001 && abs(b - d - f) < 0.000001) {
            "L-подібна стеля: перевірте A = C + E та B = D + F"
        }
        // Legacy KMP geometry clamps tiny L areas to 1 m². That would
        // invent quantities, so it is forbidden for immutable documents.
        require(a * b - e * f >= 1.0) {
            "Площа L-подібної стелі має бути не меншою за 1 м²"
        }
        return GeometryInput(
            shape = FrozenCeilingShape.L_SHAPED.wireId,
            sideA = a, sideB = b, sideC = c,
            sideD = d, sideE = e, sideF = f,
            precisionStepMeters = precisionStepMeters
        )
    }

    /** A stored source must already be canonical: never round it on replay. */
    fun requireFrozenLShaped(input: GeometryInput): GeometryInput {
        require(input.shape == FrozenCeilingShape.L_SHAPED.wireId &&
            input.length == 0.0 && input.width == 0.0 &&
            input.diagonal1 == 0.0 && input.customSides.isEmpty()) {
            "Unexpected fields in a frozen L-shaped geometry"
        }
        val sides = listOf(
            input.sideA, input.sideB, input.sideC,
            input.sideD, input.sideE, input.sideF
        )
        val canonical = capturedLShaped(
            sides,
            requireNotNull(input.precisionStepMeters) {
                "Missing frozen measurement precision"
            }
        )
        require(canonical == input) {
            "Frozen L-shaped inputs differ from their measured values"
        }
        return canonical
    }

    fun checkedFrozenLShapedAreaAndPerimeter(input: GeometryInput): Pair<Double, Double> {
        val geometry = KmpCeilingCalculator.geometry(requireFrozenLShaped(input))
        return geometry.area to geometry.perimeter
    }
}
