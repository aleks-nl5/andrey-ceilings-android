package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*

/**
 * Only for NativeSqliteDriver/SQLiter schema callbacks. SQLiter 1.3.3 wraps
 * create/upgrade AND user_version in its own physical transaction, while the
 * callback's borrowed SqlDriver cannot see it. Starting another BEGIN fails.
 * Reuse that transaction for the same shared DDL + seed; never suppress errors.
 * This adapter does not introduce a runnable iOS host.
 */
object IosPriceCatalogSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = PriceCatalogSchema.version
    override fun create(driver: SqlDriver) = PriceCatalogSchema.createInTransaction(driver)
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
        vararg callbacks: AfterVersion) = PriceCatalogSchema.migrateInTransaction(driver, oldVersion, newVersion, *callbacks)
}
