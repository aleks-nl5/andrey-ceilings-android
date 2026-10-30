package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.FrozenPolygonGeometryJson
import com.andreyceilings.app.data.db.RoomMeasurementDao
import com.andreyceilings.app.data.db.RoomMeasurementEntity
import com.andreyceilings.shared.calculation.CeilingPointMm
import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted SQLDelight persistence, not a mock of the Android editor. */
@RunWith(AndroidJUnit4::class)
class MeasurementPolygonPersistenceTest {
    @Test fun measuredPolygonAndTemplateSurviveEditingAndColdSqlCipherWalReopen() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbName = "sqldelight_polygon_measurement_editor.db"
        val key = ByteArray(32) { 0x64.toByte() }
        context.deleteDatabase(dbName)
        val concave = FrozenPolygonGeometryContract.capture(
            "polygon", listOf(
                CeilingPointMm(0, 0), CeilingPointMm(6000, 0),
                CeilingPointMm(6000, 2000), CeilingPointMm(3000, 2000),
                CeilingPointMm(3000, 5000), CeilingPointMm(0, 5000)
            ), emptyMap(), 0.01
        )
        val rightAngleL = FrozenPolygonGeometryContract.capture(
            "l_shaped", CeilingShapeTemplates.fromMeasurements(
                "l_shaped", mapOf(
                    "A" to 5000L, "B" to 4000L,
                    "C" to 2000L, "D" to 2000L
                )
            ).vertices,
            mapOf(
                "A" to 5000L, "B" to 4000L,
                "C" to 2000L, "D" to 2000L
            ), 0.01
        )
        try {
            var measurementId = 0L
            createEncryptedPrototypeDriver(
                context, key, dbName, enableWalForTesting = true
            ).use { driver ->
                val db = PrototypeDatabase(driver)
                val projectId = ProjectRepository(db).createReturningId(
                    clientId = null, title = "Polygon measurement", createdAt = 11L
                )
                val dao = RoomMeasurementDao(MeasurementRepository(db))
                measurementId = dao.insert(
                    RoomMeasurementEntity(
                        projectId = projectId, name = "Irregular room",
                        shape = "polygon", lengthMeters = 6.0, widthMeters = 5.0,
                        areaSquareMeters = 21.0, perimeterMeters = 22.0,
                        drawingJson = FrozenPolygonGeometryJson.writeMeasurement(concave)
                    )
                )
                val first = dao.observeAll().first().single()
                assertEquals(measurementId, first.id)
                assertEquals(concave,
                    FrozenPolygonGeometryJson.readMeasurement(first.drawingJson))
                dao.update(first.copy(
                    name = "Edited L room", shape = "l_shaped",
                    lengthMeters = 5.0, widthMeters = 4.0,
                    areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    drawingJson = FrozenPolygonGeometryJson.writeMeasurement(rightAngleL)
                ))
            }
            createEncryptedPrototypeDriver(
                context, key, dbName, enableWalForTesting = true
            ).use { driver ->
                val dao = RoomMeasurementDao(
                    MeasurementRepository(PrototypeDatabase(driver))
                )
                val reopened = dao.observeAll().first().single()
                assertEquals(measurementId, reopened.id)
                assertEquals("Edited L room", reopened.name)
                assertEquals(14.0, reopened.areaSquareMeters, 0.0)
                assertEquals(rightAngleL,
                    FrozenPolygonGeometryJson.readMeasurement(reopened.drawingJson))
                val changedArea = JSONObject(reopened.drawingJson)
                    .put("areaSquareMeters", 15.0).toString()
                assertTrue(runCatching {
                    FrozenPolygonGeometryJson.readMeasurement(changedArea)
                }.isFailure)
                val changedVertex = JSONObject(reopened.drawingJson)
                changedVertex.getJSONArray("vertices").getJSONObject(1)
                    .put("xMm", 9999L)
                assertTrue(runCatching {
                    FrozenPolygonGeometryJson.readMeasurement(changedVertex.toString())
                }.isFailure)
                val unexpected = JSONObject(reopened.drawingJson)
                    .put("unknown", 123).toString()
                assertTrue(runCatching {
                    FrozenPolygonGeometryJson.readMeasurement(unexpected)
                }.isFailure)
                assertEquals(1, dao.count())
            }
        } finally {
            context.deleteDatabase(dbName)
            key.fill(0)
        }
    }
}
