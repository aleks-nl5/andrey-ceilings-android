package com.andreyceilings.app

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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
        val details = MeasurementDetailsStore(db)
        val edit = MeasurementEditStore(db) { 300 }
        val guard = MeasurementCreateNavigationGuard().also { it.bindQuotes(details.elements) }
        var nav by mutableStateOf(if (global) AppNavigationState().selectTab(AppRoute.MEASUREMENTS).chooseMeasurementProject()
            else AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project).createMeasurement(project))
        var mounted by mutableStateOf(true)
        var uuid = ""; var signature = ""
        fun list() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onLast()
        fun fill(label: String, value: String) {
            compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextInput(value)
        }
        try {
            compose.setContent { if (mounted) CeilingsTheme(false) {
                if (global) MeasurementsScreen(measurements, details, edit, nav,
                    MeasurementPrecision.ONE_CENTIMETER, PaddingValues(8.dp), 0.dp, guard,
                    onNavigate = { nav = guard.transition(nav, it) },
                    onUseMeasurement = {}, onOpenEstimates = {}, onCreateProject = {})
                else nav.measurementCreate?.let { route -> MeasurementCreateWorkspace(route.projectId,
                    MeasurementPrecision.ONE_CENTIMETER, edit, PaddingValues(8.dp), 0.dp,
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
            compose.onNodeWithTag("measurement-shape-picker").assertExists()
            assertTrue(MeasurementRepository(db).all().isEmpty())
            fill("AB, м", if (global) "4,12" else "4.12")
            fill("BC, м", if (global) "3,34" else "3.34")
            fill("Назва заміру *", "Room")
            compose.runOnIdle {
                val window = compose.activity.window
                WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(10000) {
                compose.runOnIdle {
                    ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) != true
                }
            }
            compose.onNodeWithTag("measurement-edit-context-elements").performClick()
            for ((title, role) in listOf("Route profile" to "PRIMARY_PROFILE", "Route canvas" to "CANVAS")) {
                compose.onNodeWithTag("p5b-material-$role").performClick()
                val row = compose.onNodeWithTag("p5b-catalog-" +
                    PriceRepository(db).all().single { it.title == title }.uuid)
                compose.waitUntil(10000) { runCatching { row.fetchSemanticsNode() }.isSuccess }
                row.performScrollTo().performClick()
            }
            assertTrue(MeasurementRepository(db).all().isEmpty(), "Selecting materials cannot create a shell row")
            compose.onNodeWithTag("measurement-edit-save").assertIsEnabled().performClick()
            compose.waitUntil(10000) { nav.measurementDetails != null }
            val row = MeasurementRepository(db).all().single()
            uuid = row.uuid
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
                    compose.onAllNodesWithText(measurementMoney(42442), substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("дані заміру несумісні", substring = true).assertCountEquals(0)
            list().performScrollToNode(hasText("Розрахункова площа округлена до 0,001 м²"))
            compose.onNodeWithText("Розрахункова площа округлена до 0,001 м²").assertExists()
            assertEquals(42442, loaded.buildPreview().confirmedTotalCents)
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
