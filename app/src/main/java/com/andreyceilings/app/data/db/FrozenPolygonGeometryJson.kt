package com.andreyceilings.app.data.db

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.MeasuredOrthogonalSide
import com.andreyceilings.shared.calculation.OrthogonalTurn
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import org.json.JSONArray
import org.json.JSONObject

/** Android JSON adapter; validation and geometry math live in shared KMP. */
internal object FrozenPolygonGeometryJson {
    private val measurementFields = setOf(
        "shape", "geometrySchemaVersion", "vertices", "measurementsMm",
        "areaSquareMeters", "perimeterMeters", "precisionStepMeters"
    )

    /** Measurement rows use the very same v2 vertex encoding as estimates. */
    fun writeMeasurement(frozen: FrozenPolygonGeometry): String =
        geometryFields(frozen)
            .put("precisionStepMeters", frozen.precisionStepMeters)
            .toString()

    fun readMeasurement(sourceJson: String): FrozenPolygonGeometry {
        val source = JSONObject(sourceJson)
        val expected = if (isVersion3(source)) measurementFields + "sides"
            else measurementFields
        require(source.keys().asSequence().toSet() == expected) {
            "Incomplete or unsupported saved measurement geometry"
        }
        return readGeometryFields(
            source, source.getDouble("precisionStepMeters")
        )
    }

    fun isVersion2(source: JSONObject): Boolean =
        source.has("geometrySchemaVersion") &&
            source.getLong("geometrySchemaVersion") ==
                FrozenPolygonGeometryContract.SCHEMA_VERSION

    fun isVersion3(source: JSONObject): Boolean =
        source.has("geometrySchemaVersion") &&
            source.getLong("geometrySchemaVersion") ==
                FrozenPolygonGeometryContract.SIDES_SCHEMA_VERSION

    fun isVersioned(source: JSONObject): Boolean =
        isVersion2(source) || isVersion3(source)

    fun read(source: JSONObject, precisionStepMeters: Double): FrozenPolygonGeometry {
        val expected = if (isVersion3(source)) {
            FrozenPolygonGeometryContract.sidesSourceFields
        } else FrozenPolygonGeometryContract.sourceFields
        require(source.keys().asSequence().toSet() == expected) {
            "Unknown, incomplete or malformed polygon source snapshot"
        }
        require(isVersioned(source)) {
            "Unsupported polygon schema"
        }
        return readGeometryFields(source, precisionStepMeters)
    }

    private fun readGeometryFields(
        source: JSONObject, precisionStepMeters: Double
    ): FrozenPolygonGeometry {
        // The same strict parser supports both historical vertex-only v2
        // and the side/turn-preserving v3 snapshot. Unknown versions fail closed.
        require(isVersioned(source)) {
            "Unsupported geometry schema"
        }
        val sides = if (isVersion3(source)) {
            val values = source.getJSONArray("sides")
            require(values.length() in 4..CeilingPolygonCalculator.MAX_VERTICES) {
                "Incorrect number of measured walls"
            }
            (0 until values.length()).map { index ->
                val value = values.getJSONObject(index)
                require(value.keys().asSequence().toSet() == setOf(
                    "lengthMm", "turnAfter"
                )) { "Unknown measured side fields" }
                MeasuredOrthogonalSide(
                    exactLong(value, "lengthMm"),
                    OrthogonalTurn.valueOf(value.getString("turnAfter"))
                )
            }
        } else emptyList()
        val verticesJson = source.getJSONArray("vertices")
        require(verticesJson.length() in 3..CeilingPolygonCalculator.MAX_VERTICES) {
            "Invalid frozen polygon vertex count"
        }
        val vertices = (0 until verticesJson.length()).map { index ->
            val value = verticesJson.getJSONObject(index)
            require(value.keys().asSequence().toSet() == setOf("xMm", "yMm")) {
                "Unknown frozen vertex fields"
            }
            CeilingPointMm(exactLong(value, "xMm"), exactLong(value, "yMm"))
        }
        val measurements = source.getJSONObject("measurementsMm")
        require(measurements.length() <= 128) { "Too many polygon measurements" }
        val dimensions = measurements.keys().asSequence().associateWith { key ->
            exactLong(measurements, key)
        }
        return FrozenPolygonGeometryContract.requireValid(
            FrozenPolygonGeometry(
                shapeType = source.getString("shape"),
                vertices = vertices,
                measurementsMm = dimensions,
                precisionStepMeters = precisionStepMeters,
                areaSquareMeters = source.getDouble("areaSquareMeters"),
                perimeterMeters = source.getDouble("perimeterMeters"),
                sides = sides
            )
        )
    }

    fun write(
        name: String,
        material: String,
        profile: String,
        complexity: String,
        frozen: FrozenPolygonGeometry
    ): JSONObject = geometryFields(frozen)
        .put("name", name).put("material", material)
        .put("profileType", profile).put("complexity", complexity)

    private fun geometryFields(frozen: FrozenPolygonGeometry): JSONObject {
        FrozenPolygonGeometryContract.requireValid(frozen)
        val vertices = JSONArray()
        frozen.vertices.forEach { point ->
            vertices.put(JSONObject().put("xMm", point.xMm).put("yMm", point.yMm))
        }
        val measurements = JSONObject()
        frozen.measurementsMm.forEach { (key, mm) -> measurements.put(key, mm) }
        val sides = JSONArray()
        frozen.sides.forEach { wall ->
            sides.put(JSONObject().put("lengthMm", wall.lengthMm)
                .put("turnAfter", wall.turnAfter.name))
        }
        val result = JSONObject()
            .put("shape", frozen.shapeType)
            .put("geometrySchemaVersion", if (frozen.sides.isNotEmpty()) {
                FrozenPolygonGeometryContract.SIDES_SCHEMA_VERSION
            } else FrozenPolygonGeometryContract.SCHEMA_VERSION)
            .put("vertices", vertices)
            .put("measurementsMm", measurements)
            .put("areaSquareMeters", frozen.areaSquareMeters)
            .put("perimeterMeters", frozen.perimeterMeters)
        if (frozen.sides.isNotEmpty()) result.put("sides", sides)
        return result
    }

    private fun exactLong(json: JSONObject, key: String): Long {
        val raw = json.get(key)
        require(raw is Number) { "Frozen geometry coordinate must be an integer" }
        val value = json.getLong(key)
        require(raw.toString() == value.toString()) {
            "Frozen millimetres must be exact integers"
        }
        return value
    }
}
