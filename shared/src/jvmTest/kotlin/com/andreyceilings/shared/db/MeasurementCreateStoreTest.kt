package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MeasurementCreateStoreTest {
    @Test fun exitGuardKeepsSlowAtomicSaveVisibleAndDeliversItsOneResult(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            MeasurementCreateNavigationProof.run(driver)
        }
    }

    @Test fun sharedCreationProofCoversShapesPrecisionReturningIdFlowAndFailurePaths(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            MeasurementCreatePersistenceProof.run(PrototypeDatabase(driver))
        }
    }

    @Test fun unavailableDatabaseDoesNotReportSuccessAndRollsBackFailedInsert(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PrototypeDatabase.Schema.create(driver)
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "P", createdAt = 1)
        val store = MeasurementCreateStore(db) { 2 }
        val draft = MeasurementCreateFixtures.draft(project, "polygon", MeasurementPrecision.ONE_MILLIMETER)
        // An actual SQLite insert failure after the parent check leaves no partial row.
        driver.execute(null, "CREATE TRIGGER reject_measurement BEFORE INSERT ON room_measurements BEGIN SELECT RAISE(ABORT, 'test write failure'); END", 0)
        val failed = MeasurementCreateModel(store, draft)
        failed.save()
        assertNull(failed.state.value.savedId)
        assertFalse(failed.state.value.saving)
        assertNotNull(failed.state.value.error)
        assertTrue(MeasurementRepository(db).all().isEmpty())
        driver.execute(null, "DROP TRIGGER reject_measurement", 0)
        failed.save()
        assertNotNull(failed.state.value.savedId)
        assertEquals(1, MeasurementRepository(db).all().size)
        driver.close()
        val unavailable = MeasurementCreateModel(store, draft)
        unavailable.save()
        assertNull(unavailable.state.value.savedId)
        assertNotNull(unavailable.state.value.error)
        assertEquals(MeasurementCreateParent.Unavailable, store.observeParent(project).first { it !is MeasurementCreateParent.Loading })
        val locked = MeasurementCreateModel(null, draft)
        locked.save()
        assertNull(locked.state.value.savedId)
        assertNotNull(locked.state.value.error)
    }
}
