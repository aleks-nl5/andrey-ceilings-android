package com.andreyceilings.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import com.andreyceilings.shared.theme.CeilingsTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Production controls + production shared screen, with real Android pointer injection. */
class AreaFeatureTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun lightNormalAndFullscreenAtomicCrud()=scenario(false)
    @Test fun darkNormalAndFullscreenAtomicCrud()=scenario(true)
    private fun scenario(dark:Boolean) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name="area-touch-$dark.db";context.deleteDatabase(name)
        val key=ByteArray(32){86}
        val driver=createEncryptedPrototypeDriver(context,key,name,enableWalForTesting=true,initializePriceCatalog=true)
        val db=PrototypeDatabase(driver);val f=PointPlacementProof.fixture(db)
        val prices=PriceRepository(db)
        val price=prices.find(prices.createInGroup("area.touch","Область UI",PriceGroupRepository(db).all().first().uuid,1.0,6,"шт.",QuantityMode.MANUAL,PlacementKind.AREA))!!
        val element=MeasurementElementRepository(db).createCatalogSelection(f.element.measurementUuid,price.uuid,ElementQuantity.Unknown,7, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val store=MeasurementEditStore(db){100}
        val model=MeasurementEditModel(store,store.load(f.project,f.id))
        compose.setContent {CeilingsTheme(dark) {MeasurementEditScreen(model,PaddingValues(0.dp),0.dp,{},null)}}
        val matcher=SemanticsMatcher("ceiling canvas") {n->n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {it.startsWith("Контур стелі")}}
        fun stateSummary(): String {
            val state = model.state.value
            return "theme=${if (dark) "dark" else "light"}, tool=${state.area.tool}, " +
                "vertices=${state.area.vertices}, diameter=${state.area.diameter}, " +
                "areas=${model.displayAreas().map { it.role to it.uuid }}, selected=${state.area.selected}, error=${state.error}"
        }

        fun click(text: String) {
            val action = (hasText(text) or hasContentDescription(text)) and hasClickAction()
            val target = compose.onNode(action)
            if (compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
                target.performScrollTo()
            }
            target.assertIsEnabled().performClick()
            compose.waitForIdle()
        }

        // Android's IME/window resize can outlive Compose's test clock. Wait for
        // a quiet interval in the actual geometry AND projection before injection.
        fun settledCanvas(): SemanticsNode {
            var previous: List<Any>? = null
            var unchangedSince = SystemClock.elapsedRealtime()
            compose.waitUntil(15000) {
                val node = compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()
                val projection = node?.config?.getOrNull(MeasurementDrawingSemantics.Projection)
                val viewport = node?.config?.getOrNull(MeasurementDrawingSemantics.ViewportBounds)
                val now = SystemClock.elapsedRealtime()
                val snapshot = if (node != null && projection != null && viewport != null &&
                    viewport.height > 0f && node.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled) == true)
                    listOf(node.boundsInRoot, node.size, viewport, projection) else null
                if (snapshot == null || snapshot != previous) {
                    previous = snapshot
                    unchangedSince = now
                    false
                } else now - unchangedSince >= 250L
            }
            return compose.onNode(matcher).fetchSemanticsNode()
        }

        fun setField(node: SemanticsNodeInteraction, value: String) {
            // AREA verifies input, draft geometry and actual Canvas touches.
            // MeasurementWorkspaceLayoutTest separately exercises focus and the IME.
            node.performScrollTo().performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString(value)))
            }
        }

        fun tap(x: Long, y: Long, expectedVertices: Int? = null) {
            val canvas = compose.onNode(matcher)
            val node = settledCanvas()
            val projection = node.config[MeasurementDrawingSemantics.Projection]
            val viewport = node.config[MeasurementDrawingSemantics.ViewportBounds]
            val p = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
            assertTrue(
                p.x >= viewport.left && p.x < viewport.right && p.y >= viewport.top && p.y < viewport.bottom,
                "Projected touch ($x,$y) -> (${p.x},${p.y}) is outside $viewport; ${stateSummary()}"
            )
            canvas.performTouchInput { click(Offset(p.x.toFloat(),p.y.toFloat())) }
            compose.runOnIdle {
                if (expectedVertices != null) {
                    assertEquals(expectedVertices, model.state.value.area.vertices.size,
                        "Tap ($x,$y) -> (${p.x},${p.y}) must reach the area tool; ${stateSummary()}")
                }
            }
        }

        fun begin(label: String?, tool: AreaTool) {
            Log.i("P5BAreaTest", "Begin ${label ?: "catalog area"}; dark=$dark")
            compose.onNodeWithTag("measurement-workspace-add").assertIsDisplayed().assertIsEnabled().performClick()
            if (label != null) {
                click("Позначка на кресленні")
                click(label)
            }
            else {
                compose.onNodeWithTag("p5b-linked-${element.uuid}").performScrollTo().assertIsEnabled().performClick()
                assertNull(model.state.value.area.tool)
                compose.onNodeWithTag("p5b-place-existing").performScrollTo().assertIsEnabled().performClick()
            }
            compose.runOnIdle {
                assertEquals(tool, model.state.value.area.tool, "Area tool must be active; ${stateSummary()}")
                assertTrue(model.state.value.area.vertices.isEmpty(), stateSummary())
            }
        }

        fun finish(expectedCount: Int, role: AreaRole) {
            compose.runOnIdle {
                assertNotNull(model.areaCreationShape(), "A valid draft shape must exist; ${stateSummary()}")
            }
            Log.i("P5BAreaTest", "Finish $role; dark=$dark")
            click("Завершити фігуру в чернетці")
            var standaloneId: String? = null
            compose.runOnIdle {
                assertEquals(expectedCount, model.displayAreas().size, "Finish $role; ${stateSummary()}")
                assertNull(model.state.value.area.tool, "Creation must finish; ${stateSummary()}")
                val selected = model.displayAreas().singleOrNull { it.uuid == model.state.value.area.selected }
                assertNotNull(selected, stateSummary())
                assertEquals(role, selected.role, stateSummary())
                if (selected.elementUuid == null) standaloneId = selected.uuid
                assertTrue(AreaFeatureRepository(db).all().isEmpty(), "Only Save may persist areas")
            }
            // Never wait for Compose semantics from within runOnIdle's UI thread.
            standaloneId?.let { id ->
                compose.onNodeWithTag("p5b-standalone-area-$id").assertExists()
                compose.onNodeWithTag("p5b-open-standalone-area-$id").assertExists()
            }
        }

        try {
            settledCanvas()
            click("Елементи")

            begin("Перешкода", AreaTool.RECTANGLE)
            tap(500,500,1)
            tap(1000,1000,2)
            finish(1, AreaRole.OBSTACLE)

            begin("Позначка отвору", AreaTool.CIRCLE)
            setField(compose.onNodeWithText("Діаметр, м (парні мм)"), "0,2")
            compose.runOnIdle {
                assertEquals("0,2", model.state.value.area.diameter, "Circle diameter input; ${stateSummary()}")
            }
            tap(2000,1500,1)
            finish(2, AreaRole.VOID_MARKER)
            compose.runOnIdle {
                val circle = model.displayAreas().single { it.role == AreaRole.VOID_MARKER }.shape as AreaShape.Circle
                assertEquals(100L, circle.radiusMm, stateSummary())
            }

            begin(null, AreaTool.RECTANGLE)
            tap(1500,500,1)
            tap(2000,1000,2)
            finish(3, AreaRole.GENERAL_AREA)
            compose.runOnIdle {
                assertEquals(element.uuid, model.displayAreas().single { it.role == AreaRole.GENERAL_AREA }.elementUuid)
            }
            click("Скасувати дію")
            compose.runOnIdle {assertEquals(2,model.displayAreas().size)}
            click("Повторити")
            compose.runOnIdle { assertEquals(3, model.displayAreas().size, "Redo; ${stateSummary()}") }
            Log.i("P5BAreaTest", "Open fullscreen; dark=$dark")
            click("На весь екран")
            click("Вписати креслення")
            val canvas=compose.onNode(matcher)
            settledCanvas()
            canvas.performTouchInput {pinch(center-Offset(30f,0f),center+Offset(30f,0f),center-Offset(50f,0f),center+Offset(50f,0f))}
            click("Вписати креслення")
            val circleInFullscreen = compose.runOnIdle {
                model.displayAreas().single { it.shape is AreaShape.Circle }
            }
            val fullscreenCenter = (circleInFullscreen.shape as AreaShape.Circle).center
            val fullscreenProjection = settledCanvas().config[MeasurementDrawingSemantics.Projection]
            val screenPoint = fullscreenProjection.project(DrawingPoint(
                fullscreenCenter.xMm.toDouble(), fullscreenCenter.yMm.toDouble()))
            canvas.performTouchInput {
                advanceEventTime(400)
                doubleClick(Offset(screenPoint.x.toFloat(), screenPoint.y.toFloat()))
            }
            compose.runOnIdle { assertEquals(circleInFullscreen.uuid, model.state.value.area.selected) }
            compose.runOnIdle { assertEquals(circleInFullscreen.uuid,
                model.state.value.area.properties?.uuid) }
            compose.onNodeWithText("Діаметр, м (парні мм)").assertExists()
            click("Згорнути")
            // Wait until the normal canvas and its viewport have replaced the fullscreen layout.
            compose.onNodeWithTag("measurement-edit-normal-panel").assertExists()
            compose.waitForIdle()
            val circle = compose.runOnIdle {
                model.displayAreas().single { it.shape is AreaShape.Circle }
            }
            val center = (circle.shape as AreaShape.Circle).center
            compose.runOnIdle {
                model.discardAreaProperties()
                model.clearDrawingSelection()
            }
            tap(center.xMm, center.yMm)
            compose.onNodeWithTag("p5b-standalone-area-${circle.uuid}").assertExists()
            compose.onNodeWithTag("p5b-open-standalone-area-${circle.uuid}").assertExists()
            compose.onNodeWithText("Примітка фігури").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(
                    circle.uuid, model.state.value.area.selected,
                    "Tapping the circle center (${center.xMm},${center.yMm}) must select its UUID; " +
                        "areaTool=${model.state.value.area.tool}; areas=${model.displayAreas().map { it.uuid }}"
                )
            }
            compose.onNodeWithTag("p5b-open-standalone-area-${circle.uuid}").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            setField(compose.onNodeWithTag("p5b-area-note"), "Тестова позначка")
            click("Застосувати зміни")
            compose.runOnIdle {
                assertEquals("Тестова позначка", model.displayAreas().single { it.uuid == circle.uuid }.note)
            }
            compose.onNodeWithTag("p5b-delete-standalone-area-${circle.uuid}").performScrollTo().performClick()
            compose.onNodeWithText("Видалити", useUnmergedTree = true).performClick()
            compose.runOnIdle {assertEquals(2,model.displayAreas().size)}
            click("Скасувати дію")
            val ids=model.displayAreas().map {it.uuid}.toSet()
            Log.i("P5BAreaTest", "Save and reopen; dark=$dark")
            compose.onNodeWithTag("measurement-edit-save").performClick()
            compose.waitUntil(15000) {!model.state.value.dirty && !model.state.value.saving || model.state.value.error!=null}
            compose.runOnIdle {
                assertTrue(!model.state.value.dirty && !model.state.value.saving, model.state.value.error)
                val saved = store.load(f.project, f.id).placements.areas
                assertEquals(ids, saved.map { it.uuid }.toSet())
                assertEquals("Тестова позначка", saved.single { it.uuid == circle.uuid }.note)
            }
        } catch (failure: Throwable) {
            runCatching { captureMeasurementWorkspaceScreenshot("area-touch-${if (dark) "dark" else "light"}-failure") }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {store.closePointReads();driver.close();context.deleteDatabase(name);key.fill(0)}
    }
}
