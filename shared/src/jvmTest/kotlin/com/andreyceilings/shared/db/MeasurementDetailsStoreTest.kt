package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class MeasurementDetailsStoreTest {
    @Test fun ownedRecordReactsToEditsDeletionAndParentChangesWithoutWriting() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val projects = ProjectRepository(db)
            val measurements = MeasurementRepository(db)
            val p = projects.createReturningId(null, "Об'єкт", address = "Збережена адреса", createdAt = 1)
            val other = projects.createReturningId(null, "Інший", createdAt = 2)
            val id = measurements.createReturningId(p, "Кухня", lengthMeters = 5.0, widthMeters = 4.0,
                areaSquareMeters = 20.0, perimeterMeters = 18.0,
                drawingJson = AndroidMeasurementFixtures.rectangleV2, notes = "Примітки", createdAt = 3)
            val original = measurements.find(id)
            val store = MeasurementDetailsStore(db)
            val updates = Channel<MeasurementDetailsState>(Channel.UNLIMITED)
            val job = launch { store.observe(p, id).collect { updates.send(it) } }
            suspend fun await(predicate: (MeasurementDetailsState) -> Boolean): MeasurementDetailsState = withTimeout(5_000) {
                var next = updates.receive()
                while (!predicate(next)) next = updates.receive()
                next
            }
            val first = assertIs<MeasurementDetailsState.Loaded>(await { it is MeasurementDetailsState.Loaded }).data
            assertEquals("Кухня", first.name)
            assertEquals("Збережена адреса", first.projectAddress)
            assertEquals("Примітки", first.notes)
            assertIs<MeasurementGeometryView.Captured>(first.geometry)
            assertEquals(original, measurements.find(id), "Viewing never rewrites JSON, totals or timestamps")
            assertEquals(MeasurementDetailsState.Missing, store.observe(other, id).first { it !is MeasurementDetailsState.Loading })
            assertEquals(MeasurementDetailsState.Missing, store.observe(p, 999).first { it !is MeasurementDetailsState.Loading })
            assertEquals(MeasurementDetailsState.Missing, store.observe(999, id).first { it !is MeasurementDetailsState.Loading })
            measurements.update(id, "Оновлено", "rectangle", 5.0, 4.0, 20.0, 18.0,
                AndroidMeasurementFixtures.rectangleV2, "Нові примітки")
            assertEquals("Нові примітки", assertIs<MeasurementDetailsState.Loaded>(
                await { it is MeasurementDetailsState.Loaded && it.data.name == "Оновлено" }).data.notes)
            projects.update(p, null, "Об'єкт", "Нова адреса", "measurement", "", 4)
            await { it is MeasurementDetailsState.Loaded && it.data.projectAddress == "Нова адреса" }
            measurements.delete(id)
            assertEquals(MeasurementDetailsState.Missing, await { it == MeasurementDetailsState.Missing })
            job.cancel()
        }
    }

    @Test fun legacyTotalsArePreservedAndDeletedParentOrUnavailableDatabaseCannotLeakRecord() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PrototypeDatabase.Schema.create(driver)
        val db = PrototypeDatabase(driver)
        val projects = ProjectRepository(db)
        val measurements = MeasurementRepository(db)
        val p = projects.createReturningId(null, "Historical", createdAt = 1)
        val id = measurements.createReturningId(p, "Old", lengthMeters = 3.12345, widthMeters = 2.0,
            areaSquareMeters = 6.2469, perimeterMeters = 10.2469, createdAt = 2)
        val store = MeasurementDetailsStore(db)
        val state = assertIs<MeasurementDetailsState.Loaded>(store.observe(p, id).first { it !is MeasurementDetailsState.Loading })
        assertEquals(6.2469, state.data.areaSquareMeters)
        assertEquals(10.2469, state.data.perimeterMeters)
        assertIs<MeasurementGeometryView.HistoricalRectangle>(state.data.geometry)
        assertEquals("{}", measurements.find(id)?.drawingJson)
        assertEquals(MeasurementDetailsState.Missing, store.observe(-1, id).first())
        projects.delete(p)
        assertEquals(MeasurementDetailsState.Missing, store.observe(p, id).first { it !is MeasurementDetailsState.Loading })
        driver.close()
        assertEquals(MeasurementDetailsState.Unavailable, store.observe(p, id).first { it !is MeasurementDetailsState.Loading })
    }

    @Test fun malformedDrawingShowsMetadataAndExplicitlyUnavailableGeometry() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val p = ProjectRepository(db).createReturningId(null, "P", createdAt = 1)
            val id = MeasurementRepository(db).createReturningId(p, "Corrupt", lengthMeters = 3.0,
                widthMeters = 2.0, drawingJson = "broken", createdAt = 2)
            val state = assertIs<MeasurementDetailsState.Loaded>(MeasurementDetailsStore(db).observe(p, id)
                .first { it !is MeasurementDetailsState.Loading })
            assertEquals("Corrupt", state.data.name)
            assertEquals(MeasurementGeometryView.Unavailable, state.data.geometry)
        }
    }
}
