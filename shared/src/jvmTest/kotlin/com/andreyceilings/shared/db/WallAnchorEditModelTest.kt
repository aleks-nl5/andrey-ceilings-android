package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.measurement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WallAnchorEditModelTest {
    @Test fun stagedDistancesGeometryHistoryRestoreAndDurableLaterReview()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver);val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
            val points=PointPlacementRepository(db);val elements=MeasurementElementRepository(db)
            val first=points.addPoint(f.element.version,f.contourVersion,1000,1000,10)
            val second=points.addPoint(elements.find(f.element.uuid)!!.version,f.contourVersion,3000,1000,11)
            val store=MeasurementEditStore(db){100};val document=store.load(f.project,f.id)
            val model=MeasurementEditModel(store,document)
            model.selectPoint(first.uuid);model.openMounting()
            assertEquals(WallAnchor.Kind.TWO_WALLS,model.state.value.mountingInput!!.kind)
            assertEquals("",model.state.value.mountingInput!!.firstText,"Legacy coordinates never invent measured distances")
            model.updateMounting {it.copy(firstText="0,",secondText="1.5")}
            val restored=MeasurementEditModel.restore(store,model.snapshot(),f.project,f.id)!!
            restored.reconcile();assertEquals("0,",restored.state.value.mountingInput!!.firstText)
            assertFalse(restored.state.value.conflict)
            restored.updateMounting {it.copy(firstText="0.6")};restored.applyMounting()
            val staged=restored.displayPoints().first {it.uuid==first.uuid}
            assertEquals(PointPositionMm(1500,600),PointPositionMm(staged.xMm,staged.yMm));assertEquals(PointPrecision.EXACT,staged.precision)
            assertEquals(first,points.find(first.uuid))
            restored.accept(MeasurementCreateIntent.Dimension("length","6"));restored.undo()
            assertEquals(document.draft,restored.state.value.draft);assertNotNull(restored.displayPoints().first {it.uuid==first.uuid}.mounting.anchor)
            restored.undo();assertEquals(document.placements.points,restored.displayPoints())
            restored.redo();restored.redo()
            val rotating=MeasurementEditModel.restore(store,restored.snapshot(),f.project,f.id)!!;rotating.reconcile()
            assertEquals(restored.displayPoints(),rotating.displayPoints());assertEquals(restored.draftWalls(),rotating.draftWalls())
            rotating.save();assertTrue(rotating.state.value.reviewPrompt,rotating.state.value.error)
            assertTrue(points.forMeasurement(f.element.measurementUuid).all {it.mounting.needsReview})
            rotating.reviewLater();assertTrue(rotating.state.value.saved,rotating.state.value.error)
            val persisted=store.load(f.project,f.id)
            assertTrue(persisted.placements.points.all {it.mounting.deferredAt==100L})
            val review=MeasurementEditModel(store,persisted);review.reviewNow();review.confirmSelected();review.confirmSelected()
            assertTrue(points.forMeasurement(f.element.measurementUuid).none {it.mounting.needsReview})
            assertEquals(setOf(first.uuid,second.uuid),points.forMeasurement(f.element.measurementUuid).map{it.uuid}.toSet())
            assertNull(points.find(second.uuid)!!.mounting.anchor)
        }
    }
    @Test fun topologyLossReservesCapacityKeepsContextAndCannotBeConfirmedUntilMeasured()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver);val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
            val points=PointPlacementRepository(db);val elements=MeasurementElementRepository(db)
            val first=points.addPoint(f.element.version,f.contourVersion,1000,1000,10)
            val store=MeasurementEditStore(db){100};var document=store.load(f.project,f.id)
            val anchor=WallAnchor(WallAnchor.Kind.TWO_WALLS,document.placements.walls[0].uuid,document.placements.walls[3].uuid,600,1500)
            document=store.update(document,document.draft,mapOf(first.uuid to PointMountingEdit(anchor=anchor)))
            val before=points.find(first.uuid)!!;val model=MeasurementEditModel(store,document)
            model.accept(MeasurementCreateIntent.Shape("l_shaped"))
            for((k,v) in mapOf("A" to "5","B" to "4","C" to "2","D" to "2"))model.accept(MeasurementCreateIntent.Dimension(k,v))
            val preview=model.displayPoints().single()
            assertTrue(preview.mounting.unresolved);assertEquals(anchor,preview.mounting.anchor)
            assertEquals(before.xMm,preview.xMm);assertEquals(before.yMm,preview.yMm)
            model.save();assertTrue(model.state.value.reviewPrompt,model.state.value.error);model.reviewLater()
            val unresolved=points.find(first.uuid)!!
            assertTrue(unresolved.mounting.unresolved);assertEquals(before.mounting.lastValidGeometryJson,unresolved.mounting.lastValidGeometryJson)
            assertEquals(0L,elements.find(f.element.uuid)!!.placedQuantityMilli)
            repeat(5) {i->points.addPoint(elements.find(f.element.uuid)!!.version,model.state.value.original.updatedAt,500+i*100L,500,200+i.toLong())}
            assertFailsWith<PointPlacementFailure.CapacityReached> {points.addPoint(elements.find(f.element.uuid)!!.version,model.state.value.original.updatedAt,1800,500,300)}
            val review=MeasurementEditModel(store,store.load(f.project,f.id));review.reviewNow()
            review.selectPoint(first.uuid);review.confirmSelected();assertEquals(unresolved,points.find(first.uuid))
            review.beginPointDrag(first.uuid);review.previewPointDrag(1600.0,700.0);review.finishPointDrag()
            assertTrue(review.displayPoints().single{it.uuid==first.uuid}.mounting.unresolved)
            assertFalse(review.canSaveMountingDraft());review.save();assertEquals(unresolved,points.find(first.uuid))
            review.confirmSelected();assertEquals(unresolved,points.find(first.uuid))
            review.undo();assertTrue(review.canSaveMountingDraft())
            review.openMounting();val walls=review.draftWalls()
            review.updateMounting {it.copy(firstWall=walls[0].uuid,secondWall=walls[5].uuid,firstText="0.6",secondText="1.5")}
            review.applyMounting();review.save()
            assertFalse(points.find(first.uuid)!!.mounting.unresolved)
            assertEquals(6000L,elements.find(f.element.uuid)!!.placedQuantityMilli)
            assertEquals(6,points.forMeasurement(f.element.measurementUuid).size)
        }
    }
    @Test fun failedApplyingTriggerRollsBackContourWallsPointsAndReceiptThenExplicitRetryCommitsOnce()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver);val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
            val point=PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,1000,1000,10)
            val store=MeasurementEditStore(db){100};val document=store.load(f.project,f.id);val model=MeasurementEditModel(store,document)
            val before=MeasurementRepository(db).find(f.id)
            driver.execute(null,"CREATE TRIGGER test_wall_failure BEFORE UPDATE ON point_placements WHEN new.uuid='${point.uuid}' BEGIN SELECT RAISE(ABORT,'injected'); END",0)
            model.accept(MeasurementCreateIntent.Dimension("length","6"));model.save()
            assertTrue(model.state.value.uncertain);assertTrue(model.state.value.dirty)
            assertEquals(before,MeasurementRepository(db).find(f.id));assertEquals(point,PointPlacementRepository(db).find(point.uuid))
            assertEquals(document.placements.walls,store.load(f.project,f.id).placements.walls)
            assertEquals(0L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations"))
            driver.execute(null,"DROP TRIGGER test_wall_failure",0)
            model.save();assertTrue(model.state.value.reviewPrompt,model.state.value.error)
            assertEquals(1L,PriceNameUniquenessProof.scalar(driver,"SELECT count(*) FROM ceiling_operations"))
        }
    }
    @Test fun pointSummaryFlowInvalidatesAfterAtomicRemap()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver);val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
            val points=PointPlacementRepository(db);val elements=MeasurementElementRepository(db)
            points.addPoint(f.element.version,f.contourVersion,1000,1000,10)
            points.addPoint(elements.find(f.element.uuid)!!.version,f.contourVersion,3500,2000,11)
            val values=Channel<Long>(Channel.UNLIMITED)
            val reader=launch(Dispatchers.Default) { db.measurementElementsQueries.selectByUuid(f.element.uuid).asFlow().mapToOne(Dispatchers.Default).collect {values.send(it.placedQuantityMilli)} }
            suspend fun await(n:Long)=withTimeout(10_000){while(values.receive()!=n) {}}
            try {
                await(2000)
                val store=MeasurementEditStore(db){100};val document=store.load(f.project,f.id)
                store.update(document,document.draft.reduce(MeasurementCreateIntent.Dimension("length","2")))
                await(1000)
            } finally {reader.cancelAndJoin();values.close()}
        }
    }
    @Test fun committedButUnacknowledgedWholeEditRetriesIdenticalReceiptWithoutReplay()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw);val base=PrototypeDatabase(raw);val f=PointPlacementProof.fixture(base)
            val point=PointPlacementRepository(base).addPoint(f.element.version,f.contourVersion,1000,1000,10)
            val fail=java.util.concurrent.atomic.AtomicBoolean(false)
            val driver=object:SqlDriver by raw {
                override fun notifyListeners(vararg queryKeys:String) {
                    raw.notifyListeners(*queryKeys)
                    if("ceiling_operations" in queryKeys && fail.compareAndSet(true,false)) error("Injected response loss after commit")
                }
            }
            val store=MeasurementEditStore(PrototypeDatabase(driver)){100};val doc=store.load(f.project,f.id)
            val model=MeasurementEditModel(store,doc)
            model.selectPoint(point.uuid);model.openMounting();model.updateMounting{it.copy(firstText="0.6",secondText="1.5")};model.applyMounting()
            fail.set(true);model.save();assertTrue(model.state.value.uncertain)
            val committed=PointPlacementRepository(base).find(point.uuid)!!
            assertNotNull(committed.mounting.anchor,"The SQLite transaction committed before response failure")
            assertEquals(1L,PriceNameUniquenessProof.scalar(raw,"SELECT count(*) FROM ceiling_operations"))
            model.save();assertTrue(model.state.value.saved,model.state.value.error)
            assertEquals(committed,PointPlacementRepository(base).find(point.uuid))
            assertEquals(1L,PriceNameUniquenessProof.scalar(raw,"SELECT count(*) FROM ceiling_operations"))
        }
    }
    @Test fun acceptedWholeEditDrainsBeforeSessionCloseAndLateSaveNeverWrites()=runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { raw ->
            PriceCatalogSchema.create(raw);val db=PrototypeDatabase(raw);val f=PointPlacementProof.fixture(db)
            PointPlacementRepository(db).addPoint(f.element.version,f.contourVersion,1000,1000,10)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val driver=object:SqlDriver by raw {
                override fun execute(identifier:Int?,sql:String,parameters:Int,binders:(SqlPreparedStatement.()->Unit)?):QueryResult<Long> {
                    if(sql.startsWith("INSERT INTO ceiling_operations")){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                    return raw.execute(identifier,sql,parameters,binders)
                }
            }
            val store=MeasurementEditStore(PrototypeDatabase(driver)){100};val model=MeasurementEditModel(store,store.load(f.project,f.id))
            model.accept(MeasurementCreateIntent.Dimension("length","6"))
            val saving=async(Dispatchers.Default){model.save()}
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));val closing=async(Dispatchers.Default){store.closeAndAwaitPointReads()}
                delay(100);assertFalse(closing.isCompleted)
                release.countDown();withTimeout(10_000){saving.await();closing.await()}
                assertTrue(model.state.value.reviewPrompt,model.state.value.error)
                val version=MeasurementRepository(db).find(f.id)!!.updatedAt
                model.accept(MeasurementCreateIntent.Dimension("length","7"));model.save()
                assertEquals(version,MeasurementRepository(db).find(f.id)!!.updatedAt)
            } finally {release.countDown();saving.cancelAndJoin()}
        }
    }
}
