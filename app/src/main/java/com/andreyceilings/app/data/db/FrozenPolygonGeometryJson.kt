package com.andreyceilings.app.data.db

import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.CeilingPolygonCalculator
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import org.json.JSONArray
import org.json.JSONObject

/** Android JSON adapter; validation and geometry math live in shared KMP. */
internal object FrozenPolygonGeometryJson {
    fun isVersion2(source: JSONObject): Boolean =
        source.has("geometrySchemaVersion") &&
            source.getLong("geometrySchemaVersion") ==
                FrozenPolygonGeometryContract.SCHEMA_VERSION

    fun read(source: JSONObject, precisionStepMeters: Double): FrozenPolygonGeometry {
        require(source.keys().asSequence().toSet() ==
            FrozenPolygonGeometryContract.sourceFields) {
            "Unknown, incomplete or malformed polygon source snapshot"
        }
        require(source.getLong("geometrySchemaVersion") ==
            FrozenPolygonGeometryContract.SCHEMA_VERSION) {
            "Unsupported polygon schema"
        }
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
                perimeterMeters = source.getDouble("perimeterMeters")
            )
        )
    }

    fun write(
        name: String,
        material: String,
        profile: String,
        complexity: String,
        frozen: FrozenPolygonGeometry
    ): JSONObject {
        FrozenPolygonGeometryContract.requireValid(frozen)
        val vertices = JSONArray()
        frozen.vertices.forEach { point ->
            vertices.put(JSONObject().put("xMm", point.xMm).put("yMm", point.yMm))
        }
        val measurements = JSONObject()
        frozen.measurementsMm.forEach { (key, mm) -> measurements.put(key, mm) }
        return JSONObject()
            .put("name", name).put("material", material)
            .put("profileType", profile).put("complexity", complexity)
            .put("shape", frozen.shapeType)
            .put("geometrySchemaVersion", FrozenPolygonGeometryContract.SCHEMA_VERSION)
            .put("vertices", vertices)
            .put("measurementsMm", measurements)
            .put("areaSquareMeters", frozen.areaSquareMeters)
            .put("perimeterMeters", frozen.perimeterMeters)
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
