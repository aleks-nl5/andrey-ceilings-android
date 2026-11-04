package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.shared.prices.normalizedNameKey

enum class CatalogNameKind { GROUP, ITEM }

/** Only conflicting identities are reported; no database dump or user labels. */
class CatalogNameMigrationConflict(
    val kind: CatalogNameKind,
    val recordUuids: List<String>,
    val emptyName: Boolean = false
) : IllegalStateException("Catalog name migration conflict: $kind, UUIDs=$recordUuids, empty=$emptyName")

/** Caller owns the physical DDL + backfill + user_version transaction. */
internal fun backfillPriceNameKeys(driver: SqlDriver) {
    data class Row(val uuid: String, val key: String, val active: Boolean)
    fun rows(table: String, name: String): List<Row> = driver.executeQuery(null,
        "SELECT uuid, $name, deletedAt FROM $table ORDER BY id", { cursor ->
            val rows = mutableListOf<Row>()
            while (cursor.next().value) rows.add(Row(checkNotNull(cursor.getString(0)),
                normalizedNameKey(checkNotNull(cursor.getString(1))), cursor.getLong(2) == null))
            QueryResult.Value(rows)
        }, 0).value

    val groups = rows("price_groups", "name")
    val items = rows("price_items", "title")
    fun validate(rows: List<Row>, kind: CatalogNameKind) {
        val keys = mutableMapOf<String, String>()
        rows.filter { it.active }.forEach { row ->
            if (row.key.isEmpty()) throw CatalogNameMigrationConflict(kind, listOf(row.uuid), emptyName = true)
            keys.put(row.key, row.uuid)?.let { first ->
                throw CatalogNameMigrationConflict(kind, listOf(first, row.uuid))
            }
        }
    }
    // Validate BOTH sets before any backfill; never merge, rename or delete.
    validate(groups, CatalogNameKind.GROUP)
    validate(items, CatalogNameKind.ITEM)
    fun fill(rows: List<Row>, table: String, column: String) = rows.forEach { row ->
        driver.execute(null, "UPDATE $table SET $column = ? WHERE uuid = ?", 2) {
            bindString(0, row.key)
            bindString(1, row.uuid)
        }
    }
    fill(groups, "price_groups", "nameKey")
    fill(items, "price_items", "titleKey")
}
