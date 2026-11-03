package com.andreyceilings.shared.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.andreyceilings.shared.prices.StarterPriceCatalog

/** Production schema: structure + starter rows + marker commit or roll back together. */
object PriceCatalogSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = PrototypeDatabase.Schema.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        PrototypeDatabase(driver).transaction {
            createInTransaction(driver)
        }
        return QueryResult.Unit
    }

    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
        vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        PrototypeDatabase(driver).transaction {
            migrateInTransaction(driver, oldVersion, newVersion, *callbacks)
        }
        return QueryResult.Unit
    }

    internal fun createInTransaction(driver: SqlDriver): QueryResult.Value<Unit> {
        PrototypeDatabase.Schema.create(driver)
        StarterPriceCatalog.initializeInTransaction(PrototypeDatabase(driver))
        return QueryResult.Unit
    }

    internal fun migrateInTransaction(driver: SqlDriver, oldVersion: Long, newVersion: Long,
        vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        PrototypeDatabase.Schema.migrate(driver, oldVersion, newVersion, *callbacks)
        if (oldVersion < 6L && newVersion >= 6L) backfillPriceNameKeys(driver)
        if (newVersion >= 6L) StarterPriceCatalog.initializeInTransaction(PrototypeDatabase(driver))
        return QueryResult.Unit
    }
}
