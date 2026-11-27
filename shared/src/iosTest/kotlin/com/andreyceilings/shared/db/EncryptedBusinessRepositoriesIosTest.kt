package com.andreyceilings.shared.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.andreyceilings.shared.calendar.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

import com.yet.sqlcipher.NativeSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherKey
import com.yet.sqlcipher.SqlCipherRecovery
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.andreyceilings.shared.measurement.*
import kotlin.test.assertIs

/** Real generated SQLDelight business schema over an encrypted iOS simulator database. */
class EncryptedBusinessRepositoriesIosTest {
    private val factory = NativeSqlCipherDriverFactory()

    private fun config(name: String, byte: Byte) = SqlCipherConfig(
        name = name,
        key = SqlCipherKey { ByteArray(32) { byte } },
        recovery = SqlCipherRecovery.Fail,
    )

    @Test fun calendarV24UpgradeAndEncryptedReopen(): Unit = runBlocking {
        val name = "libellaro-ios-calendar-${Random.nextLong().toString(16)}.db"
        val legacy = object : SqlSchema<QueryResult.Value<Unit>> {
            override val version = 24L
            override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                PriceCatalogSchema.createInTransaction(driver)
                removeEmptyCalendarSchema(driver)
                PriceCatalogSchema.verifyStructure(driver, version)
                return QueryResult.Unit
            }
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Fixture cannot migrate")
        }
        try {
            factory.create(legacy, config(name, 56)).use { driver ->
                ClientRepository(PrototypeDatabase(driver)).create("Calendar migration fixture", createdAt = 1)
            }
            val key = factory.create(IosPriceCatalogSchema, config(name, 56)).use { driver ->
                val db = PrototypeDatabase(driver)
                assertEquals("Calendar migration fixture", ClientRepository(db).all().single().name)
                val calendar = CalendarRepository(db)
                assertEquals(4, calendar.types().size)
                val date = LocalDate(2026, 12, 15)
                assertIs<CalendarSaveResult.Saved>(calendar.save(CalendarSave(CalendarWorkDraft(date,
                    WorkSchedule(WorkTimeMode.DATE_ONLY, TimeZone.UTC, listOf(WorkDay(0))), "Simulator work", "OTHER")))).key
            }
            factory.create(IosPriceCatalogSchema, config(name, 56)).use { driver ->
                verifyCalendarStructure(driver, PriceCatalogSchema.version)
                verifyCalendarContents(driver)
                assertEquals("Simulator work", CalendarRepository(PrototypeDatabase(driver)).exact(key)?.draft?.title)
            }
        } finally { factory.deleteDatabase(name) }
    }

    @Test fun cmpEditCasOnSimulatorCipher(): Unit = runBlocking {
        val name = "andrey-ios-edit-${Random.nextLong().toString(16)}.db"
        try {
            val driver = factory.create(PrototypeDatabase.Schema, config(name, 49))
            try { MeasurementEditPersistenceProof.run(PrototypeDatabase(driver)) } finally { driver.close() }
            val reopened = factory.create(PrototypeDatabase.Schema, config(name, 49))
            try { MeasurementRepository(PrototypeDatabase(reopened)).all().forEach { MeasurementGeometryCodec.read(it.drawingJson) } }
            finally { reopened.close() }
        } finally { factory.deleteDatabase(name) }
    }

    @Test fun cmpCreatePersistsEveryShapeAndPrecisionOnSimulatorCipher(): Unit = runBlocking {
        val name = "andrey-ios-create-${Random.nextLong().toString(16)}.db"
        try {
            val first = factory.create(PrototypeDatabase.Schema, config(name, 48))
            val parent = try { MeasurementCreatePersistenceProof.run(PrototypeDatabase(first)) }
                finally { first.close() }
            val reopened = factory.create(PrototypeDatabase.Schema, config(name, 48))
            try {
                val rows = MeasurementRepository(PrototypeDatabase(reopened)).forProject(parent)
                assertEquals(12, rows.size)
                rows.forEach { assertEquals(it.shape, MeasurementGeometryCodec.read(it.drawingJson).shapeType) }
            } finally { reopened.close() }
        } finally { factory.deleteDatabase(name) }
    }

    @Test
    fun repositoriesPersistReopenRejectWrongKeyAndCascade(): Unit = runBlocking {
        val name = "andrey-ios-business-${Random.nextLong().toString(16)}.db"
        try {
            factory.create(PrototypeDatabase.Schema, config(name, 31)).let { driver ->
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                    val db = PrototypeDatabase(driver)
                    ClientRepository(db).create("iOS encrypted client", createdAt = 1)
                    val clientId = ClientRepository(db).all().single().id
                    ProjectRepository(db).create(clientId, "iOS project", createdAt = 2)
                    val projectId = ProjectRepository(db).all().single().id
                    MeasurementRepository(db).create(projectId, "Room", createdAt = 3)
                    PriceRepository(db).create("IOS", "Film", "material", "m2", 20.0, 4)
                    EstimateRepository(db).create(projectId, "Quote", "PVC", "standard", "simple",
                        2.0, 3.0, 6.0, 10.0, 50.0, 10.0, 0.0, 0.0, 60.0, createdAt = 5)
                } finally {
                    driver.close()
                }
            }

            assertFails {
                runBlocking { factory.create(PrototypeDatabase.Schema, config(name, 32)).close() }
            }

            factory.create(PrototypeDatabase.Schema, config(name, 31)).let { driver ->
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                    val db = PrototypeDatabase(driver)
                    assertEquals("iOS encrypted client", ClientRepository(db).all().single().name)
                    val projectId = ProjectRepository(db).all().single().id
                    assertEquals("Room", MeasurementRepository(db).forProject(projectId).single().name)
                    assertEquals(20.0, PriceRepository(db).all().single().price)
                    val estimateId = EstimateRepository(db).forProject(projectId).single().id
                    assertFails { ProjectRepository(db).delete(projectId) }
                    assertEquals(estimateId, EstimateRepository(db).find(estimateId)?.id)
                    val measurements = MeasurementRepository(db)
                    measurements.delete(measurements.forProject(projectId).single().id)
                    ProjectRepository(db).delete(projectId)
                    assertNull(EstimateRepository(db).find(estimateId))
                } finally {
                    driver.close()
                }
            }
        } finally {
            factory.deleteDatabase(name)
        }
        Unit
    }
    @Test fun ownedMeasurementDetailsReadVersionedGeometryAndReactToDeletion(): Unit = runBlocking {
        val name = "andrey-ios-measurement-${Random.nextLong().toString(16)}.db"
        try {
            val driver = factory.create(PrototypeDatabase.Schema, config(name, 47))
            try {
                val db = PrototypeDatabase(driver)
                val project = ProjectRepository(db).createReturningId(null, "iOS measurement project", createdAt = 1)
                val measurements = MeasurementRepository(db)
                val id = measurements.createReturningId(project, "iOS L room", shape = "polygon",
                    lengthMeters = 5.0, widthMeters = 4.0, areaSquareMeters = 14.0, perimeterMeters = 18.0,
                    drawingJson = AndroidMeasurementFixtures.sidesV3, createdAt = 2)
                val store = MeasurementDetailsStore(db)
                val loaded = assertIs<MeasurementDetailsState.Loaded>(store.observe(project, id)
                    .first { it !is MeasurementDetailsState.Loading })
                val geometry = assertIs<MeasurementGeometryView.Captured>(loaded.data.geometry).geometry
                assertEquals(6, geometry.sides.size)
                assertEquals(AndroidMeasurementFixtures.sidesV3, measurements.find(id)?.drawingJson)
                assertEquals(MeasurementDetailsState.Missing, store.observe(project + 999, id)
                    .first { it !is MeasurementDetailsState.Loading })
                measurements.delete(id)
                assertEquals(MeasurementDetailsState.Missing, store.observe(project, id)
                    .first { it !is MeasurementDetailsState.Loading })
            } finally { driver.close() }
        } finally { factory.deleteDatabase(name) }
        Unit
    }

}
