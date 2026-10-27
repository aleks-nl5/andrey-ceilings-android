package com.andreyceilings.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class GeometryCalculatorTest {
    @Test
    fun rectangleUsesExistingGoDefaultsAndRounding() {
        assertEquals(
            RoomGeometry(area = 20.0, perimeter = 18.0, cornersCount = 4),
            GeometryCalculator.calculate(GeometryInput(length = 5.0, width = 4.0)),
        )
    }

    @Test
    fun geometryRoundsAreaAndPerimeterToTwoDecimalPlaces() {
        assertEquals(
            RoomGeometry(area = 1.24, perimeter = 4.47, cornersCount = 4),
            GeometryCalculator.calculate(GeometryInput(length = 1.236, width = 1.0)),
        )
    }

    @Test
    fun missingRectangleDimensionsUseFourByThreeDefaults() {
        assertEquals(
            RoomGeometry(area = 12.0, perimeter = 14.0, cornersCount = 4),
            GeometryCalculator.calculate(GeometryInput(length = 0.0, width = -1.0)),
        )
    }

    @Test
    fun squareUsesLengthThenWidthThenThreeMeterFallback() {
        assertEquals(
            RoomGeometry(area = 9.0, perimeter = 12.0, cornersCount = 4),
            GeometryCalculator.calculate(GeometryInput(shape = RoomShape.SQUARE)),
        )
    }

    @Test
    fun lShapedRoomUsesExistingDefaultSideRules() {
        assertEquals(
            RoomGeometry(area = 15.0, perimeter = 18.0, cornersCount = 6),
            GeometryCalculator.calculate(GeometryInput(shape = RoomShape.L_SHAPED)),
        )
    }

    @Test
    fun customRoomWithValidDiagonalUsesHeronFormula() {
        assertEquals(
            RoomGeometry(area = 12.0, perimeter = 14.0, cornersCount = 5),
            GeometryCalculator.calculate(
                GeometryInput(shape = RoomShape.CUSTOM, diagonal1 = 5.0),
            ),
        )
    }

    @Test
    fun customSideCountSetsCornerCountWhenAtLeastFourSidesAreProvided() {
        assertEquals(
            6,
            GeometryCalculator.calculate(
                GeometryInput(shape = RoomShape.CUSTOM, customSides = listOf(3.0, 4.0, 3.0, 4.0, 2.0, 2.0)),
            ).cornersCount,
        )
    }
}
