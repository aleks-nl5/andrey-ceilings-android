package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.calculation.MeasuredOrthogonalSide
import com.andreyceilings.shared.calculation.OrthogonalTurn
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlinx.serialization.json.*

/** Existing Android measurement v2/v3 wire format; no persistence or rounding on read. */
object MeasurementGeometryCodec {
    private val json = Json { isLenient = false }
    private val fields = setOf("shape", "geometrySchemaVersion", "vertices", "measurementsMm",
        "areaSquareMeters", "perimeterMeters", "precisionStepMeters")
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun read(source: String): FrozenPolygonGeometry {
        require(source.length <= 262_144) { "Saved geometry is too large" }
        requireUniqueKeys(source)
        val root = json.parseToJsonElement(source).objectValue()
        val version = root.getValue("geometrySchemaVersion").exactLong()
        require(version == 2L || version == 3L) { "Unsupported measurement geometry version" }
        root.requireFields(if (version == 3L) fields + "sides" else fields)
        val vertices = root.getValue("vertices").arrayValue(3).map {
            val p = it.objectValue().also { o -> o.requireFields(setOf("xMm", "yMm")) }
            CeilingPointMm(p.getValue("xMm").exactLong(), p.getValue("yMm").exactLong())
        }
        val dimensions = root.getValue("measurementsMm").objectValue()
        require(dimensions.size <= 128) { "Too many template dimensions" }
        val sides = if (version == 3L) root.getValue("sides").arrayValue(4).map {
            val s = it.objectValue().also { o -> o.requireFields(setOf("lengthMm", "turnAfter")) }
            MeasuredOrthogonalSide(s.getValue("lengthMm").exactLong(),
                OrthogonalTurn.valueOf(s.getValue("turnAfter").stringValue()))
        } else emptyList()
        return FrozenPolygonGeometryContract.requireValid(FrozenPolygonGeometry(
            shapeType = root.getValue("shape").stringValue(), vertices = vertices,
            measurementsMm = dimensions.mapValues { it.value.exactLong() },
            precisionStepMeters = root.getValue("precisionStepMeters").finiteDouble(),
            areaSquareMeters = root.getValue("areaSquareMeters").finiteDouble(),
            perimeterMeters = root.getValue("perimeterMeters").finiteDouble(), sides = sides
        ))
    }

    fun write(geometry: FrozenPolygonGeometry): String {
        val g = FrozenPolygonGeometryContract.requireValid(geometry)
        return buildJsonObject {
            put("shape", g.shapeType)
            put("geometrySchemaVersion", if (g.sides.isEmpty()) 2L else 3L)
            putJsonArray("vertices") { g.vertices.forEach { p -> add(buildJsonObject {
                put("xMm", p.xMm); put("yMm", p.yMm)
            }) } }
            putJsonObject("measurementsMm") { g.measurementsMm.forEach { (key, mm) -> put(key, mm) } }
            put("areaSquareMeters", g.areaSquareMeters)
            put("perimeterMeters", g.perimeterMeters)
            if (g.sides.isNotEmpty()) putJsonArray("sides") { g.sides.forEach { s ->
                add(buildJsonObject { put("lengthMm", s.lengthMm); put("turnAfter", s.turnAfter.name) })
            } }
            put("precisionStepMeters", g.precisionStepMeters)
        }.toString()
    }

    private fun JsonElement.objectValue() = this as? JsonObject
        ?: throw IllegalArgumentException("Expected geometry object")
    private fun JsonObject.requireFields(expected: Set<String>) =
        require(keys == expected) { "Incomplete or unsupported geometry fields" }
    private fun JsonElement.arrayValue(min: Int): JsonArray {
        val array = this as? JsonArray ?: throw IllegalArgumentException("Expected geometry array")
        require(array.size in min..CeilingPolygonCalculator.MAX_VERTICES) { "Invalid geometry array size" }
        return array
    }
    private fun JsonElement.stringValue(): String {
        val p = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected geometry string")
        require(p.isString) { "Expected geometry string" }
        return p.content
    }
    private fun JsonElement.exactLong(): Long {
        val p = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected integer millimetres")
        require(!p.isString && integer.matches(p.content)) { "Millimetres must be integer JSON numbers" }
        return requireNotNull(p.longOrNull) { "Millimetres exceed integer range" }
    }
    private fun JsonElement.finiteDouble(): Double {
        val p = this as? JsonPrimitive ?: throw IllegalArgumentException("Expected geometry number")
        require(!p.isString && number.matches(p.content)) { "Expected finite JSON number" }
        return requireNotNull(p.doubleOrNull?.takeIf { it.isFinite() }) { "Invalid geometry number" }
    }

    // JsonObject parsing normally keeps the last duplicate key. Reject ambiguity
    // before parsing, including escaped equivalents, and bound nesting depth.
    private fun requireUniqueKeys(source: String) {
        val stack = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < source.length) {
            when (source[i]) {
                '{' -> { stack.add(mutableSetOf()); require(stack.size <= 32) { "Geometry is too deeply nested" } }
                '[' -> { stack.add(null); require(stack.size <= 32) { "Geometry is too deeply nested" } }
                '}', ']' -> { require(stack.isNotEmpty()) { "Invalid JSON nesting" }; stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    require(i < source.length) { "Unterminated JSON string" }
                    var next = i + 1
                    while (next < source.length && source[next].isWhitespace()) next++
                    if (source.getOrNull(next) == ':') {
                        val keys = requireNotNull(stack.lastOrNull()) { "Invalid JSON object key" }
                        val key = json.decodeFromString<String>(source.substring(start, i + 1))
                        require(keys.add(key)) { "Duplicate geometry field" }
                    }
                }
            }
            i++
        }
        require(stack.isEmpty()) { "Unclosed JSON geometry" }
    }
}
