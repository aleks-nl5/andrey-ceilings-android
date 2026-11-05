package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.*
import kotlin.test.*

/** Builds an isolated v4 fixture, including immutable history with old price references. */
object PriceCatalogMigrationProof {
    val legacySchema: SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 4L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            PrototypeDatabase.Schema.create(driver)
            // Reconstruct the authentic historical schema, including removal of v10-only objects.
            MeasurementElementMigrationProof.rows(driver,
                "SELECT type,name FROM sqlite_master WHERE name LIKE 'ceiling_%' AND type IN ('trigger','view','table') ORDER BY CASE type WHEN 'trigger' THEN 0 WHEN 'view' THEN 1 ELSE 2 END",2)
                .forEach { row -> driver.execute(null,"DROP ${checkNotNull(row[0]).uppercase()} ${checkNotNull(row[1])}",0) }
            // This fixture reconstructs authentic v4, so remove all later v8 objects too.
            MeasurementElementMigrationProof.rows(driver,
                "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'point_%'", 1)
                .forEach { driver.execute(null, "DROP TRIGGER ${checkNotNull(it.single())}", 0) }
            driver.execute(null, "DROP TABLE point_position_operations", 0)
            driver.execute(null, "DROP TABLE point_placements", 0)
            listOf("DROP VIEW measurement_element_records", "DROP TABLE measurement_elements", "DROP VIEW price_catalog_items", "DROP TABLE price_items", "DROP TABLE price_groups",
                "DROP TABLE price_catalog_state", """CREATE TABLE price_items (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, title TEXT NOT NULL,
                    category TEXT NOT NULL, unit TEXT NOT NULL, price REAL NOT NULL, updatedAt INTEGER NOT NULL,
                    uuid TEXT NOT NULL UNIQUE, deletedAt INTEGER)""",
                "CREATE UNIQUE INDEX index_price_items_category_code ON price_items(category, code) WHERE deletedAt IS NULL"
            ).forEach { driver.execute(null, it, 0) }
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
            error("Fixture cannot migrate")
    }

    // Populate AFTER opening the v4 driver. Native SQLiter owns the schema
    // callback transaction; normal repository transactions belong outside it.
    fun populateLegacy(driver: SqlDriver) {
        populateHistory(driver)
        driver.execute(null, """INSERT INTO price_items(id, code, title, category, unit, price, updatedAt, uuid)
            VALUES (900, 'canvas.pvc_matte', 'OLD TEST PRICE', 'OLD CATEGORY', 'м²', 9999, 14, 'old-uuid')""", 0)
        driver.execute(null, """INSERT INTO price_items(code, title, category, unit, price, updatedAt, uuid, deletedAt)
            VALUES ('old.deleted', 'Deleted', 'OLD CATEGORY', 'шт.', 1, 15, 'old-deleted', 16)""", 0)
    }

    fun populateHistory(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        ClientRepository(db).create("Preserve client", createdAt = 10)
        val client = ClientRepository(db).all().single().id
        val project = ProjectRepository(db).createReturningId(client, "Preserve project", createdAt = 11)
        MeasurementRepository(db).create(project, "Preserve geometry", drawingJson = "{\"frozen\":true}", createdAt = 12)
        EstimateRepository(db).create(project, "Preserve estimate", "PVC", "standard", "simple",
            2.0, 3.0, 6.0, 10.0, 50.0, 10.0, 0.0, 0.0, 60.0,
            detailsJson = "{\"oldPriceUuid\":\"old-uuid\",\"pdf\":\"unchanged\"}", createdAt = 13)
        ImmutableEstimateRepository(db).createSeries(project, 2026, 13,
            ImmutableEstimateDraft("{\"priceUuid\":\"old-uuid\"}", "{\"frozen\":true}", 0.01, "kmp-1", 9999.0,
                listOf(ImmutableEstimateLine("canvas.pvc_matte", "OLD TEST PRICE", "м²", 1.0, 9999.0, 9999.0, priceItemUuid = "old-uuid"))))
    }

    /** SQLite quote() preserves NULL/type/blob bytes; compare every non-price column. */
    fun untouchedRows(driver: SqlDriver): List<List<String?>> = listOf(
        "clients", "projects", "room_measurements", "estimates", "estimate_series", "estimate_revisions",
        "estimate_revision_lines", "estimate_number_settings", "prototype_client"
    ).flatMap { table ->
        val columns = driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names.add(checkNotNull(cursor.getString(1)))
            QueryResult.Value(names.sorted())
        }, 0).value
        driver.executeQuery(null, "SELECT " + columns.joinToString { "quote($it)" } +
            " FROM $table ORDER BY " + columns.indices.joinToString { (it + 1).toString() }, { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) rows.add(listOf(table) + columns.indices.map { cursor.getString(it) })
            QueryResult.Value(rows)
        }, 0).value
    }

    fun assertReplaced(database: PrototypeDatabase) {
        PriceCatalogPersistenceProof.starter(database)
        val prices = PriceRepository(database)
        assertTrue(prices.all().none { it.id == 900L || it.uuid == "old-uuid" })
        assertTrue(prices.tombstones().isEmpty())
        val series = ImmutableEstimateRepository(database).seriesForProject(ProjectRepository(database).all().single().id).single()
        val preview = EstimateRecalculationRepository(database).previewLatest(series.seriesUuid)
        assertFalse(preview.canConfirm)
        assertEquals(setOf(RecalculationProblem.MISSING_PRICE), preview.problems)
        assertEquals("Preserve client", ClientRepository(database).all().single().name)
        assertEquals("Preserve project", ProjectRepository(database).all().single().title)
        assertEquals("{\"frozen\":true}", MeasurementRepository(database).all().single().drawingJson)
        assertEquals("{\"oldPriceUuid\":\"old-uuid\",\"pdf\":\"unchanged\"}", EstimateRepository(database).all().single().detailsJson)
    }

    /** Failure after several seed inserts proves rollback of DDL and partial seed together. */
    fun failing(driver: SqlDriver): SqlDriver = object : SqlDriver by driver {
        private var inserts = 0
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            if (sql.contains("INSERT INTO price_items") && ++inserts == 4) error("Injected seed failure")
            return driver.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    fun assertOldPrice(driver: SqlDriver) {
        assertEquals("old-uuid", driver.executeQuery(null,
            "SELECT uuid FROM price_items WHERE id = 900", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            }, 0).value)
        assertEquals(0L, driver.executeQuery(null,
            "SELECT count(*) FROM sqlite_master WHERE name = 'price_groups'", { cursor ->
                cursor.next(); QueryResult.Value(cursor.getLong(0))
            }, 0).value)
    }
}
