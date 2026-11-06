package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AreaReviewRegressionTest {
    @Test fun invalidManualAnchorAndCommandKeepValidDataButChangedContourMayBecomeUnresolved()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d);val db=PrototypeDatabase(d);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val doc=store.load(f.project,f.id)
            val before=CeilingReceiptMigrationProof.snapshot(d)
            val model=MeasurementEditModel(store,doc)
            model.selectArea(f.polygon);model.openAreaMounting(0)
            model.updateMounting {it.copy(firstText="1.5",secondText="1.8")};model.applyMounting()
            assertNotNull(model.state.value.error);assertTrue(model.state.value.area.edits.isEmpty())
            assertNotNull(model.state.value.mountingInput);assertEquals(doc.placements.areas,model.displayAreas())
            val a=doc.placements.areas.first {it.uuid==f.polygon}
            val points=a.shape.positions.toMutableList().also {it[0]=PointPositionMm(1800,1500)}
            val mounts=a.mountings.toMutableList().also {it[0]=PointMounting()}
            assertFails {store.update(doc,doc.draft,now=200,areaEdits=mapOf(a.uuid to AreaFeatureEdit(a.copy(shape=a.shape.withPositions(points),mountings=mounts))))}
            assertEquals(before,CeilingReceiptMigrationProof.snapshot(d))
            model.discardMounting();model.accept(MeasurementCreateIntent.Dimension("length","1.2"));model.save(keepEditing=true)
            assertNull(model.state.value.error)
            val unresolved=store.load(f.project,f.id).placements.areas.first {it.uuid==a.uuid}
            assertTrue(unresolved.unresolved);assertTrue(unresolved.needsReview);assertEquals(a.shape,unresolved.shape)
            val next=MeasurementEditModel(store,store.load(f.project,f.id));next.accept(MeasurementCreateIntent.Dimension("length","4"));next.save(keepEditing=true)
            assertTrue(store.load(f.project,f.id).placements.areas.first {it.uuid==a.uuid}.unresolved)
        }
    }
    @Test fun measuredVertexInsideConcaveArmCannotPullAnEdgeAcrossCutout() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d);val db=PrototypeDatabase(d)
            val project=ProjectRepository(db).createReturningId(null,"Concave",createdAt=1)
            val coords=listOf(0L to 0L,6000L to 0L,6000L to 5000L,4000L to 5000L,4000L to 2000L,2000L to 2000L,2000L to 5000L,0L to 5000L)
            val g=com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract.capture("polygon",coords.map {com.andreyceilings.shared.calculation.CeilingPointMm(it.first,it.second)},emptyMap(),0.001)
            val draft=MeasurementCreateDraft(project,com.andreyceilings.shared.calculation.MeasurementPrecision.ONE_MILLIMETER,"Concave","",MeasurementOutlineDraft.Captured(g))
            val id=MeasurementCreateStore(db){2}.create(draft);val store=MeasurementEditStore(db){100};var doc=store.load(project,id)
            val a=AreaFeature("concave-area",doc.draft.recordUuid,AreaShape.rectangle(PointPositionMm(500,500),PointPositionMm(1500,1500)),doc.updatedAt,0,0)
            doc=store.update(doc,doc.draft,now=100,areaEdits=mapOf(a.uuid to AreaFeatureEdit(a)))
            val model=MeasurementEditModel(store,doc);model.selectArea(a.uuid);model.openAreaMounting(2)
            model.updateMounting {it.copy(firstWall=doc.placements.walls[0].uuid,secondWall=doc.placements.walls[1].uuid,firstText="3.5",secondText="0.5")}
            assertNotNull(solveWallAnchor(g,doc.placements.walls,model.state.value.mountingInput!!.anchor()!!))
            val before=CeilingReceiptMigrationProof.snapshot(d);model.applyMounting()
            assertNotNull(model.state.value.error);assertTrue(model.state.value.area.edits.isEmpty());assertEquals(doc.placements.areas,model.displayAreas())
            assertEquals(before,CeilingReceiptMigrationProof.snapshot(d))
        }
    }
    @Test fun detailsBackCancelsGestureAndKeepsDirtyDraftUntilExplicitDiscard() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d);val db=PrototypeDatabase(d);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val m=MeasurementEditModel(store,store.load(f.project,f.id))
            val guard=MeasurementCreateNavigationGuard();guard.bindEdit(m)
            val nav=com.andreyceilings.shared.navigation.AppNavigationState().selectTab(com.andreyceilings.shared.navigation.AppRoute.MEASUREMENTS).openMeasurement(f.project,f.id)
            m.selectArea(f.polygon);m.beginAreaDrag(f.polygon);m.previewAreaDrag(300.0,200.0);m.finishAreaDrag()
            val draft=m.displayAreas();m.beginAreaDrag(f.polygon);m.previewAreaDrag(100.0,100.0)
            assertNotNull(m.state.value.area.drag)
            var exited=false
            assertEquals(nav,guard.transition(nav,nav.back()){exited=true})
            assertNull(m.state.value.area.drag);assertTrue(m.state.value.exitRequested);assertFalse(exited)
            assertEquals(draft,m.displayAreas());m.discardAndExit();assertTrue(exited)
            assertEquals(store.load(f.project,f.id).placements.areas,m.displayAreas())
        }
    }
    @Test fun changingRadiusWithoutMeasurementIsApproximateAndUnchangedMeasuredRadiusStaysExact()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d);val db=PrototypeDatabase(d);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val m=MeasurementEditModel(store,store.load(f.project,f.id))
            m.selectArea(f.circle);assertEquals(PointPrecision.EXACT,m.displayAreas().first {it.uuid==f.circle}.precision)
            m.openAreaProperties();m.changeAreaProperties {it.copy(note="unchanged radius")};m.applyAreaProperties()
            assertEquals(PointPrecision.EXACT,m.displayAreas().first {it.uuid==f.circle}.precision)
            m.openAreaProperties();m.changeAreaProperties {it.copy(diameter="0.6",diameterMeasured=false)};m.applyAreaProperties()
            val changed=m.displayAreas().first {it.uuid==f.circle};assertFalse(changed.radiusMeasured);assertEquals(PointPrecision.APPROXIMATE,changed.precision)
            m.save(keepEditing=true);assertNull(m.state.value.error)
            assertFalse(store.load(f.project,f.id).placements.areas.first {it.uuid==f.circle}.radiusMeasured)
            m.selectArea(f.circle);m.openAreaProperties();m.changeAreaProperties {it.copy(diameterMeasured=true)};m.applyAreaProperties();m.save(keepEditing=true)
            assertNull(m.state.value.error);assertEquals(PointPrecision.EXACT,store.load(f.project,f.id).placements.areas.first {it.uuid==f.circle}.precision)
        }
    }
    @Test fun freeTransformsKeepUntouchedAnchorsCapacityAndRestorableHistory()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use {d ->
            PriceCatalogSchema.create(d);val db=PrototypeDatabase(d);val f=OverlayDragFixture.create(db)
            val store=MeasurementEditStore(db){200};val m=MeasurementEditModel(store,store.load(f.project,f.id))
            val line=m.displayLines().single();m.selectLine(f.line);m.setLineMoveTarget(1);m.beginLineDrag(f.line);m.previewLineDrag(200.0,0.0);m.finishLineDrag()
            val changed=m.displayLines().single();assertEquals(800,changed.lengthMm);assertNull(changed.startMounting.anchor);assertEquals(line.endMounting.anchor,changed.endMounting.anchor)
            m.undo();assertEquals(line,m.displayLines().single());m.redo()
            val restored=MeasurementEditModel.restore(store,m.snapshot(),f.project,f.id)!!;restored.reconcile();assertEquals(m.displayLines(),restored.displayLines())
            restored.save(keepEditing=true);assertNull(restored.state.value.error)
            restored.selectLine(f.line);restored.setLineMoveTarget(2);restored.beginLineDrag(f.line);restored.previewLineDrag(1600.0,0.0);restored.finishLineDrag()
            assertNotNull(restored.lineCapacityError());assertFalse(restored.canSaveMountingDraft())
            val before=CeilingReceiptMigrationProof.snapshot(d);restored.save(keepEditing=true);assertEquals(before,CeilingReceiptMigrationProof.snapshot(d))
        }
    }
}
