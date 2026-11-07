package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.serialization.json.*
import kotlin.test.*

object AreaFeatureMigrationProof {
    val v12Schema=object:SqlSchema<QueryResult.Value<Unit>> {
        override val version=12L
        override fun create(driver:SqlDriver):QueryResult.Value<Unit> {
            LinePlacementMigrationProof.v11Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver,11,12)
            PriceCatalogSchema.verifyStructure(driver,12)
            return QueryResult.Unit
        }
        override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> = error("Frozen v12")
    }
    /** Real v12 journal writer, without querying any v13 table or receipt column. */
    fun populate(driver:SqlDriver) {
        val db=historicalElementDatabase(driver)
        StarterPriceCatalog.initialize(db)
        val f=PointPlacementProof.fixture(db,"Deployed v12")
        val point=PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,3500,2500,10)
        val old=HistoricalPointEditStore(db){20}.load(f.project,f.id)
        val prices=PriceRepository(db)
        val item=prices.find(prices.createInGroup("fixture.v12.line","V12 line",PriceGroupRepository(db).all().first().uuid,1.0,11,"м.п.",QuantityMode.MANUAL,PlacementKind.LINE))!!
        val e=MeasurementElementRepository(db).createCatalogSelection(old.draft.recordUuid,item.uuid,ElementQuantity.Range(1000,4000),12, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val g=validateMeasurementCreate(old.draft).geometry!!
        val drawing=old.sourceDrawingJson!!
        fun mount(x:Long,now:Long)=encodeMounting(PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,old.placements.walls[0].uuid,old.placements.walls[3].uuid,600,x),
            modifiedVersion=now,confirmedAt=now,anchorGeometryJson=drawing,lastValidGeometryJson=drawing))
        fun line(id:String,now:Long,deleted:Int=0,expected:Long?=null)=buildJsonObject {
            put("uuid",id);put("elementUuid",e.uuid);put("expectedUpdatedAt",expected?.let(::JsonPrimitive) ?: JsonNull)
            put("expectedElementUpdatedAt",MeasurementElementRepository(db).find(e.uuid)!!.updatedAt)
            put("startX",500);put("startY",600);put("endX",1500);put("endY",600);put("lengthMm",1000);put("widthMm",JsonNull);put("note","deployed")
            put("startMounting",mount(500,now));put("endMounting",mount(1500,now));put("needsReview",0);put("unresolved",0)
            put("deleted",deleted);put("write",1);put("confirm",1);put("remeasuredStart",1);put("remeasuredEnd",1)
        }
        fun write(now:Long,lines:List<JsonObject>) {
            val m=MeasurementRepository(db).find(f.id)!!
            val payload=buildJsonObject {
                put("name",m.name);put("notes",m.notes);put("shape",g.shapeType);put("drawingJson",drawing);put("oldDrawingJson",drawing)
                put("length",m.lengthMeters);put("width",m.widthMeters);put("area",g.areaSquareMeters);put("perimeter",g.perimeterMeters)
                putJsonArray("walls") {old.placements.walls.forEach {add(buildJsonObject {put("uuid",it.uuid)})}}
                putJsonArray("points") {add(buildJsonObject {
                    put("uuid",point.uuid);put("elementUuid",point.elementUuid);put("expectedUpdatedAt",point.updatedAt)
                    put("expectedElementUpdatedAt",MeasurementElementRepository(db).find(point.elementUuid)!!.updatedAt)
                    put("xMm",point.xMm);put("yMm",point.yMm);put("precision",point.precision.value);put("mountingJson",encodeMounting(point.mounting));put("write",0)
                })}
                put("lines",JsonArray(lines));putJsonObject("declaredLengths") {}
            }.toString()
            db.ceilingEditsQueries.insertOperation("v12-${f.id}-$now",m.uuid,m.updatedAt,payload,now)
        }
        write(20,listOf(line("v12-active-${f.id}",20),line("v12-tombstone-${f.id}",20)))
        write(30,listOf(line("v12-active-${f.id}",30,expected=20),line("v12-tombstone-${f.id}",30,1,20)))
    }
    fun fingerprint(driver:SqlDriver)=PriceNameMigrationProof.schema(driver).map {row -> row.mapIndexed {i,value ->
        if(i==3)value?.trim()?.trimEnd(';')?.replace(Regex("\\s+")," ")?.replace(Regex("\\s*([,()])\\s*"),"$1")?.lowercase() else value
    } }
    fun snapshot(driver:SqlDriver):List<List<String?>> {
        val tables=driver.executeQuery(null,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'area_%' AND name NOT LIKE 'sqlite_autoindex%' ORDER BY name",{c ->
            val result=mutableListOf<String>();while(c.next().value)result+=c.getString(0)!!;QueryResult.Value(result)
        },0).value
        return tables.flatMap {table ->
            val cols=driver.executeQuery(null,"PRAGMA table_info($table)",{c ->val names=mutableListOf<String>();while(c.next().value)c.getString(1)?.takeUnless {it=="areaVersions" || it=="businessRole"}?.let(names::add);QueryResult.Value(names)},0).value
            MeasurementElementMigrationProof.rows(driver,"SELECT "+cols.joinToString {"quote($it)"}+" FROM $table ORDER BY 1",cols.size).map {listOf(table)+it}
        }
    }
    suspend fun lifecycle(production:SqlSchema<QueryResult.Value<Unit>>,open:suspend(SqlSchema<QueryResult.Value<Unit>>)->SqlDriver) {
        val (before,schema)=open(v12Schema).use {driver ->populate(driver);snapshot(driver) to PriceNameMigrationProof.schema(driver)}
        val failed=object:SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> {
                val injected=object:SqlDriver by driver {
                    override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
                        if(sql.trimStart().startsWith("CREATE VIEW ceiling_operation_areas"))error("Injected v13 migration failure")
                        return driver.execute(identifier,sql,parameters,binders)
                    }
                }
                return production.migrate(injected,oldVersion,newVersion,*callbacks)
            }
        }
        assertFails {open(failed).close()}
        open(v12Schema).use {driver ->assertEquals(before,snapshot(driver));assertEquals(schema,PriceNameMigrationProof.schema(driver));PriceCatalogSchema.verifyStructure(driver,12)}
        open(production).use {driver ->
            PriceCatalogSchema.verifyStructure(driver);assertEquals(before,snapshot(driver))
            assertEquals(0,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM area_features"))
            AreaFeatureProof.run(driver)
        }
        open(production).use {PriceCatalogSchema.verifyStructure(it)}
    }
}
