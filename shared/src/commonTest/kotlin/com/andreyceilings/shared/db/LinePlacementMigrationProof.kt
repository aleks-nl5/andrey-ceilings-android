package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import kotlin.test.*

object LinePlacementMigrationProof {
    val v11Schema=object:SqlSchema<QueryResult.Value<Unit>> {
        override val version=11L
        override fun create(driver:SqlDriver):QueryResult.Value<Unit> {
            WallAnchorMigrationProof.v10Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver,10,11)
            PriceCatalogSchema.verifyStructure(driver,11)
            return QueryResult.Unit
        }
        override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> = error("Historical v11 fixture")
    }
    suspend fun lifecycle(production:SqlSchema<QueryResult.Value<Unit>>,open:suspend(SqlSchema<QueryResult.Value<Unit>>)->SqlDriver) {
        val (before,schema)=open(v11Schema).use {driver ->
            val f=LegacyWallSeedProof.populate(driver)
            LegacyWallSeedProof.decorateV10(driver,f)
            LegacyWallSeedProof.snapshot(driver,true) to PriceNameMigrationProof.schema(driver)
        }
        val failed=object:SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> {
                val injected=object:SqlDriver by driver {
                    override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
                        if(sql.trimStart().startsWith("CREATE VIEW ceiling_operation_lines"))error("Injected after additive v12 DDL")
                        return driver.execute(identifier,sql,parameters,binders)
                    }
                }
                return production.migrate(injected,oldVersion,newVersion,*callbacks)
            }
        }
        assertFails {open(failed).close()}
        open(v11Schema).use {driver ->
            assertEquals(11,PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version"))
            assertEquals(before,LegacyWallSeedProof.snapshot(driver,true))
            assertEquals(schema,PriceNameMigrationProof.schema(driver))
        }
        open(production).use {driver ->
            PriceCatalogSchema.verifyStructure(driver)
            assertEquals(before,LegacyWallSeedProof.snapshot(driver,true))
            assertEquals(production.version,PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version"))
            assertEquals(0,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM line_placements"))
            LinePlacementProof.run(driver)
        }
        open(production).use {driver ->PriceCatalogSchema.verifyStructure(driver);assertEquals(production.version,PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version"))}
    }
}
