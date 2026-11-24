package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

object WallVersionMigrationProof {
    // Construct the shipped schema from its original migrations without installing any v20 DDL.
    val v19Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 19L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            RoundedAreaPolicyProof.v18Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 18, 19)
            PriceCatalogSchema.verifyStructure(driver, version)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
            vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Frozen v19 fixture")
    }

    data class Seed(
        val projectId: Long,
        val document: MeasurementEditDocument,
        val stale: Room_measurements,
        val seriesUuid: String,
        val frozenPayload: String,
        val preview: LiveEstimatePreview
    )

    data class Evidence(
        val seed: Seed,
        val moved: MeasurementEditDocument,
        val walls: List<Ceiling_walls>,
        val staleWalls: List<Ceiling_walls>,
        val receipts: List<SelectPricingReceipts>
    )

    fun seed(driver: SqlDriver): Seed {
        val db = PrototypeDatabase(driver)
        val fixture = MeasurementPricingFixture.seed(db)
        val measurements = MeasurementRepository(db)
        val row = assertNotNull(measurements.findByUuid(fixture.measurementUuid))
        val prices = PriceRepository(db)
        val group = assertNotNull(prices.find(fixture.priceId)).groupUuid
        val elements = MeasurementElementRepository(db)
        fun element(code: String, unit: String, kind: PlacementKind, quantity: Long,
            approval: ElementApproval): MeasurementElement {
            val item = assertNotNull(prices.find(prices.createInGroup(code, code, group, 10.0, 110,
                unit, QuantityMode.MANUAL, kind)))
            return elements.createCatalogSelection(row.uuid, item.uuid, ElementQuantity.Exact(quantity), 111,
                approval = approval, businessRole = BusinessRole.ADDITIONAL_WORK)
        }
        val lineItem = element("Migration line", "м.п.", PlacementKind.LINE, 1000, ElementApproval.DISCUSSING)
        val store = MeasurementEditStore(db) { 150 }
        val initial = store.load(row.projectId, row.id)
        val line = LinePlacement(newRecordUuid(), lineItem.uuid, PointPositionMm(500, 500),
            PointPositionMm(1500, 500), initial.updatedAt, 0, 0)
        val area = AreaFeature(newRecordUuid(), row.uuid, AreaShape.Circle(PointPositionMm(2500, 1500), 200),
            initial.updatedAt, 0, 0, role = AreaRole.OBSTACLE)
        // A real redraw keeps the v19 wall projection coherent while seeding both other placement types.
        val redrawn = store.update(initial, initial.draft.reduce(MeasurementCreateIntent.Dimension("length", "5")),
            lineEdits = mapOf(line.uuid to LinePlacementEdit(line)), areaEdits = mapOf(area.uuid to AreaFeatureEdit(area)))
        val point = element("Migration spotlight", "шт.", PlacementKind.POINT, 1000, ElementApproval.APPROVED)
        PointPlacementRepository(db).addPoint(point.version, redrawn.updatedAt, 700, 600, 152)
        val document = store.load(row.projectId, row.id)
        val loaded = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(row.projectId, row.uuid))
        val preview = loaded.buildPreview()
        assertTrue(preview.canFinalizeFull, "The legacy fixture must start with a complete quote")
        val presenter = TransientQuotePresenter(row.uuid)
        presenter.acceptRead(presenter.beginRead(), loaded)
        val repo = ImmutableEstimateRepository(db)
        val quote = repo.saveIndividual(assertNotNull(presenter.saveRequest(row.projectId, "Before migration")), 2026, 160)
        presenter.close()
        val staleFixture = MeasurementPricingFixture.seed(db)
        val stale = assertNotNull(measurements.findByUuid(staleFixture.measurementUuid))
        return Seed(row.projectId, document, stale, quote.seriesUuid, db.frozenEstimateQueries.selectFrozenV1(quote.seriesUuid).executeAsOne().payload,
            preview)
    }

    fun move(driver: SqlDriver, seed: Seed): Evidence {
        val db = PrototypeDatabase(driver)
        val before = seed.document
        val uuid = before.draft.recordUuid
        val point = before.placements.points.single()
        val moved = MeasurementEditStore(db) { 200 }.update(before, before.draft,
            edits = mapOf(point.uuid to PointMountingEdit(freeX = 1100, freeY = 900)))
        assertEquals(before.sourceDrawingJson, moved.sourceDrawingJson)
        assertEquals(PointPositionMm(1100, 900), moved.placements.points.single().let { PointPositionMm(it.xMm, it.yMm) })
        assertEquals(before.placements.lines, moved.placements.lines)
        assertEquals(before.placements.areas, moved.placements.areas)
        val walls = db.livePricingQueries.selectPricingWalls(uuid).executeAsList()
        assertTrue(walls.any { it.active == 1L })
        assertTrue(walls.filter { it.active == 1L }.all { it.geometryVersion == before.updatedAt })
        // A separate invalid source has no operation receipt to justify correcting its version.
        driver.execute(null, "UPDATE room_measurements SET updatedAt = updatedAt + 1 WHERE id = ?", 1) {
            bindLong(0, seed.stale.id)
        }
        return Evidence(seed, moved, walls, db.livePricingQueries.selectPricingWalls(seed.stale.uuid).executeAsList(),
            db.livePricingQueries.selectPricingReceipts(uuid).executeAsList())
    }

    fun verify(driver: SqlDriver, evidence: Evidence, migrated: Boolean) {
        val db = PrototypeDatabase(driver)
        db.transaction {
            val version = if (migrated) PriceCatalogSchema.version else v19Schema.version
            assertEquals(version, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
            PriceCatalogSchema.verifyStructure(driver, version)
            val (seed, moved, _, staleWalls, receipts) = evidence
            val uuid = moved.draft.recordUuid
            assertEquals(moved, MeasurementEditStore(db) { 0 }.load(seed.projectId, moved.measurementId))
            assertEquals(expectedWalls(evidence, migrated), db.livePricingQueries.selectPricingWalls(uuid).executeAsList())
            assertEquals(receipts, db.livePricingQueries.selectPricingReceipts(uuid).executeAsList())
            assertEquals(seed.frozenPayload, db.frozenEstimateQueries.selectFrozenV1(seed.seriesUuid).executeAsOne().payload)
            assertEquals(staleWalls, db.livePricingQueries.selectPricingWalls(seed.stale.uuid).executeAsList())
            assertEquals(seed.stale.copy(updatedAt = seed.stale.updatedAt + 1), MeasurementRepository(db).find(seed.stale.id))
            val reader = LivePricingReader(db)
            val invalid = PricingReadResult.Unavailable(PricingProblemCode.INVALID_SOURCE)
            assertEquals(invalid, reader.read(seed.stale.projectId, seed.stale.uuid))
            val current = reader.read(seed.projectId, uuid)
            if (migrated) {
                val preview = assertIs<PricingReadResult.Loaded>(current).buildPreview()
                assertEquals(seed.preview.priceableLines, preview.priceableLines)
                assertEquals(seed.preview.confirmedTotalCents, preview.confirmedTotalCents)
                assertTrue(preview.canFinalizeFull)
            } else {
                assertEquals(invalid, current)
            }
        }
    }

    fun failingSchema(production: SqlSchema<QueryResult.Value<Unit>>, evidence: Evidence, reached: () -> Unit) =
        object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long,
                vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
                return production.migrate(driver, oldVersion, newVersion, *callbacks, AfterVersion(19) { migrated ->
                    assertEquals(expectedWalls(evidence, true), PrototypeDatabase(migrated).livePricingQueries
                        .selectPricingWalls(evidence.moved.draft.recordUuid).executeAsList())
                    reached()
                    error("Injected failure after the v20 wall repair")
                })
            }
        }

    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>,
        open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val evidence = open(v19Schema).use { driver ->
            move(driver, seed(driver)).also { verify(driver, it, migrated = false) }
        }
        var reached = false
        assertFails { open(failingSchema(production, evidence) { reached = true }).close() }
        assertTrue(reached)
        open(v19Schema).use { verify(it, evidence, migrated = false) }
        repeat(2) { open(production).use { verify(it, evidence, migrated = true) } }
    }

    private fun expectedWalls(evidence: Evidence, migrated: Boolean) = evidence.walls.map {
        if (migrated && it.active == 1L) it.copy(geometryVersion = evidence.moved.updatedAt) else it
    }
}
