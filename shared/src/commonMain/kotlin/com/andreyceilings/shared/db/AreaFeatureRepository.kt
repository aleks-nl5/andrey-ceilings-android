package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import kotlinx.serialization.json.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

fun encodeAreaPositions(shape: AreaShape): String = buildJsonArray {
    shape.positions.forEach { add(buildJsonObject { put("xMm",it.xMm);put("yMm",it.yMm) }) }
}.toString()
fun encodeAreaMountings(mountings: List<PointMounting>): String = buildJsonArray {
    mountings.forEach {add(encodeMounting(it))}
}.toString()

class AreaFeatureRepository(private val database: PrototypeDatabase) {
    fun forMeasurement(uuid: String): List<AreaFeature> = database.areaFeaturesQueries.selectForMeasurement(uuid).executeAsList().map {it.domain()}
    fun observeForMeasurement(uuid: String) = database.areaFeaturesQueries.selectForMeasurement(uuid).asFlow().mapToList(Dispatchers.Default).map {rows -> rows.map {it.domain()}}
    fun all(): List<AreaFeature> = database.areaFeaturesQueries.selectAll().executeAsList().map {it.domain()}
}
private fun Area_features.domain(): AreaFeature {
    val positions=Json.parseToJsonElement(positionsJson).jsonArray.map {
        PointPositionMm(it.jsonObject.getValue("xMm").jsonPrimitive.long,it.jsonObject.getValue("yMm").jsonPrimitive.long)
    }
    val shape=when(shapeKind) {
        "POLYGON" -> AreaShape.Polygon(positions)
        "CIRCLE" -> AreaShape.Circle(positions.single(),checkNotNull(radiusMm))
        else -> error("Unsupported area shape")
    }
    require(canonicalTwiceAreaMm2(shape)==twiceAreaMm2)
    return AreaFeature(uuid,measurementUuid,shape,contourUpdatedAt,createdAt,updatedAt,elementUuid,AreaRole.valueOf(role),note,
        Json.parseToJsonElement(mountingsJson).jsonArray.map {decodeMounting(it.jsonPrimitive.content)},
        radiusMeasured==1L,needsReview==1L,unresolved==1L,deletedAt,lastCeilingEditUuid)
}
