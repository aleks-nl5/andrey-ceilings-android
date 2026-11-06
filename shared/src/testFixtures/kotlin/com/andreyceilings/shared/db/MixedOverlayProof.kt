package com.andreyceilings.shared.db

import com.andreyceilings.shared.measurement.*
import kotlin.test.*

/** One measured POINT overlaps both the polygon fill and LINE's touch halo. */
object MixedOverlayProof {
    data class Fixture(val overlay:OverlayDragFixture.Fixture,val original:MeasurementEditDocument,val point:String)
    fun create(db:PrototypeDatabase,unresolved:Boolean=false):Fixture {
        val f=OverlayDragFixture.create(db);val store=MeasurementEditStore(db){150}
        val before=store.load(f.project,f.id);val point=before.placements.points.single()
        var original=store.update(before,before.draft,edits=mapOf(point.uuid to PointMountingEdit(
            anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,before.placements.walls[0].uuid,before.placements.walls[3].uuid,1100,1000))))
        assertEquals(PointPrecision.EXACT,original.placements.points.single().precision)
        if(unresolved) {
            original=store.update(original,original.draft,edits=mapOf(point.uuid to PointMountingEdit(
                anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,original.placements.walls[0].uuid,original.placements.walls[3].uuid,2500,3500))),now=151)
            original=store.update(original,original.draft.copy(outline=MeasurementOutlineDraft.Template("rectangle",mapOf("length" to "3.2","width" to "3"))),now=160)
            original=store.update(original,original.draft.copy(outline=MeasurementOutlineDraft.Template("rectangle",mapOf("length" to "4","width" to "3"))),now=170)
            assertTrue(original.placements.points.single().mounting.unresolved)
            assertTrue(original.placements.areas.none {it.unresolved})
        }
        return Fixture(f,original,point.uuid)
    }
    interface Canvas {
        fun tap(x:Long,y:Long)
        fun drag(x:Long,y:Long,toX:Long,toY:Long)
        fun click(label:String)
        fun points():List<PointPlacement>
        fun lines():List<LinePlacement>
        fun areas():List<AreaFeature>
        fun selectedPoint():String?
        fun selectedLine():String?
        fun selectedArea():String?
        fun databaseUnchanged()
    }
    fun exerciseUnresolved(f:Fixture,c:Canvas) {
        assertTrue(c.points().single().mounting.unresolved)
        c.tap(1500,950);c.drag(1500,950,1800,750)
        assertEquals(PointPositionMm(800,750),c.lines().single().start)
        c.tap(3500,2500);assertEquals(f.point,c.selectedPoint())
        c.drag(3500,2500,3200,2200)
        val point=c.points().single();assertEquals(PointPositionMm(3200,2200),PointPositionMm(point.xMm,point.yMm))
        assertTrue(point.mounting.unresolved,"Free movement cannot resolve a lost measured placement")
        c.tap(1000,1800);c.drag(1000,1800,1300,2000)
        assertEquals(PointPositionMm(800,1200),c.areas().first {it.uuid==f.overlay.polygon}.shape.positions.first())
        c.databaseUnchanged()
    }
    fun exercise(f:Fixture,c:Canvas,order:String) {
        fun selectedPoint(x:Long,y:Long) {
            c.tap(x,y);assertEquals(f.point,c.selectedPoint(),"POINT tap must win over selected LINE/AREA fill/24dp halo")
            assertNull(c.selectedLine());assertNull(c.selectedArea());c.databaseUnchanged()
        }
        var p=PointPositionMm(1000,1100);var l=PointPositionMm(500,950);var a=PointPositionMm(500,1000)
        fun point(to:PointPositionMm) {
            selectedPoint(p.xMm,p.yMm);c.drag(p.xMm,p.yMm,to.xMm,to.yMm);p=to
            val actual=c.points().single {it.uuid==f.point}
            assertEquals(p,PointPositionMm(actual.xMm,actual.yMm));assertNull(actual.mounting.anchor)
            assertEquals(PointPrecision.APPROXIMATE,actual.precision);assertTrue(actual.mounting.needsReview)
            c.databaseUnchanged()
        }
        fun line(dx:Long,dy:Long) {
            val end=PointPositionMm(l.xMm+1000,l.yMm)
            c.tap(end.xMm,end.yMm);assertEquals(f.overlay.line,c.selectedLine())
            assertNull(c.selectedPoint());assertNull(c.selectedArea())
            c.drag(end.xMm,end.yMm,end.xMm+dx,end.yMm+dy);l=PointPositionMm(l.xMm+dx,l.yMm+dy)
            val actual=c.lines().single();assertEquals(l,actual.start);assertEquals(1000,actual.lengthMm)
            assertNull(actual.startMounting.anchor);assertNull(actual.endMounting.anchor);c.databaseUnchanged()
        }
        fun area(dx:Long,dy:Long) {
            c.tap(1000,1800);assertEquals(f.overlay.polygon,c.selectedArea());assertNull(c.selectedPoint());assertNull(c.selectedLine())
            c.drag(1000,1800,1000+dx,1800+dy);a=PointPositionMm(a.xMm+dx,a.yMm+dy)
            val actual=c.areas().first {it.uuid==f.overlay.polygon};assertEquals(a,actual.shape.positions.first())
            assertEquals(f.original.placements.areas.first {it.uuid==actual.uuid}.twiceAreaMm2,actual.twiceAreaMm2)
            assertTrue(actual.mountings.all {it.anchor==null});c.databaseUnchanged()
        }
        // Selection without movement preserves measured anchors and every DB row.
        c.tap(1500,950);assertEquals(f.overlay.line,c.selectedLine());selectedPoint(p.xMm,p.yMm)
        c.tap(1000,1800);assertEquals(f.overlay.polygon,c.selectedArea());selectedPoint(p.xMm,p.yMm)
        assertEquals(f.original.placements.points,c.points())
        when(order) {
            "point_line_point"->{point(PointPositionMm(1300,1400));line(300,-200);point(PointPositionMm(1600,1600));area(300,200)}
            "line_point_area"->{line(300,-200);point(PointPositionMm(1300,1400));area(300,200);selectedPoint(p.xMm,p.yMm);line(300,200)}
            "area_point_line"->{area(300,200);point(PointPositionMm(1300,1400));line(300,-200);selectedPoint(p.xMm,p.yMm);area(400,300)}
            else->error("Unknown order")
        }
        val after=Triple(c.points(),c.lines(),c.areas())
        c.click("Скасувати дію");assertNotEquals(after,Triple(c.points(),c.lines(),c.areas()))
        c.click("Повторити");assertEquals(after,Triple(c.points(),c.lines(),c.areas()));c.databaseUnchanged()
        selectedPoint(p.xMm,p.yMm)
        c.drag(p.xMm,p.yMm,0,p.yMm) // Boundary position is invalid; retain the valid mixed draft.
        assertEquals(after,Triple(c.points(),c.lines(),c.areas()));c.databaseUnchanged()
        assertEquals(f.original.placements.areas.first {it.uuid==f.overlay.circle},c.areas().first {it.uuid==f.overlay.circle})
    }
    fun verifySaved(f:Fixture,points:List<PointPlacement>,lines:List<LinePlacement>,areas:List<AreaFeature>,saved:MeasurementEditDocument) {
        assertEquals(points.map {Triple(it.uuid,it.xMm,it.yMm)},saved.placements.points.map {Triple(it.uuid,it.xMm,it.yMm)})
        assertEquals(lines.map {Triple(it.uuid,it.start,it.end)},saved.placements.lines.map {Triple(it.uuid,it.start,it.end)})
        assertEquals(areas.map {it.uuid to it.shape},saved.placements.areas.map {it.uuid to it.shape})
        assertEquals(f.original.draft,saved.draft)
        assertEquals(f.original.placements.quantities,saved.placements.quantities)
        assertEquals(f.original.lineElements.map {it.uuid to it.quantity},saved.lineElements.map {it.uuid to it.quantity})
        assertEquals(f.original.areaElements.map {it.uuid to it.quantity},saved.areaElements.map {it.uuid to it.quantity})
        assertEquals(PointPrecision.APPROXIMATE,saved.placements.points.single().precision)
        assertNull(saved.placements.points.single().mounting.anchor)
        assertTrue(saved.placements.lines.all {it.startMounting.anchor==null && it.endMounting.anchor==null})
        assertTrue(saved.placements.areas.first {it.uuid==f.overlay.polygon}.mountings.all {it.anchor==null})
        assertEquals(f.original.placements.areas.first {it.uuid==f.overlay.circle},saved.placements.areas.first {it.uuid==f.overlay.circle})
    }
}
