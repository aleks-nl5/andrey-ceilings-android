package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import app.cash.sqldelight.db.*
import com.andreyceilings.shared.calculation.MeasurementPrecision

/** Mixed, measured production rows for real pointer tests on Android/Desktop. */
object OverlayDragFixture {
    data class Fixture(val project:Long,val id:Long,val line:String,val polygon:String,val circle:String,val element:String)
    fun create(db:PrototypeDatabase):Fixture {
        StarterPriceCatalog.initialize(db)
        val project=ProjectRepository(db).createReturningId(null,"Drag",createdAt=1)
        val draft=MeasurementCreateDraft(project,MeasurementPrecision.ONE_MILLIMETER,"Drag","",MeasurementOutlineDraft.Template("rectangle",mapOf("length" to "4","width" to "3")))
        val id=MeasurementCreateStore(db){2}.create(draft)
        val row=MeasurementRepository(db).find(id)!!
        val prices=PriceRepository(db)
        fun item(code:String,unit:String,kind:PlacementKind)=prices.find(prices.createInGroup(code,code,
            PriceGroupRepository(db).all().first().uuid,1.0,20,unit,QuantityMode.MANUAL,kind))!!
        val elements=MeasurementElementRepository(db)
        val pointElement=elements.createCatalogSelection(row.uuid,item("drag.point","шт.",PlacementKind.POINT).uuid,ElementQuantity.Exact(6000),5, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        PointPlacementRepository(db).addPoint(pointElement.version,row.updatedAt,3500,2500,10)
        val lineElement=elements.createCatalogSelection(row.uuid,item("drag.line","м.п.",PlacementKind.LINE).uuid,ElementQuantity.Exact(2000),21, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val areaElement=elements.createCatalogSelection(row.uuid,item("drag.area","шт.",PlacementKind.AREA).uuid,ElementQuantity.Exact(3000),22, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val store=MeasurementEditStore(db){100};val doc=store.load(project,id)
        fun mounting(p:PointPositionMm)=PointMounting(anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,doc.placements.walls[0].uuid,doc.placements.walls[3].uuid,p.yMm,p.xMm))
        val start=PointPositionMm(500,950);val end=PointPositionMm(1500,950)
        val line=LinePlacement("drag-line",lineElement.uuid,start,end,doc.updatedAt,0,0,startMounting=mounting(start),endMounting=mounting(end))
        val shape=AreaShape.rectangle(PointPositionMm(500,1000),PointPositionMm(1500,2000))
        val polygon=AreaFeature("drag-polygon",doc.draft.recordUuid,shape,doc.updatedAt,0,0,elementUuid=areaElement.uuid,role=AreaRole.OBSTACLE,mountings=shape.positions.map(::mounting))
        val circleShape=AreaShape.Circle(PointPositionMm(2800,1500),200)
        val circle=AreaFeature("drag-circle",doc.draft.recordUuid,circleShape,doc.updatedAt,0,0,role=AreaRole.VOID_MARKER,radiusMeasured=true,mountings=circleShape.positions.map(::mounting))
        store.update(doc,doc.draft,now=100,lineEdits=mapOf(line.uuid to LinePlacementEdit(line,confirm=true)),
            areaEdits=mapOf(polygon.uuid to AreaFeatureEdit(polygon,confirm=true),circle.uuid to AreaFeatureEdit(circle,confirm=true)))
        return Fixture(project,id,line.uuid,polygon.uuid,circle.uuid,areaElement.uuid)
    }
    fun snapshot(driver:SqlDriver):List<List<String?>> {
        fun rows(sql:String,n:Int)=driver.executeQuery(null,sql,{c ->
            val result=mutableListOf<List<String?>>()
            while(c.next().value)result+=List(n){c.getString(it)}
            QueryResult.Value(result)
        },0).value
        return rows("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",1).flatMap {row ->
            val t=row.single()!!
            val cols=driver.executeQuery(null,"PRAGMA table_info($t)",{c ->val names=mutableListOf<String>();while(c.next().value)names+=c.getString(1)!!;QueryResult.Value(names)},0).value
            rows("SELECT "+cols.joinToString {"quote($it)"}+" FROM $t ORDER BY 1",cols.size).map {listOf(t)+it}
        }
    }
}
