package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*

/** The v16 settings migration intentionally changes only future numbering, tested in FrozenEstimateMigrationProof.
 * Legacy business snapshots still compare every number, UUID, prefix, width and counter. */
fun isP4C1AddedSnapshotColumn(table: String, column: String) =
    table == "estimate_series" && column == "publicNumber" ||
        table == "estimate_number_settings" && column in setOf("template", "suffixLabel")

/** Test seeding bridge only: write authentic UUID-era defaults and project the missing optional setting. */
fun historicalNumberDatabase(driver: SqlDriver): PrototypeDatabase = PrototypeDatabase(object : SqlDriver by driver {
    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        driver.execute(identifier, sql.replace("{sequence}-{suffix}", "{sequence}-{uuid}"), parameters, binders)
    override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> = driver.executeQuery(identifier,
        sql.replace("estimate_number_settings.suffixLabel", "'' AS suffixLabel").replace("estimate_series.publicNumber", "NULL AS publicNumber"), mapper, parameters, binders)
})
