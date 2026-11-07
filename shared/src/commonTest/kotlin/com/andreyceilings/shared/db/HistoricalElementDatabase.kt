package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*

/** Test-only bridge for seeding frozen v7–v14 schemas using current geometry/placement fixtures.
 * Executes the historical role-less SQL on the physical old DB; never changes DDL or migration reads.
 * All migration assertions still inspect the original driver and every original column.
 */
fun historicalElementDatabase(driver: SqlDriver): PrototypeDatabase {
    val hasRole = driver.executeQuery(null, "PRAGMA table_info(measurement_elements)", { c ->
        var found = false
        while (c.next().value) if (c.getString(1) == "businessRole") found = true
        QueryResult.Value(found)
    }, 0).value
    if (hasRole) return PrototypeDatabase(driver)
    return PrototypeDatabase(object : SqlDriver by driver {
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            var transformed = sql.replace(Regex("\\b(?:measurement_element_records|e)\\.businessRole"), "NULL AS businessRole")
            var skip: Int? = null
            if (sql.startsWith("INSERT INTO measurement_elements")) {
                skip = 15
                transformed = transformed.replace(", businessRole, createdAt", ", createdAt")
                var parameter = 0
                transformed = Regex("\\?").replace(transformed) { if (parameter++ == skip) "__REMOVE_ROLE__" else "?" }
                    .replace("__REMOVE_ROLE__, ", "")
            } else if (sql.startsWith("UPDATE measurement_elements") && sql.contains("businessRole = ?")) {
                skip = if (sql.contains("kind = 'catalog_item'")) 1 else 3
                transformed = transformed.replace(" businessRole = ?,", "")
            }
            val omitted = skip
            return driver.executeQuery(null, transformed, mapper, parameters - if (omitted == null) 0 else 1,
                if (binders == null) null else {
                    {
                        val actual = this
                        binders(object : SqlPreparedStatement {
                            fun mapped(index: Int) = if (omitted != null && index > omitted) index - 1 else index
                            override fun bindBytes(index: Int, bytes: ByteArray?) { if (index != omitted) actual.bindBytes(mapped(index), bytes) }
                            override fun bindLong(index: Int, long: Long?) { if (index != omitted) actual.bindLong(mapped(index), long) }
                            override fun bindDouble(index: Int, double: Double?) { if (index != omitted) actual.bindDouble(mapped(index), double) }
                            override fun bindString(index: Int, string: String?) { if (index != omitted) actual.bindString(mapped(index), string) }
                            override fun bindBoolean(index: Int, boolean: Boolean?) { if (index != omitted) actual.bindBoolean(mapped(index), boolean) }
                        })
                    }
                })
        }
    })
}
