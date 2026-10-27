package com.andreyceilings.shared.domain

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

enum class RoomShape {
    RECTANGLE,
    SQUARE,
    L_SHAPED,
    CUSTOM,
}

data class GeometryInput(
    val shape: RoomShape = RoomShape.RECTANGLE,
    val length: Double = 0.0,
    val width: Double = 0.0,
    val sideA: Double = 0.0,
    val sideB: Double = 0.0,
    val sideC: Double = 0.0,
    val sideD: Double = 0.0,
    val sideE: Double = 0.0,
    val sideF: Double = 0.0,
    val diagonal1: Double = 0.0,
    val customSides: List<Double> = emptyList(),
)

data class RoomGeometry(
    val area: Double,
    val perimeter: Double,
    val cornersCount: Int,
)

/**
 * Initial common-Kotlin port of the existing Go CalculateRoomGeometry rules.
 * Keep behavior aligned with go-core/core/calculator.go until parity tests
 * have been run against a representative set of real estimates.
 */
object GeometryCalculator {
    fun calculate(input: GeometryInput): RoomGeometry {
        var length = input.length
        var width = input.width
        if (length <= 0.0) length = 4.0
        if (width <= 0.0) width = 3.0

        if (input.shape == RoomShape.SQUARE) {
            var side = input.length
            if (side <= 0.0) side = input.width
            if (side <= 0.0) side = 3.0
            side = max(0.1, side)
            return RoomGeometry(round2(side * side), round2(4.0 * side), 4)
        }

        if (input.shape == RoomShape.RECTANGLE) {
            length = max(0.1, length)
            width = max(0.1, width)
            return RoomGeometry(round2(length * width), round2(2.0 * (length + width)), 4)
        }

        if (input.shape == RoomShape.L_SHAPED) {
            var a = input.sideA
            if (a <= 0.0) a = input.length
            if (a <= 0.0) a = 5.0
            var b = input.sideB
            if (b <= 0.0) b = input.width
            if (b <= 0.0) b = 4.0
            var c = input.sideC
            if (c <= 0.0) c = a * 0.5
            var d = input.sideD
            if (d <= 0.0) d = b * 0.5
            var e = input.sideE
            if (e <= 0.0) e = max(0.1, a - c)
            var f = input.sideF
            if (f <= 0.0) f = max(0.1, b - d)
            val area = max(1.0, a * b - max(0.0, e * f))
            return RoomGeometry(round2(area), round2(a + b + c + d + e + f), 6)
        }

        var a = input.sideA
        if (a <= 0.0) a = length
        var b = input.sideB
        if (b <= 0.0) b = width
        var c = input.sideC
        if (c <= 0.0) c = length
        var d = input.sideD
        if (d <= 0.0) d = width
        var area = length * width
        if (input.diagonal1 > 0.0) {
            val candidate = triangleArea(a, b, input.diagonal1) +
                triangleArea(c, d, input.diagonal1)
            if (candidate > 0.0) area = candidate
        }
        val corners = if (input.customSides.size >= 4) input.customSides.size else 5
        return RoomGeometry(round2(area), round2(a + b + c + d), corners)
    }

    private fun triangleArea(x: Double, y: Double, diagonal: Double): Double {
        val semiperimeter = (x + y + diagonal) / 2.0
        val product = semiperimeter * (semiperimeter - x) *
            (semiperimeter - y) * (semiperimeter - diagonal)
        return if (product <= 0.0) 0.0 else sqrt(product)
    }

    // Go math.Round rounds positive half values away from zero.
    private fun round2(value: Double): Double = floor(value * 100.0 + 0.5) / 100.0
}
