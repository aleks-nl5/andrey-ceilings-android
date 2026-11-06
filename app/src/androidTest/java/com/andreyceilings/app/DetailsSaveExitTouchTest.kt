package com.andreyceilings.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual Details route, physical Canvas drag, system Back and physical dialog buttons. */
class DetailsSaveExitTouchTest {
    @get:Rule val compose=createComposeRule()
    @Test fun backSaveWaitsForCommitAndNavigatesOnce()=scenario("back")
    @Test fun editSaveKeepsTheRequestedRoute()=scenario("edit")
    @Test fun rollbackKeepsDialogAndDraftUntilRetry()=scenario("rollback")
    @Test fun uncertainRetryConfirmsReceiptBeforeNavigation()=scenario("uncertain")
    @Test fun conflictDoesNotNavigateAndExplicitDiscardStillWorks()=scenario("conflict")
    private fun scenario(mode:String) {
        val context=ApplicationProvider.getApplicationContext<Context>();System.loadLibrary("sqlcipher")
        val name="details-save-exit-$mode.db";context.deleteDatabase(name);val key=ByteArray(32){94}
        val raw=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        val rawDb=PrototypeDatabase(raw);val f=OverlayDragFixture.create(rawDb)
        val fault=DetailsExitFaultDriver(raw);val db=PrototypeDatabase(fault)
        val store=MeasurementEditStore(db){200};val details=MeasurementDetailsStore(db)
        val original=store.load(f.project,f.id)
        val initial=AppNavigationState().selectTab(AppRoute.MEASUREMENTS).openMeasurement(f.project,f.id)
        val guard=MeasurementCreateNavigationGuard();var nav by mutableStateOf(initial)
        val calls=AtomicInteger();val expectSaved=AtomicBoolean(mode!="conflict")
        val started=AtomicBoolean();val release=CountDownLatch(1)
        fun apply(next:AppNavigationState) {
            if(next==nav)return
            if(expectSaved.get()) {
                val saved=store.load(f.project,f.id)
                if(mode=="edit")assertEquals(PointPositionMm(800,1150),saved.placements.lines.single().start)
                else assertEquals(PointPositionMm(800,1200),saved.placements.areas.first {it.uuid==f.polygon}.shape.positions.first())
            }
            calls.incrementAndGet();nav=next
        }
        fun navigate(next:AppNavigationState) {
            val current=nav;val accepted=guard.transition(current,next,::apply)
            if(accepted!=current)apply(accepted)
        }
        compose.setContent {CeilingsTheme(mode=="edit" || mode=="uncertain") {
            BackHandler(nav.measurementDetails!=null) {navigate(nav.back())}
            if(nav.measurementDetails!=null)MeasurementDetailsScreen(f.project,f.id,details,PaddingValues(0.dp),0.dp,
                onBack={navigate(nav.back())},onEdit={navigate(nav.editMeasurement(f.project,f.id))},navigationGuard=guard,editStore=store)
            else Text(if(nav.measurementEdit!=null)"Edit destination" else "Back destination")
        }}
        val canvasMatcher=SemanticsMatcher("Canvas") {n->n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        fun canvas()=compose.onNode(canvasMatcher).performScrollTo().also {compose.waitForIdle()}
        fun position(x:Long,y:Long):Offset {
            val size=canvas().fetchSemanticsNode().size
            val contour=measurementDrawingContour(MeasurementGeometryView.Captured(validateMeasurementCreate(original.draft).geometry!!))!!
            val p=measurementDrawingProjection(contour,size.width.toFloat(),size.height.toFloat(),compose.density.density)!!.project(DrawingPoint(x.toDouble(),y.toDouble()))
            return Offset(p.x.toFloat(),p.y.toFloat())
        }
        fun openEdit() {
            // Header Edit is distinct from each catalog element's Edit action.
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.onAllNodes(hasText("Редагувати") and hasClickAction()).onFirst()
                .assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick){assertTrue(it())}
        }
        fun dialog(label:String)=compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
        fun awaitDialog()=compose.waitUntil(15000){compose.onAllNodesWithText("Незбережені зміни креслення").fetchSemanticsNodes().isNotEmpty()}
        try {
            compose.waitUntil(15000) {compose.onAllNodes(hasText("Сегмент 1: 1 м") and hasClickAction()).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.Disabled)==null}}
            val x=1000L;val y=if(mode=="edit")950L else 1500L
            val start=position(x,y);canvas().performTouchInput {click(start)}
            val a=position(x,y);val b=position(x+300,y+200);canvas().performTouchInput {swipe(a,b,500)}
            val before=OverlayDragFixture.snapshot(raw)
            if(mode=="conflict") {
                store.update(original,original.draft.copy(notes="Concurrent writer"),now=150)
                compose.waitForIdle()
            }
            val expectedDb=OverlayDragFixture.snapshot(raw)
            when(mode) {
                "rollback"->fault.failNext(DetailsExitFaultDriver.Fault.ROLLBACK)
                "uncertain"->fault.failNext(DetailsExitFaultDriver.Fault.LOST_RESPONSE)
                "back"->fault.afterInsert={started.set(true);check(release.await(15,TimeUnit.SECONDS))}
            }
            if(mode=="edit")openEdit() else InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitDialog();dialog("Зберегти креслення").assertIsEnabled().performClick()
            when(mode) {
                "back"->{
                    compose.waitUntil(15000){started.get()}
                    assertEquals(0,calls.get());assertEquals(initial,nav);assertTrue(guard.blocksExit(nav))
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    assertEquals(0,calls.get());assertEquals(initial,nav)
                    release.countDown()
                }
                "rollback"->{
                    compose.waitUntil(15000){compose.onAllNodes(hasText("Зміни не записано.",substring=true)).fetchSemanticsNodes().isNotEmpty()}
                    awaitDialog();assertEquals(0,calls.get());assertEquals(before,OverlayDragFixture.snapshot(raw))
                    dialog("Зберегти креслення").assertIsEnabled().performClick()
                }
                "uncertain"->{
                    compose.waitUntil(15000){compose.onAllNodes(hasText("Повторити ту саму спробу") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()}
                    assertEquals(0,calls.get());assertEquals(initial,nav)
                    val committed=OverlayDragFixture.snapshot(raw)
                    dialog("Повторити ту саму спробу").assertIsEnabled().performClick()
                    compose.waitUntil(15000){calls.get()==1};assertEquals(committed,OverlayDragFixture.snapshot(raw))
                }
                "conflict"->{
                    compose.waitUntil(15000){compose.onAllNodes(hasText("Замір змінено іншим редактором",substring=true)).fetchSemanticsNodes().isNotEmpty()}
                    awaitDialog();assertEquals(0,calls.get());assertEquals(initial,nav)
                    dialog("Зберегти креслення").assertIsNotEnabled()
                    assertEquals(expectedDb,OverlayDragFixture.snapshot(raw))
                    dialog("Продовжити").performClick();compose.waitForIdle()
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);awaitDialog();dialog("Відхилити зміни").performClick()
                }
            }
            compose.waitUntil(15000){calls.get()==1};compose.waitForIdle()
            assertEquals(1,calls.get())
            if(mode=="edit")assertEquals(MeasurementEdit(f.project,f.id),nav.measurementEdit) else assertNull(nav.measurementDetails)
            if(mode=="conflict")assertEquals(expectedDb,OverlayDragFixture.snapshot(raw))
            else assertEquals(2L,raw.executeQuery(null,"SELECT count(*) FROM ceiling_operations",{c->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))},0).value)
        } finally {
            release.countDown();fault.afterInsert=null
            compose.runOnIdle {nav=initial.back()};compose.waitForIdle()
            runBlocking {details.elements.closeAndAwaitWrites();store.closeAndAwaitPointReads()}
            raw.close();context.deleteDatabase(name);key.fill(0)
        }
    }
}
