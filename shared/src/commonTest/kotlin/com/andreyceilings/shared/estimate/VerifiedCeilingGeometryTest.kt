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
}
