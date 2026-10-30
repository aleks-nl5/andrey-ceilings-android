package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.calculation.KmpCeilingCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VerifiedCeilingGeometryTest {
    @Test fun completeSixSideRoomProducesExactKmpAreaAndPerimeter() {
        val input = VerifiedCeilingGeometry.capturedLShaped(
            listOf(5.0, 4.0, 2.0, 2.0, 3.0, 2.0), 0.01
        )
        assertEquals("l_shaped", input.shape)
        assertEquals(5.0, input.sideA, 0.0)
        assertEquals(3.0, input.sideE, 0.0)
        val dimensions = KmpCeilingCalculator.geometry(
            VerifiedCeilingGeometry.requireFrozenLShaped(input)
        )
        assertEquals(14.0, dimensions.area, 0.000001)
        assertEquals(18.0, dimensions.perimeter, 0.000001)
        assertEquals(6, dimensions.cornersCount)
    }

    @Test fun capturedValuesRoundBeforeClosureAndBecomeCanonicalSnapshot() {
        val input = VerifiedCeilingGeometry.capturedLShaped(
            listOf(5.004, 4.004, 2.004, 2.004, 3.004, 2.004), 0.01
        )
        assertEquals(5.0, input.sideA, 0.0)
        assertEquals(2.0, input.sideD, 0.0)
        assertEquals(input, VerifiedCeilingGeometry.requireFrozenLShaped(input))
        assertFailsWith<IllegalArgumentException> {
            VerifiedCeilingGeometry.requireFrozenLShaped(
                input.copy(sideA = 5.004)
            )
        }
    }

    @Test fun incompleteOrInvalidUserInputsCannotReachImmutableEstimate() {
        val valid = listOf(5.0, 4.0, 2.0, 2.0, 3.0, 2.0)
        val invalid = listOf(
            valid.dropLast(1),
            valid.mapIndexed { i, x -> if (i == 4) 3.5 else x },
            valid.mapIndexed { i, x -> if (i == 0) Double.NaN else x },
            valid.mapIndexed { i, x -> if (i == 1) 0.0 else x },
            valid.mapIndexed { i, x -> if (i == 1) Double.POSITIVE_INFINITY else x },
            listOf(0.5, 0.5, 0.2, 0.2, 0.3, 0.3)
        )
        invalid.forEach { sides ->
            assertFailsWith<IllegalArgumentException> {
                VerifiedCeilingGeometry.capturedLShaped(sides, 0.01)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            VerifiedCeilingGeometry.capturedLShaped(valid, 0.123)
        }
    }
    @Test fun activeRectangleAndLGeometryUseOneVerifiedVertexCalculation() {
        val rectangle = VerifiedCeilingGeometry.capturedRectangle(
            1.006, 1.006, 0.01
        )
        assertEquals(4, rectangle.verticesMm.size)
        assertEquals(1.0201, KmpCeilingCalculator.geometry(rectangle).area, 0.000001)
        assertEquals(4.04, KmpCeilingCalculator.geometry(rectangle).perimeter, 0.000001)

        val historicalL = VerifiedCeilingGeometry.capturedLShaped(
            listOf(5.0, 4.0, 2.0, 2.0, 3.0, 2.0), 0.01
        )
        val polygonL = VerifiedCeilingGeometry.polygonForCalculation(historicalL)
        assertEquals(6, polygonL.verticesMm.size)
        assertEquals(KmpCeilingCalculator.geometry(historicalL),
            KmpCeilingCalculator.geometry(polygonL))
        assertEquals(14.0, KmpCeilingCalculator.geometry(polygonL).area, 0.0)
        assertEquals(18.0, KmpCeilingCalculator.geometry(polygonL).perimeter, 0.0)
        // The original immutable KMP input is NOT modified.
        assertEquals(emptyList(), historicalL.verticesMm)
    }

    @Test fun fourEnteredLParametersDeriveTwoSidesAndPreserveFullFrozenGeometry() {
        val active = VerifiedCeilingGeometry.capturedTemplate(
            "l_shaped",
            mapOf("A" to 5.0, "B" to 4.0, "C" to 2.0, "D" to 2.0),
            0.01
        )
        assertEquals(3.0, active.sideE, 0.0)
        assertEquals(2.0, active.sideF, 0.0)
        assertEquals(6, active.verticesMm.size)
        val historical = active.copy(verticesMm = emptyList())
        assertEquals(historical, VerifiedCeilingGeometry.requireFrozenLShaped(historical))
        assertEquals(14.0, KmpCeilingCalculator.geometry(active).area, 0.0)
        assertEquals(18.0, KmpCeilingCalculator.geometry(active).perimeter, 0.0)
    }

}
