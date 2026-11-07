package com.andreyceilings.shared.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.andreyceilings.shared.prices.StarterPriceCatalog

/** Ordinal only: never publishes failing SQL, bound values or exception messages. */
class MigrationStatementFailure(val statement: Int, cause:Throwable) : IllegalStateException("Schema migration statement failed",cause) {
    init {require(statement>0)}
}

/** Production schema: structure + starter rows + marker commit or roll back together. */
object PriceCatalogSchema : SqlSchema<QueryResult.Value<Unit>> {
    /** Read-only full fingerprint audit, also used by physical platform proofs. */
    fun verifyStructure(driver:SqlDriver,version:Long=this.version) {
        verifyMeasurementElementStructure(driver, version)
        verifyPointPlacementStructure(driver, version)
    }

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
        verifyStructure(driver)
        return QueryResult.Unit
    }

    internal fun migrateInTransaction(driver: SqlDriver, oldVersion: Long, newVersion: Long,
        vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        // Old draft APKs distributed two exact v10 schemas. Unknown/partial/hybrid schemas
        // must fail BEFORE corrective DDL can accidentally repair away their evidence.
        if(oldVersion in 8L..14L) {
            verifyMeasurementElementStructure(driver, oldVersion)
            verifyPointPlacementStructure(driver,oldVersion)
        }
        var statement=0
        val observed=object:SqlDriver by driver {
            override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(app.cash.sqldelight.db.SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
                statement++
                return try {driver.execute(identifier,sql,parameters,binders)}
                catch(failure:Throwable){throw MigrationStatementFailure(statement,failure)}
            }
        }
        PrototypeDatabase.Schema.migrate(observed, oldVersion, newVersion, *callbacks)
        if (oldVersion < 6L && newVersion >= 6L) backfillPriceNameKeys(driver)
        if (newVersion >= 6L) StarterPriceCatalog.initializeInTransaction(PrototypeDatabase(driver))
        if (newVersion >= 8L) verifyStructure(driver, newVersion)
        return QueryResult.Unit
    }
}
