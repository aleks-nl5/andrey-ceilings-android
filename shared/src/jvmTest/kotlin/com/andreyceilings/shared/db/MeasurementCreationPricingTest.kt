package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.*
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.navigation.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.*
import kotlin.test.*

class MeasurementCreationPricingTest {
    @Test fun realScalarsRemainStrictWhenJdbcFacadeReadsDoublesThroughText() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val project = ProjectRepository(db).createReturningId(null, "Scalar round trip", createdAt = 1)
            val draft = MeasurementCreateFixtures.draft(project, "rectangle", MeasurementPrecision.ONE_CENTIMETER)
            val id = MeasurementCreateStore(db) { 2 }.create(draft)
            val saved = MeasurementRepository(db).find(id)!!
            val textDoubles = object : SqlDriver by driver {
                override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                    parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> =
                    driver.executeQuery(identifier, sql, { cursor -> mapper(object : SqlCursor by cursor {
                        // SQLite columnText uses 15 significant digits by default;
                        // Xerial getString uses Java's round-trip representation instead.
                        override fun getDouble(index: Int) = cursor.getDouble(index)?.let {
                            String.format(java.util.Locale.ROOT, "%.15g", it).toDouble()
                        }
                    }) }, parameters, binders)
            }
            val facadeDb = PrototypeDatabase(textDoubles)
            assertNotEquals(MeasurementGeometryCodec.read(saved.drawingJson).areaSquareMeters,
                MeasurementRepository(facadeDb).find(id)!!.areaSquareMeters, "Reproduce native facade loss of REAL precision")
            val reader = LivePricingReader(facadeDb)
            assertIs<PricingReadResult.Loaded>(reader.read(project, saved.uuid))
            driver.execute(null, "UPDATE room_measurements SET areaSquareMeters = areaSquareMeters + 0.000001 WHERE id = $id", 0)
            assertEquals(PricingReadResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY), reader.read(project, saved.uuid))
        }
    }

    @Test fun bothHundredthMetreSidesKeepExactGeometryAndAllowQuote() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val db = PrototypeDatabase(driver)
            val f = MeasurementPricingFixture.seed(db)
            val draft = MeasurementCreateDraft(f.projectId, MeasurementPrecision.ONE_CENTIMETER, "Decimal room", "",
                MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4.12", "width" to "3.34")))
            MeasurementCreateStore(db) { 1000 }.create(draft)
            val elements = MeasurementElementRepository(db)
            val profile = (elements.find(f.profileUuid)!!.source as ElementSource.CatalogSelection).priceItemUuid
            elements.createCatalogSelection(draft.recordUuid, profile, ElementQuantity.CeilingPerimeter, 1001,
                approval = ElementApproval.APPROVED, businessRole = BusinessRole.PRIMARY_PROFILE)
            elements.createCatalogSelection(draft.recordUuid, PriceRepository(db).find(f.priceId)!!.uuid, ElementQuantity.CeilingArea, 1001,
                approval = ElementApproval.APPROVED, businessRole = BusinessRole.CANVAS)
            val read = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(f.projectId, draft.recordUuid))
            assertEquals(validateMeasurementCreate(draft).geometry, read.source.geometry)
            assertEquals(13.7608, read.source.geometry.areaSquareMeters, 0.000000000001)
            val preview = read.buildPreview()
            assertTrue(preview.canFinalizeFull, preview.toString())
        }
    }

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
