package com.andreyceilings.app

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.theme.CeilingsTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class WorkspaceAddPointTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun catalogPointKeepsDeclaredQuantityAndStablePlacementAcrossSaveAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name = "workspace-p5b-point.db"
        context.deleteDatabase(name)
        val key = ByteArray(32) { 67 }
        val driver = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val database = PrototypeDatabase(driver)
        val fixture = PointPlacementProof.fixture(database)
        val store = MeasurementEditStore(database) { 100 }
        val document = store.load(fixture.project, fixture.id)
        val shown = mutableStateOf(MeasurementEditModel(store, document))
        val prices = PriceRepository(database)
        val catalogId = prices.createInGroup("p5b.ui.point", "Тестова позначка", PriceGroupRepository(database).all().first().uuid,
            1.0, 6, "шт.", com.andreyceilings.shared.prices.QuantityMode.MANUAL, com.andreyceilings.shared.prices.PlacementKind.POINT)
        val itemUuid = checkNotNull(prices.find(catalogId)).uuid
        val canvasItem = prices.all().single { it.code == "canvas.pvc_matte" }
        val matchCanvas = SemanticsMatcher("measurement drawing") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Контур стелі") }
        }
        val canvas = compose.onNode(matchCanvas)

        fun click(label: String) {
            val action = (hasText(label) or hasContentDescription(label)) and hasClickAction()
            val node = compose.onNode(action)
            if (compose.onAllNodes(action and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty())
                node.performScrollTo()
            node.assertIsEnabled().performClick()
        }

        fun position(x: Long, y: Long): Offset {
            val projection = canvas.fetchSemanticsNode().config[MeasurementDrawingSemantics.Projection]
            val projected = projection.project(DrawingPoint(x.toDouble(), y.toDouble()))
            return Offset(projected.x.toFloat(), projected.y.toFloat())
        }

        try {
            compose.setContent {
                CeilingsTheme(false) { MeasurementEditScreen(shown.value, PaddingValues(0.dp), onBack = {}) }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodes(matchCanvas).fetchSemanticsNodes().any {
                    it.config.getOrNull(MeasurementDrawingSemantics.EditingEnabled) == true
                }
            }
            fun assertLiveMetrics() {
                val draft = shown.value.state.value.draft
                val geometry = assertNotNull(validateMeasurementCreate(draft).geometry)
                compose.onNodeWithTag("measurement-live-area").assertIsDisplayed().assertContentDescriptionEquals(
                    "Площа: ${formatMeasurementArea(geometry.areaSquareMeters, draft.precision)} м²")
                compose.onNodeWithTag("measurement-live-perimeter").assertIsDisplayed().assertContentDescriptionEquals(
                    "Периметр: ${formatMeasurementLength(geometry.perimeterMeters, draft.precision)} м")
            }
            assertLiveMetrics()
            click("Вартість")
            assertLiveMetrics()
            click("На весь екран")
            assertLiveMetrics()
            click("Згорнути")
            click("Елементи")
            assertLiveMetrics()
            compose.onNodeWithTag("p5b-material-CANVAS").assertIsDisplayed().performClick()
            val canvasTag = "p5b-catalog-${canvasItem.uuid}"
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(canvasTag)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(canvasTag).performScrollTo().performClick()
            compose.onNodeWithTag("p5b-material-CANVAS").assertIsDisplayed()
            compose.onNodeWithTag("p5b-material-PRIMARY_PROFILE").assertIsDisplayed()
            compose.onNodeWithTag("measurement-workspace-add").performClick()
            compose.onNodeWithTag("p5b-catalog-search").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            val itemTag = "p5b-catalog-$itemUuid"
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(itemTag)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(itemTag).performScrollTo().performClick()
            compose.onNodeWithText("Заявлена кількість, шт.").performTextInput("3")
            click("Додати й розмістити")
            click("Вписати креслення")
            val approximate = position(1200, 800)
            canvas.performTouchInput { click(approximate) }
            val added = compose.runOnIdle { shown.value.displayPoints().single() }
            assertTrue(PointPlacementRepository(database).forMeasurement(document.draft.recordUuid).isEmpty())
            assertEquals(1, MeasurementElementRepository(database).forMeasurement(document.draft.recordUuid).size)

            click("Редагувати розміри")
            compose.onNode(isDialog()).assertDoesNotExist()
            click("Уздовж + усередину")
            compose.onNodeWithText("Від початку вздовж стіни, м").performScrollTo().performTextInput("1,25")
            compose.onNodeWithText("Відступ усередину, м").performScrollTo().performTextInput("0,85")
            click("Застосувати розміри")
            val precise = compose.runOnIdle { shown.value.displayPoints().single() }
            assertEquals(added.uuid, precise.uuid)
            assertEquals(1250L, precise.xMm)
            assertEquals(850L, precise.yMm)
            click("Скасувати дію")
            compose.runOnIdle { assertEquals(added, shown.value.displayPoints().single()) }
            click("Повторити")
            compose.onNodeWithTag("measurement-edit-save").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { !shown.value.state.value.dirty || shown.value.state.value.error != null }
            assertFalse(shown.value.state.value.dirty, shown.value.state.value.error)
            val reopened = store.load(fixture.project, fixture.id)
            val material = reopened.materialElements.single()
            assertEquals(ElementApproval.APPROVED, material.approval)
            assertEquals(canvasItem.uuid, (material.source as ElementSource.CatalogSelection).priceItemUuid)
            val point = reopened.placements.points.single()
            assertEquals(added.uuid, point.uuid)
            assertEquals(1250L, point.xMm)
            assertEquals(850L, point.yMm)
            val work = assertNotNull(MeasurementElementRepository(database).find(point.elementUuid))
            assertEquals(ElementQuantity.Exact(3000), work.quantity)
            assertEquals(1000L, work.placedQuantityMilli)
            compose.runOnIdle { shown.value = MeasurementEditModel(store, reopened) }
            compose.waitUntil(15_000) { canvas.fetchSemanticsNode().config.getOrNull(MeasurementDrawingSemantics.Points)?.singleOrNull()?.uuid == point.uuid }
        } finally {
            store.closePointReads()
            driver.close()
            context.deleteDatabase(name)
            key.fill(0)
        }
    }
}
