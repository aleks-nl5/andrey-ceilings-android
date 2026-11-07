package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.prices.StarterPriceCatalog

/** Narrow v3–v7 Windows probe for installations that did not persist user_version. */
fun openDesktopPriceCatalog(driver: SqlDriver, previouslyExisting: Boolean) {
    val database = PrototypeDatabase(driver)
    val persistedVersion = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
        cursor.next(); QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }, 0).value
    check(persistedVersion <= PriceCatalogSchema.version) { "Unsupported future database version" }
    database.transaction {
        // Acquire the writer lock before probing. Two concurrent first opens must
        // not both observe the old schema. Preserve the old token until DDL succeeds;
        // even this lock-acquiring write rolls back on any failure.
        driver.execute(null, "PRAGMA user_version = $persistedVersion", 0)
        fun columns(table: String): Set<String> = driver.executeQuery(null,
            "PRAGMA table_info($table)", { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) cursor.getString(1)?.let(names::add)
                QueryResult.Value(names)
            }, 0).value
        if (!previouslyExisting) {
            // A competing opener may have created it while we waited for the lock.
            if (columns("projects").isEmpty()) PriceCatalogSchema.create(driver)
        }
        val projectColumns = columns("projects")
        check("status" in projectColumns && "seriesUuid" in columns("estimates")) {
            "Існуюча SQLCipher БД має непідтримувану схему. Оновлення скасовано."
        }
        val priceColumns = columns("price_items")
        val oldVersion = when {
            "groupUuid" in priceColumns -> {
                check("paymentStatus" in projectColumns && "quantityMode" in priceColumns &&
                    "placementKind" in priceColumns && "seedVersion" in columns("price_catalog_state")) {
                    "Неповна структура каталогу. Оновлення скасовано."
                }
                val groupColumns = columns("price_groups")
                check(("titleKey" in priceColumns) == ("nameKey" in groupColumns)) {
                    "Неповна структура ключів каталогу. Оновлення скасовано."
                }
                if ("titleKey" in priceColumns) {
                    if (columns("measurement_elements").isEmpty()) 6L else {
                        verifyMeasurementElementStructure(driver, if ("businessRole" in columns("measurement_elements")) 15L else 14L)
                        if (columns("point_placements").isEmpty()) {
                            val partial = driver.executeQuery(null,
                                "SELECT count(*) FROM sqlite_master WHERE name LIKE 'point_%'", { cursor ->
                                    cursor.next(); QueryResult.Value(cursor.getLong(0))
                                }, 0).value
                            check(partial == 0L) { "Partial point placement schema" }
                            7L
                        } else {
                            val pointColumns = columns("point_placements")
                            check(("precision" in pointColumns) == ("lastMoveUuid" in pointColumns)) { "Partial point position schema" }
                            check(("mountingJson" in pointColumns) == ("lastCeilingEditUuid" in pointColumns)) { "Partial mounting schema" }
                            val detected = if ("mountingJson" in pointColumns) {
                                if("areaVersions" in columns("ceiling_operation_results") || columns("area_features").isNotEmpty()) {
                                    val guarded=driver.executeQuery(null,"SELECT count(*) FROM sqlite_master WHERE name='ceiling_result_insert_guard'",{c ->c.next();QueryResult.Value(c.getLong(0))},0).value
                                    if ("businessRole" in columns("measurement_elements")) 15L else if(guarded==1L)14L else 13L
                                }
                                else if("lineVersions" in columns("ceiling_operation_results") || columns("line_placements").isNotEmpty()) 12L
                                else if(runCatching { verifyPointPlacementStructure(driver,11L) }.isSuccess) 11L else 10L
                            } else if ("precision" in pointColumns) 9L else 8L
                            verifyPointPlacementStructure(driver, detected)
                            detected
                        }
                    }
                } else {
                    check(columns("measurement_elements").isEmpty()) { "Partial measurement element schema" }
                    5L
                }
            }
            "category" in priceColumns -> if ("paymentStatus" in projectColumns) 4L else 3L
            else -> error("Непідтримувана структура прайсу. Оновлення скасовано.")
        }
        check(persistedVersion < 8L || oldVersion >= persistedVersion) { "Incomplete point placement schema" }
        if (oldVersion < PriceCatalogSchema.version)
            PriceCatalogSchema.migrate(driver, oldVersion, PriceCatalogSchema.version)
        verifyMeasurementElementStructure(driver)
        verifyPointPlacementStructure(driver)
        StarterPriceCatalog.initialize(database)
        driver.execute(null, "PRAGMA user_version = ${PriceCatalogSchema.version}", 0)
    }
}
