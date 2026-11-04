package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

object PointPositionMigrationProof {
    val v8Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 8L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            PointPlacementMigrationProof.v7Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 7, 8)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Fixture cannot migrate")
    }
    fun failing(driver: SqlDriver): SqlDriver = object : SqlDriver by driver {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.trimStart().startsWith("CREATE TABLE point_position_operations")) error("Injected v9 interruption after additive columns")
            return driver.execute(identifier, sql, parameters, binders)
        }
    }
    fun oldPoints(driver: SqlDriver) = MeasurementElementMigrationProof.rows(driver,
        "SELECT quote(uuid),quote(elementUuid),quote(xMm),quote(yMm),quote(contourUpdatedAt),quote(createdAt),quote(updatedAt),quote(deletedAt) FROM point_placements ORDER BY uuid", 8)

    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, populated: Boolean,
        open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val old = open(v8Schema)
        val before: List<List<String?>>; val beforePoints: List<List<String?>>; val structure: List<List<String?>>
        var fixture: PointPlacementProof.Fixture? = null
        try {
            com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(PrototypeDatabase(old))
            if (populated) {
                MeasurementElementMigrationProof.populate(old)
                val f = PointPlacementProof.fixture(PrototypeDatabase(old), "v8 captured")
                fixture = f
                old.execute(null, "INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt) VALUES('old-v8-point','${f.element.uuid}',1000,1000,${f.contourVersion},10,10)", 0)
            }
            before = PointPlacementMigrationProof.snapshot(old)
            beforePoints = oldPoints(old); structure = PriceNameMigrationProof.schema(old)
        } finally { old.close() }
        val bad = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) =
                production.migrate(failing(driver), oldVersion, newVersion, *callbacks)
        }
        assertFails { open(bad).close() }
        val rolledBack = open(v8Schema)
        try {
            assertEquals(before, PointPlacementMigrationProof.snapshot(rolledBack))
            assertEquals(beforePoints, oldPoints(rolledBack)); assertEquals(structure, PriceNameMigrationProof.schema(rolledBack))
            assertEquals(8L, PriceNameUniquenessProof.scalar(rolledBack, "PRAGMA user_version"))
        } finally { rolledBack.close() }
        val upgraded = open(production)
        val moved: PointPlacement?
        try {
            assertEquals(10L, PriceNameUniquenessProof.scalar(upgraded, "PRAGMA user_version"))
            assertEquals(before, PointPlacementMigrationProof.snapshot(upgraded))
            assertEquals(beforePoints, oldPoints(upgraded))
            val db = PrototypeDatabase(upgraded)
            moved = fixture?.let { f ->
                val point = assertNotNull(PointPlacementRepository(db).find("old-v8-point"))
                assertEquals(PointPrecision.APPROXIMATE, point.precision); assertNull(point.lastMoveUuid)
                val element = MeasurementElementRepository(db).find(f.element.uuid)!!
                PointPlacementRepository(db).movePoint(element.version, point, f.contourVersion, 1500, 1300,
                    PointPrecision.EXACT, 20, "persistent-v9-move").point
            }
        } finally { upgraded.close() }
        val reopened = open(production)
        try {
            assertEquals(moved, PointPlacementRepository(PrototypeDatabase(reopened)).find("old-v8-point"))
            if (fixture != null) {
                val e = MeasurementElementRepository(PrototypeDatabase(reopened)).find(fixture.element.uuid)!!
                assertEquals(1000L, e.placedQuantityMilli); assertEquals(ElementPlacement.EXACT, e.placement)
            }
            assertTrue(MeasurementElementMigrationProof.rows(reopened, "PRAGMA foreign_key_check", 4).isEmpty())
        } finally { reopened.close() }
    }
}
