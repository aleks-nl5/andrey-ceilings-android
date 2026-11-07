package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import kotlin.test.*

object WallAnchorMigrationProof {
    val v10Schema=object: SqlSchema<QueryResult.Value<Unit>> {
        override val version=10L
        override fun create(driver:SqlDriver):QueryResult.Value<Unit> {
            v9Schema.create(driver);PrototypeDatabase.Schema.migrate(driver,9,10)
            driver.execute(null,"DROP TRIGGER ceiling_measurement_walls",0)
            driver.execute(null,"DROP TRIGGER ceiling_unplaced_geometry_walls",0)
            deployedV10SeedTriggers.forEach { driver.execute(null,it,0) }
            return QueryResult.Unit
        }
        override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> = error("Historical fixture")
    }
    val v9Schema=object: SqlSchema<QueryResult.Value<Unit>> {
        override val version=9L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            PointPositionMigrationProof.v8Schema.create(driver);PrototypeDatabase.Schema.migrate(driver,8,9);return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver,oldVersion: Long,newVersion: Long,vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Historical fixture")
    }
    fun failing(driver: SqlDriver): SqlDriver=object:SqlDriver by driver {
        override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
            if(sql.trimStart().startsWith("CREATE TABLE ceiling_operations")) error("Injected v10 migration interruption")
            return driver.execute(identifier,sql,parameters,binders)
        }
    }
    private fun oldPoints(driver:SqlDriver)=MeasurementElementMigrationProof.rows(driver,
        "SELECT quote(uuid),quote(elementUuid),quote(xMm),quote(yMm),quote(contourUpdatedAt),quote(createdAt),quote(updatedAt),quote(deletedAt),quote(precision),quote(lastMoveUuid) FROM point_placements ORDER BY uuid",10)
    private fun receipts(driver:SqlDriver)=MeasurementElementMigrationProof.rows(driver,"SELECT quote(uuid),quote(pointUuid),quote(expectedPointUpdatedAt),quote(expectedElementUpdatedAt),quote(contourUpdatedAt),quote(xMm),quote(yMm),quote(precision),quote(createdAt) FROM point_position_operations ORDER BY uuid",9)
    fun populateV9(driver:SqlDriver):PointPlacementProof.Fixture {
        val db=historicalElementDatabase(driver)
        com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(db)
        val f=PointPlacementProof.fixture(db,"Старий v9")
        driver.execute(null,"INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt) VALUES('v9-point','${f.element.uuid}',1000,1000,${f.contourVersion},10,10)",0)
        val version=MeasurementElementRepository(db).find(f.element.uuid)!!.updatedAt
        driver.execute(null,"INSERT INTO point_position_operations VALUES('v9-move','v9-point',10,$version,${f.contourVersion},1500,1200,'exact',20)",0)
        driver.execute(null,"UPDATE point_placements SET xMm=1500,yMm=1200,precision='exact',lastMoveUuid='v9-move',updatedAt=20 WHERE uuid='v9-point'",0)
        driver.execute(null,"INSERT INTO point_placements(uuid,elementUuid,xMm,yMm,contourUpdatedAt,createdAt,updatedAt) VALUES('v9-deleted','${f.element.uuid}',2000,1000,${f.contourVersion},21,21)",0)
        driver.execute(null,"UPDATE point_placements SET deletedAt=22,updatedAt=22 WHERE uuid='v9-deleted'",0)
        return f
    }
    suspend fun lifecycle(production:SqlSchema<QueryResult.Value<Unit>>,populated:Boolean,open:suspend(SqlSchema<QueryResult.Value<Unit>>)->SqlDriver) {
        val old=open(v9Schema)
        val before:List<List<String?>>;val points:List<List<String?>>;val ops:List<List<String?>>;val schema:List<List<String?>>
        try {
            com.andreyceilings.shared.prices.StarterPriceCatalog.initialize(PrototypeDatabase(old))
            if(populated) { MeasurementElementMigrationProof.populate(old);populateV9(old) }
            before=PointPlacementMigrationProof.snapshot(old);points=oldPoints(old);ops=receipts(old);schema=PriceNameMigrationProof.schema(old)
        } finally {old.close()}
        val bad=object:SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion)=production.migrate(failing(driver),oldVersion,newVersion,*callbacks)
        }
        assertFails {open(bad).close()}
        open(v9Schema).use { rolled ->
            assertEquals(9L,PriceNameUniquenessProof.scalar(rolled,"PRAGMA user_version"))
            assertEquals(before,PointPlacementMigrationProof.snapshot(rolled));assertEquals(points,oldPoints(rolled));assertEquals(ops,receipts(rolled));assertEquals(schema,PriceNameMigrationProof.schema(rolled))
        }
        var saved:PointPlacement?=null
        open(production).use { upgraded ->
            assertEquals(PriceCatalogSchema.version,PriceNameUniquenessProof.scalar(upgraded,"PRAGMA user_version"))
            assertEquals(before,PointPlacementMigrationProof.snapshot(upgraded));assertEquals(points,oldPoints(upgraded));assertEquals(ops,receipts(upgraded))
            if(populated) {
                val db=PrototypeDatabase(upgraded);val point=PointPlacementRepository(db).find("v9-point")!!
                assertEquals(PointMounting(),point.mounting);assertEquals(PointPrecision.EXACT,point.precision)
                val row=db.businessSchemaQueries.selectMeasurementByUuid(MeasurementElementRepository(db).find(point.elementUuid)!!.measurementUuid).executeAsOne()
                val store=MeasurementEditStore(db){30};val document=store.load(row.projectId,row.id)
                assertEquals(4,document.placements.walls.size)
                val anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,document.placements.walls[0].uuid,document.placements.walls[3].uuid,600,1500)
                saved=store.update(document,document.draft,mapOf(point.uuid to PointMountingEdit(anchor=anchor))).placements.points.single()
            }
        }
        open(production).use { reopened ->
            if(populated)assertEquals(saved,PointPlacementRepository(PrototypeDatabase(reopened)).find("v9-point"))
            assertTrue(MeasurementElementMigrationProof.rows(reopened,"PRAGMA foreign_key_check",4).isEmpty())
            openDesktopPriceCatalog(reopened,true)
        }
    }
}
