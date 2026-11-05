package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import kotlin.test.*

/** Historical records are readable data, not instructions to invent a measured contour. */
object LegacyWallSeedProof {
    val unsupportedDrawings = listOf("{}","not-json","null","[]",
        """{"precisionStepMeters":0.01,"vertices":[{"x":0,"y":0},{"x":4,"y":0},{"x":4,"y":3},{"x":0,"y":3}]}""",
        """{"shape":"rectangle","geometrySchemaVersion":2,"precisionStepMeters":0.01,"vertices":[{"x":0,"y":0},{"x":4,"y":0},{"x":4,"y":3},{"x":0,"y":3}]}""",
        """{"shape":"rectangle","geometrySchemaVersion":2,"precisionStepMeters":0.01,"vertices":["old",null,3,{}]}""",
        """{"shape":"rectangle","geometrySchemaVersion":2,"precisionStepMeters":0.01,"vertices":[{"xMm":0,"yMm":0},{"xMm":4000.5,"yMm":0},{"xMm":4000,"yMm":3000},{"xMm":0,"yMm":3000}]}""")

    fun populate(driver:SqlDriver,includeUnsupported:Boolean=true):PointPlacementProof.Fixture {
        MeasurementElementMigrationProof.populate(driver)
        val f=WallAnchorMigrationProof.populateV9(driver)
        val repo=MeasurementRepository(PrototypeDatabase(driver))
        (if(includeUnsupported) unsupportedDrawings else listOf("{}")).forEachIndexed { i,source ->
            repo.createReturningId(f.project,"Legacy $i",lengthMeters=4.12345,widthMeters=3.0,
                areaSquareMeters=12.37035,perimeterMeters=14.2469,drawingJson=source,createdAt=40+i.toLong(),recordUuid="legacy-source-$i")
        }
        return f
    }
    fun snapshot(driver:SqlDriver)=PointPlacementMigrationProof.snapshot(driver)+MeasurementElementMigrationProof.rows(driver,
        "SELECT quote(uuid),quote(elementUuid),quote(xMm),quote(yMm),quote(contourUpdatedAt),quote(createdAt),quote(updatedAt),quote(deletedAt),quote(precision),quote(lastMoveUuid) FROM point_placements ORDER BY uuid",10)

    suspend fun lifecycle(production:SqlSchema<QueryResult.Value<Unit>>,version:Long,
        open:suspend(SqlSchema<QueryResult.Value<Unit>>)->SqlDriver) {
        val historical=if(version==9L)WallAnchorMigrationProof.v9Schema else WallAnchorMigrationProof.v10Schema
        val(before,oldSchema,oldWalls)=open(historical).use { driver ->
            populate(driver,version==9L)
            Triple(snapshot(driver),PriceNameMigrationProof.schema(driver),
                if(version==10L)MeasurementElementMigrationProof.rows(driver,"SELECT * FROM ceiling_walls ORDER BY uuid",9) else emptyList())
        }
        val interrupted=object:SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> {
                val failing=object:SqlDriver by driver {
                    override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
                        if(sql.trimStart().startsWith("DROP TRIGGER ceiling_unplaced_geometry_walls")) error("Injected corrective migration interruption")
                        return driver.execute(identifier,sql,parameters,binders)
                    }
                }
                return production.migrate(failing,oldVersion,newVersion,*callbacks)
            }
        }
        assertFails { open(interrupted).close() }
        open(historical).use { driver ->
            assertEquals(version,PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version"))
            assertEquals(before,snapshot(driver));assertEquals(oldSchema,PriceNameMigrationProof.schema(driver))
        }
        open(production).use { driver ->
            assertEquals(production.version,PriceNameUniquenessProof.scalar(driver,"PRAGMA user_version"))
            assertEquals(before,snapshot(driver))
            if(version==10L)assertEquals(oldWalls,MeasurementElementMigrationProof.rows(driver,"SELECT * FROM ceiling_walls ORDER BY uuid",9))
            assertEquals(0L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_walls WHERE measurementUuid LIKE 'legacy-source-%'"))
            assertEquals(0L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM point_placements WHERE mountingJson!='{}'"))
            val db=PrototypeDatabase(driver);val project=ProjectRepository(db).all().first().id
            assertTrue(ClientRepository(db).createReturningId("After migration",createdAt=500)>0)
            assertTrue(ProjectRepository(db).createReturningId(null,"After migration",createdAt=501)>0)
            assertTrue(MeasurementRepository(db).createReturningId(project,"Historical only",drawingJson=unsupportedDrawings[5],createdAt=502)>0)
            assertTrue(MeasurementElementMigrationProof.rows(driver,"PRAGMA foreign_key_check",4).isEmpty())
        }
        open(production).use { driver -> assertTrue(ClientRepository(PrototypeDatabase(driver)).all().any {it.name=="After migration"}) }
    }
}
