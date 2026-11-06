package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

/** v13 APKs exist: retain their exact DDL and data, correct receipt INSERT/REPLACE in v14. */
object CeilingReceiptMigrationProof {
    val v13Schema=object:SqlSchema<QueryResult.Value<Unit>> {
        override val version=13L
        override fun create(driver:SqlDriver):QueryResult.Value<Unit> {
            AreaFeatureMigrationProof.v12Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver,12,13)
            PriceCatalogSchema.verifyStructure(driver,13)
            return QueryResult.Unit
        }
        override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> = error("Frozen distributed v13")
    }
    fun populate(driver:SqlDriver) {
        val db=PrototypeDatabase(driver);StarterPriceCatalog.initialize(db)
        val f=PointPlacementProof.fixture(db,"Distributed v13 AREA")
        val prices=PriceRepository(db)
        val price=prices.find(prices.createInGroup("v13.area.${newRecordUuid()}","V13 area ${newRecordUuid()}",PriceGroupRepository(db).all().first().uuid,1.0,300,"м²",QuantityMode.MANUAL,PlacementKind.AREA))!!
        val e=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,price.uuid,ElementQuantity.Unknown,301)
        val store=MeasurementEditStore(db){400};var doc=store.load(f.project,f.id)
        val rectangle=AreaFeature("v13-obstacle-${f.id}",doc.draft.recordUuid,AreaShape.rectangle(PointPositionMm(500,500),PointPositionMm(1500,1500)),doc.updatedAt,0,0,role=AreaRole.OBSTACLE,note="Preserve unpriced annotation")
        val circle=AreaFeature("v13-circle-${f.id}",doc.draft.recordUuid,AreaShape.Circle(PointPositionMm(2500,1500),200),doc.updatedAt,0,0,
            elementUuid=e.uuid,role=AreaRole.GENERAL_AREA,radiusMeasured=true,
            mountings=listOf(PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,doc.placements.walls[0].uuid,doc.placements.walls[3].uuid,1500,2500))))
        val tombstone=rectangle.copy(uuid="v13-tombstone-${f.id}")
        doc=store.update(doc,doc.draft,now=400,areaEdits=mapOf(rectangle.uuid to AreaFeatureEdit(rectangle),circle.uuid to AreaFeatureEdit(circle,confirm=true),tombstone.uuid to AreaFeatureEdit(tombstone)))
        doc=store.update(doc,doc.draft,now=410,areaEdits=mapOf(tombstone.uuid to AreaFeatureEdit(doc.placements.areas.first {it.uuid==tombstone.uuid}.copy(deletedAt=0))))
        doc=store.update(doc,doc.draft.reduce(MeasurementCreateIntent.Dimension("length","6")),now=420)
        store.update(doc,doc.draft,now=430,areaEdits=doc.placements.areas.associate {it.uuid to AreaFeatureEdit(it,defer=true)})
    }
    /** Every old table/column, including AREA geometry/provenance and receipt areaVersions. */
    fun snapshot(driver:SqlDriver):List<List<String?>> {
        val tables=MeasurementElementMigrationProof.rows(driver,"SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",1).map {it.single()!!}
        return tables.flatMap {t ->
            val columns=driver.executeQuery(null,"PRAGMA table_info($t)",{c->val names=mutableListOf<String>();while(c.next().value)names+=c.getString(1)!!;QueryResult.Value(names)},0).value
            MeasurementElementMigrationProof.rows(driver,"SELECT "+columns.joinToString {"quote($it)"}+" FROM $t ORDER BY 1",columns.size).map {listOf(t)+it}
        }
    }
    fun rejectReplace(driver:SqlDriver) {
        val before=snapshot(driver)
        driver.execute(null,"PRAGMA foreign_keys=OFF",0)
        driver.execute(null,"PRAGMA recursive_triggers=OFF",0)
        try {
            for(verb in listOf("INSERT OR REPLACE","REPLACE","INSERT OR IGNORE")) {
                assertFails("$verb must not overwrite or ignore immutable receipts") {driver.execute(null,
                    "$verb INTO ceiling_operation_results(operationUuid,measurementVersion,pointVersions,elementVersions,lineVersions,areaVersions) SELECT r.operationUuid,r.measurementVersion,r.pointVersions,r.elementVersions,r.lineVersions,'{\"forged\":1}' FROM ceiling_operation_results r JOIN ceiling_operations o ON o.uuid=r.operationUuid JOIN room_measurements m ON m.uuid=o.measurementUuid WHERE m.deletedAt IS NULL AND m.updatedAt=r.measurementVersion ORDER BY o.createdAt DESC LIMIT 1",0)}
                assertEquals(before,snapshot(driver))
            }
            assertFails {driver.execute(null,"INSERT INTO ceiling_operation_results VALUES('nonexistent-operation',1,'{}','{}','{}','{}')",0)}
            assertEquals(before,snapshot(driver))
        } finally {driver.execute(null,"PRAGMA foreign_keys=ON",0)}
        // A legitimate fresh complete-set operation is still writable and has its full receipt.
        val db=PrototypeDatabase(driver)
        val area=AreaFeatureRepository(db).all().first {it.deletedAt==null}
        val row=MeasurementRepository(db).all().first {it.uuid==area.measurementUuid}
        val store=MeasurementEditStore(db){500};val doc=store.load(row.projectId,row.id)
        val saved=store.update(doc,doc.draft.copy(notes="After v14 receipt guard"),operationUuid="v14-receipt-${row.id}",now=500)
        assertTrue(store.operationCommitted("v14-receipt-${row.id}",row.uuid,saved.updatedAt))
    }
    suspend fun lifecycle(production:SqlSchema<QueryResult.Value<Unit>>,open:suspend(SqlSchema<QueryResult.Value<Unit>>)->SqlDriver) {
        val (before,oldSchema)=open(v13Schema).use {d ->populate(d);snapshot(d) to PriceNameMigrationProof.schema(d)}
        val failed=object:SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver:SqlDriver,oldVersion:Long,newVersion:Long,vararg callbacks:AfterVersion):QueryResult.Value<Unit> {
                val injected=object:SqlDriver by driver {
                    override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
                        val result=driver.execute(identifier,sql,parameters,binders)
                        if(sql.trimStart().startsWith("CREATE TRIGGER ceiling_result_insert_guard"))error("Injected AFTER v14 receipt DDL")
                        return result
                    }
                }
                return production.migrate(injected,oldVersion,newVersion,*callbacks)
            }
        }
        assertFails {open(failed).close()}
        open(v13Schema).use {d ->assertEquals(before,snapshot(d));assertEquals(oldSchema,PriceNameMigrationProof.schema(d));PriceCatalogSchema.verifyStructure(d,13)}
        open(production).use {d ->
            assertEquals(before,snapshot(d));PriceCatalogSchema.verifyStructure(d)
            rejectReplace(d)
        }
        open(production).use {d ->PriceCatalogSchema.verifyStructure(d);assertTrue(AreaFeatureRepository(PrototypeDatabase(d)).all().isNotEmpty())}
    }
}
