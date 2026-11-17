package com.andreyceilings.app

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.prices.*
import com.andreyceilings.shared.theme.CeilingsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class MeasurementCreationPricingTouchTest {
    @get:Rule val compose = createComposeRule()
    @Test fun globalCreateThenQuoteAndEncryptedReopen() = createThenQuote(true)
    @Test fun objectCreateThenQuoteAndEncryptedReopen() = createThenQuote(false)

    private fun createThenQuote(global: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "measurement-pricing-$global.db"; val key = ByteArray(32) { 49 }
        context.deleteDatabase(name)
        fun open() = createEncryptedPrototypeDriver(context, key, name, enableWalForTesting = true, initializePriceCatalog = true)
        val driver = open(); val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "Pricing parent", createdAt = 100)
        val group = PriceGroupRepository(db).create("Route materials", 100)
        PriceRepository(db).createInGroup("route.profile", "Route profile", group, 10.0, 100, unit = "м.п.", quantityMode = QuantityMode.CEILING_PERIMETER)
        PriceRepository(db).createInGroup("route.canvas", "Route canvas", group, 20.0, 100, unit = "м²", quantityMode = QuantityMode.CEILING_AREA)
        val measurements = MeasurementsStore(db)
        val create = MeasurementCreateStore(db) { 1794900000200 }
        val details = MeasurementDetailsStore(db)
        val edit = MeasurementEditStore(db) { 300 }
        val guard = MeasurementCreateNavigationGuard().also { it.bindQuotes(details.elements) }
        var nav by mutableStateOf(if (global) AppNavigationState().selectTab(AppRoute.MEASUREMENTS).chooseMeasurementProject()
            else AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).createMeasurement(project))
        var mounted by mutableStateOf(true)
        var uuid = ""; var signature = ""
        fun list() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
        fun fill(label: String, value: String) {
            list().performScrollToNode(hasText(label))
            compose.onNode(hasText(label) and hasSetTextAction()).performTextInput(value)
        }
        try {
            compose.setContent { if (mounted) CeilingsTheme(false) {
                if (global) MeasurementsScreen(measurements, create, details, edit, nav,
                    MeasurementPrecision.ONE_CENTIMETER, PaddingValues(8.dp), 0.dp, guard,
                    onNavigate = { nav = guard.transition(nav, it) }, onEditSaved = { _, _ -> },
                    onUseMeasurement = {}, onOpenEstimates = {}, onCreateProject = {})
                else nav.measurementCreate?.let { route -> MeasurementCreateScreen(route.projectId,
                    MeasurementPrecision.ONE_CENTIMETER, create, PaddingValues(8.dp), 0.dp,
                    onBack = { nav = nav.back() }, onSaved = { nav = guard.transition(nav, nav.measurementCreated(project, it)) }, navigationGuard = guard) }
                    ?: nav.measurementDetails?.let { route -> MeasurementDetailsScreen(route.projectId, route.measurementId,
                        details, PaddingValues(8.dp), 0.dp, onBack = { nav = nav.back() }, navigationGuard = guard,
                        editStore = edit, quoteOpen = nav.measurementQuote, onOpenQuote = { nav = nav.openMeasurementQuote() }) }
            } }
            if (global) {
                compose.waitUntil(10000) { compose.onAllNodesWithText("Pricing parent").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Pricing parent").performClick()
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Назва заміру *").fetchSemanticsNodes().isNotEmpty() }
            fill("Назва заміру *", "Room")
            fill(measurementTemplateFieldLabel("rectangle", "length"), "4")
            fill(measurementTemplateFieldLabel("rectangle", "width"), "3")
            compose.onNodeWithText("Зберегти").assertIsEnabled().performClick()
            compose.waitUntil(10000) { nav.measurementDetails != null }
            val row = MeasurementRepository(db).all().single(); uuid = row.uuid
            for ((title, role) in listOf("Route profile" to "Основний профіль", "Route canvas" to "Полотно")) {
                compose.waitUntil(10000) {
                    runCatching { list().performScrollToNode(hasText("Додати елемент")); compose.onNodeWithText("Додати елемент").assertIsEnabled(); true }.getOrDefault(false)
                }
                compose.onNodeWithText("Додати елемент").performClick()
                compose.onNodeWithText("З прайсу").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Пошук за назвою або кодом").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Пошук за назвою або кодом") and hasSetTextAction()).performTextInput(title)
                compose.onNodeWithText(title).performClick()
                compose.onNode(hasText("Погоджено") and hasClickAction()).performScrollTo().performClick()
                compose.onNode(hasText(role) and hasClickAction()).performScrollTo().performClick()
                compose.onNodeWithText("Зберегти").assertIsEnabled().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Елемент із прайсу").fetchSemanticsNodes().isEmpty() }
            }
            val loaded = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(project, uuid),
                "global=$global row=${MeasurementRepository(db).find(row.id)} walls=${db.livePricingQueries.selectPricingWalls(uuid).executeAsList()}")
            assertTrue(loaded.buildPreview().canFinalizeFull, loaded.buildPreview().toString())
            signature = loaded.source.signature
            assertEquals(4, loaded.source.walls.size)
            compose.waitUntil(10000) {
                runCatching { list().performScrollToNode(hasText("Попередній кошторис")); compose.onNodeWithText("Попередній кошторис").assertIsEnabled(); true }.getOrDefault(false)
            }
            compose.onNodeWithText("Попередній кошторис").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("До заміру").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(measurementMoney(38000), substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("дані заміру несумісні", substring = true).assertCountEquals(0)
            assertEquals(38000, loaded.buildPreview().confirmedTotalCents)
        } finally {
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            runBlocking { details.elements.closeAndAwaitWrites(); edit.closeAndAwaitPointReads() }
            driver.close()
        }
        try {
            open().use { reopened ->
                val loaded = assertIs<PricingReadResult.Loaded>(LivePricingReader(PrototypeDatabase(reopened)).read(project, uuid))
                assertEquals(signature, loaded.source.signature)
                assertFalse(loaded.buildPreview().fatalProblems.any { it.code == PricingProblemCode.INVALID_SOURCE })
            }
        } finally { key.fill(0); context.deleteDatabase(name) }
    }
}
