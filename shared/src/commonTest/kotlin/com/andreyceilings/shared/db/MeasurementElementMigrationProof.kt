package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

/** Actual pre-P3A DDL, built through unchanged historical migrations. */
object MeasurementElementMigrationProof {
    val v6Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 6L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            PriceNameMigrationProof.v5Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 5, 6)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
            vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Fixture cannot migrate")
    }

    fun populate(driver: SqlDriver) {
        com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(PrototypeDatabase(driver))
        PriceCatalogMigrationProof.populateHistory(driver)
        // Keep edited/deleted catalog metadata and native geometry bytes, including Unicode.
        driver.execute(null, "UPDATE room_measurements SET drawingJson='{\"precision\":0.001,\"frozen\":true}', areaSquareMeters=12.345, perimeterMeters=14.321", 0)
        driver.execute(null, "UPDATE price_items SET price=987.65, updatedAt=300 WHERE code='light.spot'", 0)
        driver.execute(null, "UPDATE price_items SET deletedAt=301, updatedAt=301 WHERE code='light.line'", 0)
    }

    fun snapshot(driver: SqlDriver): List<List<String?>> = rows(driver,
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('measurement_elements','point_placements') ORDER BY name", 1)
        .map { checkNotNull(it.single()) }.plus("sqlite_sequence").flatMap { table ->
            val columns = rows(driver, "PRAGMA table_info($table)", 6).map { checkNotNull(it[1]) }
            rows(driver, "SELECT " + columns.joinToString { "quote($it)" } + " FROM $table ORDER BY " +
                columns.indices.joinToString { (it + 1).toString() }, columns.size).map { listOf(table) + it }
        }

    fun rows(driver: SqlDriver, sql: String, count: Int): List<List<String?>> = driver.executeQuery(null, sql, { cursor ->
        val result = mutableListOf<List<String?>>()
        while (cursor.next().value) result.add((0 until count).map { cursor.getString(it) })
        QueryResult.Value(result)
    }, 0).value

    fun failing(driver: SqlDriver): SqlDriver = object : SqlDriver by driver {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (sql.trimStart().startsWith("CREATE INDEX index_measurement_elements")) error("Injected interruption after element DDL")
            return driver.execute(identifier, sql, parameters, binders)
        }
    }

    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>,
        open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val old = open(v6Schema)
        val before: List<List<String?>>
        val oldStructure: List<List<String?>>
        try {
            populate(old)
            before = snapshot(old)
            oldStructure = PriceNameMigrationProof.schema(old)
        } finally { old.close() }
        val failingSchema = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                vararg callbacks: AfterVersion) = production.migrate(failing(driver), oldVersion, newVersion, *callbacks)
        }
        assertFails { open(failingSchema).close() }
        val unchanged = open(v6Schema)
        try {
            assertEquals(before, snapshot(unchanged))
            assertEquals(oldStructure, PriceNameMigrationProof.schema(unchanged), "DDL and version roll back together")
        } finally { unchanged.close() }
        val migrated = open(production)
        val parent: String
        try {
            assertEquals(9L, PriceNameUniquenessProof.scalar(migrated, "PRAGMA user_version"))
            assertEquals(before, snapshot(migrated), "All old rows, types, UUIDs, keys, precision, history and sequences survive")
            assertStructure(migrated)
            val db = PrototypeDatabase(migrated)
            parent = MeasurementRepository(db).all().single().uuid
            MeasurementElementRepository(db).createWish(parent, "Persisted", 400, uuid = "persisted")
        } finally { migrated.close() }
        val reopened = open(production)
        try {
            assertEquals(before, snapshot(reopened))
            assertStructure(reopened)
            assertEquals("Persisted", MeasurementElementRepository(PrototypeDatabase(reopened)).find("persisted")?.label)
            assertEquals(parent, MeasurementElementRepository(PrototypeDatabase(reopened)).find("persisted")?.measurementUuid)
        } finally { reopened.close() }
    }

    fun assertStructure(driver: SqlDriver) {
        assertEquals(20, rows(driver, "PRAGMA table_info(measurement_elements)", 6).size)
        assertEquals(setOf("room_measurements", "price_items"), rows(driver,
            "PRAGMA foreign_key_list(measurement_elements)", 8).map { it[2] }.toSet())
        assertEquals(listOf("measurementUuid", "deletedAt", "createdAt", "uuid"), rows(driver,
            "PRAGMA index_info(index_measurement_elements_parent_active_order)", 3).map { it[2] })
        assertEquals(1, rows(driver, "SELECT sql FROM sqlite_master WHERE name='measurement_element_records'", 1).size)
    }

    fun sqlGuards(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "SQL guards", createdAt = 1)
        val id = MeasurementRepository(db).createReturningId(project, "SQL guards", createdAt = 2)
        val parent = checkNotNull(MeasurementRepository(db).find(id)).uuid
        val repo = MeasurementElementRepository(db)
        repo.createWish(parent, "SQL guards", 3, uuid = "sql-guard")
        for (invalid in listOf(
            "kind='other'", "kind='catalog_item'", "approvalStatus='placed'", "quantityKind='other'",
            "quantityMilli=1000", "unit='шт.',quantityKind='exact',quantityMilli=2500",
            "unit='шт.',quantityKind='exact',quantityMilli=0", "unit='custom',quantityKind='exact',quantityMilli=9223372036854775808",
            "unit='custom',quantityKind='exact',quantityMilli=1.5", "unit='custom',quantityKind='range',quantityMinMilli=6000,quantityMaxMilli=4000",
            "unit='custom',quantityKind='range',quantityMinMilli=4000,quantityMaxMilli=4000",
            "quantityKind='ceiling_area'", "placementStatus='approved'", "placedQuantityMilli=1000", "label=' '",
            "priceItemCode='placeholder'", "quantityMode='manual'", "placementKind='point'", "updatedAt=9223372036854775808"
        )) assertFails("CHECK must reject $invalid") {
            driver.execute(null, "UPDATE measurement_elements SET $invalid WHERE uuid='sql-guard'", 0)
        }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        assertFails { driver.execute(null, "UPDATE measurement_elements SET measurementUuid='missing' WHERE uuid='sql-guard'", 0) }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        assertFailsWith<MeasurementElementFailure.ParentUnavailable> { repo.createWish("missing", "Missing even without FK", 4) }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        assertTrue(rows(driver, "PRAGMA foreign_key_check", 4).isEmpty())
    }
}
