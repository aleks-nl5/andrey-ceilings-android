package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import kotlin.test.*

/** Authentic v5 structure from unchanged 4.sqm, populated with edited values/history. */
object PriceNameMigrationProof {
    val v5Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 5L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            PriceCatalogMigrationProof.legacySchema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 4, 5)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
            error("Fixture cannot migrate")
    }

    fun populate(driver: SqlDriver, conflict: CatalogNameKind? = null, empty: Boolean = false) {
        PriceCatalogMigrationProof.populateHistory(driver)
        listOf(
            "INSERT INTO price_catalog_state VALUES (1,1)",
            "INSERT INTO price_groups(id,uuid,name,defaultUnit,createdAt,updatedAt) VALUES (41,'g1',' ПОЛОТНА редаговані ','м²',31,32)",
            "INSERT INTO price_groups(id,uuid,name,defaultUnit,createdAt,updatedAt) VALUES (42,'g2','ҐЄІЇ ASCII','шт.',33,34)",
            "INSERT INTO price_groups(id,uuid,name,createdAt,updatedAt,deletedAt) VALUES (43,'gd','полотна редаговані',35,36,37)",
            "INSERT INTO price_items(id,uuid,code,title,groupUuid,unit,quantityMode,placementKind,price,updatedAt) VALUES (71,'i1','light.spot',' Точковий світильник редагований ','g1','м²','ceiling_area','area',456.78,38)",
            "INSERT INTO price_items(id,uuid,code,title,groupUuid,unit,quantityMode,placementKind,price,updatedAt) VALUES (72,'i2','custom','ҐЄІЇ ASCII','g2','шт.','manual','point',123.45,39)",
            "INSERT INTO price_items(id,uuid,code,title,groupUuid,unit,price,updatedAt,deletedAt) VALUES (73,'id','deleted','точковий світильник редагований','g1','шт.',99,40,41)"
        ).forEach { driver.execute(null, it, 0) }
        when (conflict) {
            CatalogNameKind.GROUP -> driver.execute(null, "UPDATE price_groups SET name='полотна РЕДАГОВАНІ' WHERE uuid='g2'", 0)
            CatalogNameKind.ITEM -> driver.execute(null, "UPDATE price_items SET title='точковий СВІТИЛЬНИК редагований' WHERE uuid='i2'", 0)
            null -> Unit
        }
        if (empty) driver.execute(null, "UPDATE price_items SET title='   ' WHERE uuid='i2'", 0)
    }

    fun snapshot(driver: SqlDriver): List<List<String?>> = PriceCatalogMigrationProof.untouchedRows(driver) +
        listOf("price_groups", "price_items", "price_catalog_state", "sqlite_sequence").flatMap { table ->
            val columns = driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) cursor.getString(1)?.takeUnless { it == "nameKey" || it == "titleKey" }?.let(names::add)
                QueryResult.Value(names)
            }, 0).value
            rows(driver, "SELECT " + columns.joinToString { "quote($it)" } + " FROM $table ORDER BY 1", columns.size)
                .map { listOf(table) + it }
        }

    fun schema(driver: SqlDriver): List<List<String?>> = rows(driver,
        "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name", 4) +
        listOf(listOf(PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version").toString()))

    private fun rows(driver: SqlDriver, sql: String, columns: Int): List<List<String?>> =
        driver.executeQuery(null, sql, { cursor ->
            val result = mutableListOf<List<String?>>()
            while (cursor.next().value) result.add((0 until columns).map { cursor.getString(it) })
            QueryResult.Value(result)
        }, 0).value

    fun assertMigrated(driver: SqlDriver, before: List<List<String?>>) {
        assertEquals(before, snapshot(driver), "Every preexisting business value and sequence must survive")
        PriceNameUniquenessProof.assertKeys(driver)
        assertEquals(1L, PrototypeDatabase(driver).priceGroupsQueries.selectSeedVersion().executeAsOne())
        assertEquals(2L, PriceRepository(PrototypeDatabase(driver)).count())
        assertEquals(2, PriceGroupRepository(PrototypeDatabase(driver)).all().size)
        assertEquals(1, PriceRepository(PrototypeDatabase(driver)).tombstones().size)
    }

    fun failingBackfill(driver: SqlDriver): SqlDriver = object : SqlDriver by driver {
        private var writes = 0
        override fun execute(identifier: Int?, sql: String, parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if ((sql.startsWith("UPDATE price_groups SET nameKey") || sql.startsWith("UPDATE price_items SET titleKey")) && ++writes == 5)
                error("Injected interruption during key backfill")
            return driver.execute(identifier, sql, parameters, binders)
        }
    }

    /** Runs through each platform's real schema callback and persistent version. */
    suspend fun lifecycle(
        scenario: String,
        production: SqlSchema<QueryResult.Value<Unit>>,
        open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver
    ) {
        val initial = open(v5Schema)
        val before: List<List<String?>>
        val oldSchema: List<List<String?>>
        try {
            populate(initial, when (scenario) {
                "group" -> CatalogNameKind.GROUP
                "item" -> CatalogNameKind.ITEM
                else -> null
            }, scenario == "empty")
            before = snapshot(initial)
            oldSchema = schema(initial)
        } finally { initial.close() }
        if (scenario != "ok") {
            val failing = object : SqlSchema<QueryResult.Value<Unit>> by production {
                override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                    vararg callbacks: AfterVersion) = production.migrate(
                    if (scenario == "interrupted") failingBackfill(driver) else driver,
                    oldVersion, newVersion, *callbacks)
            }
            val failure = try {
                open(failing).close()
                fail("Expected rejected v5 migration")
            } catch (failure: Throwable) { failure }
            // Platform helpers can wrap callback exceptions; inspect the cause chain.
            if (scenario != "interrupted") {
                var cause: Throwable? = failure
                while (cause != null && cause !is CatalogNameMigrationConflict) cause = cause.cause
                val conflict = assertIs<CatalogNameMigrationConflict>(cause)
                assertEquals(if (scenario == "group") CatalogNameKind.GROUP else CatalogNameKind.ITEM, conflict.kind)
                assertEquals(scenario == "empty", conflict.emptyName)
            }
            val unchanged = open(v5Schema)
            try {
                assertEquals(oldSchema, schema(unchanged), "Failed migration must preserve schema and user_version")
                assertEquals(before, snapshot(unchanged), "Failed migration must preserve every business value")
            } finally { unchanged.close() }
            if (scenario != "interrupted") return
        }
        val migrated = open(production)
        try {
            assertEquals(6L, PriceNameUniquenessProof.scalar(migrated, "PRAGMA user_version"))
            assertMigrated(migrated, before)
        } finally { migrated.close() }
        val reopened = open(production)
        try { assertMigrated(reopened, before) } finally { reopened.close() }
    }
}
