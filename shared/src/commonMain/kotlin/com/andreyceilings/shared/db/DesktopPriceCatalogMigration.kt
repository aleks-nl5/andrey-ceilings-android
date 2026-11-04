package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.prices.StarterPriceCatalog

/** Narrow v3/v4/v5/v6 Windows probe for installations that did not persist user_version. */
fun openDesktopPriceCatalog(driver: SqlDriver, previouslyExisting: Boolean) {
    val database = PrototypeDatabase(driver)
    database.transaction {
        // Acquire the writer lock before probing. Two concurrent first opens must
        // not both observe the old schema. The PRAGMA rolls back on any failure.
        driver.execute(null, "PRAGMA user_version = 6", 0)
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
                if ("titleKey" in priceColumns) 6L else 5L
            }
            "category" in priceColumns -> if ("paymentStatus" in projectColumns) 4L else 3L
            else -> error("Непідтримувана структура прайсу. Оновлення скасовано.")
        }
        if (oldVersion < 6L) PriceCatalogSchema.migrate(driver, oldVersion, 6L)
        StarterPriceCatalog.initialize(database)
    }
}
