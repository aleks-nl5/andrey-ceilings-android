package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.*
import kotlin.test.*

class MeasurementCreationPricingTest {
    @Test fun bothCreateRoutesHaveCoherentPersistedWallsAndLivePricing() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Pricing routes", createdAt = 1)
            val group = PriceGroupRepository(db).create("Route materials", 1)
            val prices = PriceRepository(db)
            val profile = prices.createInGroup("route.profile", "Route profile", group, 10.0, 1, unit = "м.п.", quantityMode = QuantityMode.CEILING_PERIMETER)
            val canvas = prices.createInGroup("route.canvas", "Route canvas", group, 20.0, 1, unit = "м²", quantityMode = QuantityMode.CEILING_AREA)
            for (global in listOf(true, false)) for (precision in MeasurementPrecision.entries) {
                for (shape in listOf("rectangle", "l_shaped", "polygon")) {
                    val parent = if (global) AppNavigationState().selectTab(AppRoute.MEASUREMENTS).chooseMeasurementProject()
                        else AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(project)
                    val route = parent.createMeasurement(project)
                    val draft = MeasurementCreateFixtures.draft(project, shape, precision)
                    val id = MeasurementCreateStore(db) { 1794900000200 }.create(draft)
                    val saved = route.measurementCreated(project, id)
                    assertEquals(id, saved.measurementDetails!!.measurementId)
                    val row = MeasurementRepository(db).find(id)!!
                    val store = MeasurementElementsStore(db, { 1794900000201 }, Dispatchers.Unconfined)
                    try {
                        for ((priceId, role) in listOf(profile to BusinessRole.PRIMARY_PROFILE, canvas to BusinessRole.CANVAS)) {
                            val price = prices.find(priceId)!!
                            val item = ElementCatalogItem(price.uuid, price.groupUuid, price.title, price.code, price.unit,
                                QuantityMode.entries.single { it.value == price.quantityMode }, PlacementKind.entries.single { it.value == price.placementKind })
                            store.save(ElementRoute(project, id, row.uuid), ElementEditorDraft(action = ElementEditorAction.CREATE_CATALOG,
                                selection = item, approval = ElementApproval.APPROVED, businessRole = role))
                        }
                    } finally { runBlocking { store.closeAndAwaitWrites() } }
                    val read = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(project, row.uuid), "$global/$shape/$precision")
                    assertEquals(read.source.geometry.vertices.size, read.source.walls.size)
                    assertTrue(read.buildPreview().canFinalizeFull, read.buildPreview().toString())
                }
            }
        }
    }
}
