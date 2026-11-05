package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

/** Read-only outside the whole-ceiling journal. There is deliberately no separate line Save. */
class LinePlacementRepository(private val database: PrototypeDatabase) {
    fun forMeasurement(uuid: String): List<LinePlacement> = database.linePlacementsQueries.selectForMeasurement(uuid).executeAsList().map { it.model() }
    fun all(): List<LinePlacement> = database.linePlacementsQueries.selectAll().executeAsList().map { it.model() }
    fun observeForMeasurement(uuid: String) = database.linePlacementsQueries.selectForMeasurement(uuid).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.model() } }
}
private fun Line_placements.model() = LinePlacement(uuid,elementUuid,PointPositionMm(startX,startY),PointPositionMm(endX,endY),
    contourUpdatedAt,createdAt,updatedAt,deletedAt,widthMm,note,decodeMounting(startMounting),decodeMounting(endMounting),
    needsReview==1L,unresolved==1L,lastCeilingEditUuid)
