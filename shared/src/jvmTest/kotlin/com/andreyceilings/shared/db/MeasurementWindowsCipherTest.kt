package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class MeasurementWindowsCipherTest {
    @Test fun editCasAndNavigationOnEncryptedWindows(): Unit = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val directory = Files.createTempDirectory("cmp-edit-cipher-").toFile()
        val key = ByteArray(32) { 65 }
        try {
            SqlCipherWindowsDriverFactory.open(directory.resolve("edit.db"), key).use { driver ->
                PrototypeDatabase.Schema.create(driver)
                MeasurementEditPersistenceProof.run(PrototypeDatabase(driver))
                MeasurementEditNavigationProof.run(driver)
            }
            SqlCipherWindowsDriverFactory.open(directory.resolve("edit.db"), key).use { driver ->
                MeasurementRepository(PrototypeDatabase(driver)).all().forEach { MeasurementGeometryCodec.read(it.drawingJson) }
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }

    @Test fun slowCipherTransactionCannotBeHiddenByBackOrTab(): Unit = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val directory = Files.createTempDirectory("cmp-create-navigation-").toFile()
        val key = ByteArray(32) { 64 }
        try {
            SqlCipherWindowsDriverFactory.open(directory.resolve("navigation.db"), key).use { driver ->
                PrototypeDatabase.Schema.create(driver)
                MeasurementCreateNavigationProof.run(driver)
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }

    @Test fun cmpEditorUsesReturningIdsOnRealWindowsCipherAndReopensEveryCapturedShape(): Unit = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val directory = Files.createTempDirectory("cmp-create-cipher-").toFile()
        val file = directory.resolve("creation.db")
        val key = ByteArray(32) { 62 }
        try {
            var project = 0L
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                PrototypeDatabase.Schema.create(driver)
                project = MeasurementCreatePersistenceProof.run(PrototypeDatabase(driver))
            }
            assertFalse(file.readBytes().take(16).toByteArray().contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            assertFails { SqlCipherWindowsDriverFactory.open(file, ByteArray(32) { 63 }).use {
                MeasurementRepository(PrototypeDatabase(it)).all()
            } }
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                val db = PrototypeDatabase(driver)
                val rows = MeasurementRepository(db).forProject(project)
                assertEquals(12, rows.size)
                assertEquals(setOf("rectangle", "l_shaped", "polygon"), rows.map { it.shape }.toSet())
                rows.forEach { assertEquals(it.shape, MeasurementGeometryCodec.read(it.drawingJson).shapeType) }
                val store = MeasurementCreateStore(db) { 400 }
                driver.close()
                val failed = MeasurementCreateModel(store, MeasurementCreateFixtures.draft(project, "rectangle",
                    com.andreyceilings.shared.calculation.MeasurementPrecision.ONE_CENTIMETER))
                failed.save()
                assertNull(failed.state.value.savedId)
                assertNotNull(failed.state.value.error)
            }
            SqlCipherWindowsDriverFactory.open(file, key).use {
                assertEquals(12, MeasurementRepository(PrototypeDatabase(it)).forProject(project).size)
            }
        } finally { key.fill(0); directory.deleteRecursively() }
    }

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
