package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class MeasurementWindowsCipherTest {
    @Test fun ownedMeasurementReadsPreserveGeometryAcrossEncryptedReopen(): Unit = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val directory = Files.createTempDirectory("cmp-measurement-cipher-").toFile()
        val file = directory.resolve("measurements.db")
        val key = ByteArray(32) { 61 }
        try {
            var project = 0L
            var id = 0L
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                PrototypeDatabase.Schema.create(driver)
                val db = PrototypeDatabase(driver)
                val projects = ProjectRepository(db)
                project = projects.createReturningId(null, "Measured project", createdAt = 1)
                val measurements = MeasurementRepository(db)
                id = measurements.createReturningId(project, "L room", shape = "polygon",
                    lengthMeters = 5.0, widthMeters = 4.0, areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    drawingJson = AndroidMeasurementFixtures.sidesV3, createdAt = 2)
            }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                val db = PrototypeDatabase(driver)
                val store = MeasurementDetailsStore(db)
                val loaded = assertIs<MeasurementDetailsState.Loaded>(store.observe(project, id)
                    .first { it !is MeasurementDetailsState.Loading })
                val geometry = assertIs<MeasurementGeometryView.Captured>(loaded.data.geometry).geometry
                assertEquals(6, geometry.sides.size)
                assertEquals(AndroidMeasurementFixtures.sidesV3, MeasurementRepository(db).find(id)?.drawingJson)
                assertEquals(MeasurementDetailsState.Missing, store.observe(project + 999, id)
                    .first { it !is MeasurementDetailsState.Loading })
                MeasurementRepository(db).delete(id)
                assertEquals(MeasurementDetailsState.Missing, store.observe(project, id)
                    .first { it !is MeasurementDetailsState.Loading })
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }
}
