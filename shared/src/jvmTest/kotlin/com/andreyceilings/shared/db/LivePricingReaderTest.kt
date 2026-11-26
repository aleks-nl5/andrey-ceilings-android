package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.prices.*
import com.andreyceilings.shared.measurement.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*
import kotlinx.coroutines.*

class LivePricingReaderTest {
    @Test fun currentDraftPreviewStaysReadOnlyAndMatchesCommittedPricing() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            val database = PrototypeDatabase(driver)
            val fixture = LivePricingProof.seed(database)
            val reader = LivePricingReader(database)
            val measurement = assertNotNull(MeasurementRepository(database).findByUuid(fixture.measurementUuid))
            val loaded = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
            val preview = loaded.buildPreview()
            val model = MeasurementEditModel(null, MeasurementEditStore(database) { 200 }.load(fixture.projectId, measurement.id))
            fun changes() = driver.executeQuery(null, "SELECT total_changes()", { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
            }, 0).value
            val beforeChanges = changes()
            val basis = DraftPricingBasis.Saved(loaded)
            val draft = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(model, model.state.value, basis))
            assertEquals(loaded.source.elements.toSet(), draft.projection.elements.toSet())
            assertEquals(loaded.source.geometry, draft.projection.geometry)
            assertEquals(preview.confirmedTotalCents, draft.preview.confirmedTotalCents)
            assertEquals(beforeChanges, changes())
            val oldState = model.state.value
            model.accept(MeasurementCreateIntent.Dimension("length", "5"))
            assertEquals(DraftPricingResult.Unavailable(PricingProblemCode.STALE_SOURCE), projectDraftPricing(model, oldState, basis))
            val changedDraft = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(model, model.state.value, basis))
            assertEquals(15.0, changedDraft.projection.geometry.areaSquareMeters)
            assertEquals(15000L, changedDraft.preview.confirmedTotalCents)
            assertNotEquals(loaded.source.signature, changedDraft.projection.signature)
            assertEquals(12000, preview.confirmedTotalCents)
            model.accept(MeasurementCreateIntent.Dimension("length", ""))
            assertEquals(DraftPricingResult.Unavailable(PricingProblemCode.INVALID_GEOMETRY),
                projectDraftPricing(model, model.state.value, basis))
            assertEquals(beforeChanges, changes())
            val createStore = MeasurementEditStore(database) { 205 }
            val creation = MeasurementEditModel(createStore, MeasurementEditDocument(0L, 0L,
                MeasurementCreateDraft(fixture.projectId, MeasurementPrecision.ONE_MILLIMETER, "Pre-save",
                    outline = MeasurementOutlineDraft.Template("rectangle", mapOf("length" to "4", "width" to "3")))))
            val picker = createStore.placementCatalog()
            val canvas = picker.single { it.uuid == (loaded.source.elements.single { e -> e.businessRole == BusinessRole.CANVAS }
                .source as ElementSource.CatalogSelection).priceItemUuid }
            val profile = picker.single { it.uuid == (loaded.source.elements.single { e -> e.businessRole == BusinessRole.PRIMARY_PROFILE }
                .source as ElementSource.CatalogSelection).priceItemUuid }
            assertTrue(creation.chooseMaterial(BusinessRole.CANVAS, canvas))
            assertTrue(creation.chooseMaterial(BusinessRole.PRIMARY_PROFILE, profile))
            val additional = picker.firstOrNull { it.placementKind == PlacementKind.POINT && it.mode == QuantityMode.MANUAL && it.unit == "шт." }
            additional?.let { creation.addCatalogWork(it, ElementQuantity.Exact(6000), place = false) }
            val createBasis = DraftPricingBasis.Created(loaded.source.projectUuid, loaded.catalog)
            val beforeSave = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(creation, creation.state.value, createBasis))
            assertEquals(12000L, beforeSave.preview.confirmedTotalCents)
            additional?.let { work ->
                val pointRow = beforeSave.preview.priceableLines.single { it.catalogItemUuid == work.uuid }
                assertEquals(6000L, pointRow.effectiveBillableQuantityMilli)
                assertTrue(PricingProblemCode.PLACEMENT_UNPLACED in pointRow.reviewFlags)
                creation.undo()
                val undone = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(creation, creation.state.value, createBasis))
                assertTrue(undone.preview.priceableLines.none { it.catalogItemUuid == work.uuid })
                creation.redo()
                val redone = assertIs<DraftPricingResult.Loaded>(projectDraftPricing(creation, creation.state.value, createBasis))
                assertEquals(beforeSave.preview.priceableLines, redone.preview.priceableLines)
            }
            assertEquals(beforeChanges, changes())
            assertTrue(creation.saveInWorkspace(forQuote = true))
            val committed = creation.state.value
            assertFalse(committed.creating)
            assertFalse(committed.dirty)
            val newCanonical = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, committed.draft.recordUuid))
            assertEquals(beforeSave.preview.priceableLines, newCanonical.buildPreview().priceableLines)
            assertEquals(beforeSave.preview.confirmedTotalCents, newCanonical.buildPreview().confirmedTotalCents)
        }
    }

    // Archived pre-v21 upgrade proof; see CI historical DB policy.
    @kotlin.test.Ignore
    @Test fun deployedV19FileUpgradeRepairsOnlyTheProvenWallSet() =
        physicalWallRepair { file -> JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}") }

    // Archived pre-v21 upgrade proof; see CI historical DB policy.
    @kotlin.test.Ignore
    @Test fun deployedV19WindowsCipherUpgradeRepairsOnlyTheProvenWallSet() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 46 }
        try { physicalWallRepair { file -> SqlCipherWindowsDriverFactory.open(file, key) } }
        finally { key.fill(0) }
    }

    private fun physicalWallRepair(open: (java.io.File) -> SqlDriver): Unit = runBlocking {
        val dir = Files.createTempDirectory("wall-migration-").toFile()
        val file = dir.resolve("v19.db")
        try {
            WallVersionMigrationProof.lifecycle(PriceCatalogSchema) { schema ->
                val driver = open(file)
                try {
                    if (schema === PriceCatalogSchema) openDesktopPriceCatalog(driver, true)
                    else PrototypeDatabase(driver).transaction {
                        val version = PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version")
                        if (version == 0L) schema.create(driver)
                        else if (version < schema.version) schema.migrate(driver, version, schema.version)
                        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                    }
                    driver
                } catch (failure: Throwable) {
                    driver.close()
                    throw failure
                }
            }
        } finally { dir.deleteRecursively() }
    }

    // Archived pre-v21 upgrade proof; see CI historical DB policy.
    @kotlin.test.Ignore
    @Test fun wallVersionMigrationRejectsAnUnknownLegacyGuardBeforeRepair() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            WallVersionMigrationProof.v19Schema.create(driver)
            PriceCatalogSchema.verifyStructure(driver, 19)
            driver.execute(null, "DROP TRIGGER ceiling_wall_update_guard", 0)
            driver.execute(null, """
                CREATE TRIGGER ceiling_wall_update_guard BEFORE UPDATE ON ceiling_walls
                BEGIN SELECT 1; END
            """.trimIndent(), 0)
            val before = PriceNameMigrationProof.schema(driver)
            for (legacyVersion in 16L..19L) {
                val failure = assertFailsWith<SchemaFingerprintMismatch> {
                    PriceCatalogSchema.migrate(driver, legacyVersion, PriceCatalogSchema.version)
                }
                assertEquals("ceiling_wall_update_guard", failure.objectId)
                assertEquals(before, PriceNameMigrationProof.schema(driver))
            }
        }
    }

    // Archived pre-v21 upgrade proof; see CI historical DB policy.
    @kotlin.test.Ignore
    @Test fun version19MigrationRepairsOnlyWallsBackedByACompletedEdit() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            // Use the historical DDL, not a partial downgrade of today's schema.
            WallVersionMigrationProof.v19Schema.create(driver)
            StarterPriceCatalog.initialize(PrototypeDatabase(driver))
            PriceCatalogSchema.verifyStructure(driver, 19)
            val db = PrototypeDatabase(driver)
            val fixture = LivePricingProof.seed(db)
            val stale = LivePricingProof.seed(db)
            val measurements = MeasurementRepository(db)
            val row = assertNotNull(measurements.findByUuid(fixture.measurementUuid))
            val staleRow = assertNotNull(measurements.findByUuid(stale.measurementUuid))
            val reader = LivePricingReader(db)
            assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
            val store = MeasurementEditStore(db) { 200 }
            val initial = store.load(fixture.projectId, row.id)
            val first = store.update(initial, initial.draft.copy(notes = "First edit"), now = 201)
            val second = store.update(first, first.draft.copy(notes = "Second edit"), now = 202)
            assertEquals(row.drawingJson, second.sourceDrawingJson)
            val before = db.livePricingQueries.selectPricingWalls(fixture.measurementUuid).executeAsList()
            assertTrue(before.all { it.geometryVersion == row.updatedAt })
            val receipts = db.livePricingQueries.selectPricingReceipts(fixture.measurementUuid).executeAsList()
            val invalidSource = PricingReadResult.Unavailable(PricingProblemCode.INVALID_SOURCE)
            assertEquals(invalidSource, reader.read(fixture.projectId, fixture.measurementUuid))
            driver.execute(null, "UPDATE room_measurements SET updatedAt = updatedAt + 1 WHERE id = ?", 1) {
                bindLong(0, staleRow.id)
            }
            assertEquals(invalidSource, reader.read(stale.projectId, stale.measurementUuid))

            PriceCatalogSchema.migrate(driver, 19, PriceCatalogSchema.version)
            assertEquals(before.map { it.copy(geometryVersion = second.updatedAt) },
                db.livePricingQueries.selectPricingWalls(fixture.measurementUuid).executeAsList())
            assertEquals(receipts, db.livePricingQueries.selectPricingReceipts(fixture.measurementUuid).executeAsList())
            assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
            assertEquals(invalidSource, reader.read(stale.projectId, stale.measurementUuid))
            assertFails {
                driver.execute(null, "UPDATE ceiling_walls SET geometryVersion = geometryVersion + 1", 0)
            }
            val third = store.update(second, second.draft.copy(notes = "After migration"), now = 203)
            assertEquals(203L, third.updatedAt)
            val after = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
            driver.execute(null, """
                CREATE TRIGGER fail_pricing_test_receipt BEFORE INSERT ON ceiling_operation_results
                BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END
            """.trimIndent(), 0)
            assertFailsWith<MeasurementEditRejected> {
                store.update(third, third.draft.copy(notes = "Rolled back"), now = 204)
            }
            assertEquals(third, store.load(fixture.projectId, row.id))
            assertEquals(after, reader.read(fixture.projectId, fixture.measurementUuid))
        }
    }

    @Test fun walSnapshotNeverMixesChildAndCatalogFromDifferentCommits() {
        coherentRead { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun windowsSqlCipherCoherentRead() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 37 }
        try { coherentRead { SqlCipherWindowsDriverFactory.open(it, key) } } finally { key.fill(0) }
    }

    @Test fun closeDrainsPausedTransactionsAndRejectsNewReads() {
        closingRead { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun windowsCipherCloseDrainsPausedTransactions() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 38 }
        try { closingRead { SqlCipherWindowsDriverFactory.open(it, key) } } finally { key.fill(0) }
    }

    private fun closingRead(open: (java.io.File) -> SqlDriver) = runBlocking {
        for (failRead in listOf(false, true)) for (cancelClose in listOf(false, true)) for (verify in listOf(false, true)) {
            val directory = Files.createTempDirectory("p4a-close-").toFile()
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                open(directory.resolve("close.db")).use { driver ->
                    PriceCatalogSchema.create(driver)
                    val fixture = LivePricingProof.seed(PrototypeDatabase(driver))
                    var queries = 0
                    val paused = object : SqlDriver by driver {
                        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                            binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                            queries++
                            val value = driver.executeQuery(identifier, sql, mapper, parameters, binders)
                            if (sql.contains("FROM projects")) {
                                entered.countDown()
                                check(release.await(20, TimeUnit.SECONDS))
                                if (failRead) error("Injected read failure after close began")
                            }
                            return value
                        }
                    }
                    val reader = LivePricingReader(PrototypeDatabase(paused))
                    val inFlight = executor.submit<Any> {
                        if (verify) reader.verifyFrozenRevision(fixture.projectId, "missing", "missing")
                        else reader.read(fixture.projectId, fixture.measurementUuid)
                    }
                    assertTrue(entered.await(20, TimeUnit.SECONDS))
                    val close = launch(start = CoroutineStart.UNDISPATCHED) { reader.closeReads() }
                    assertFalse(close.isCompleted) // Admission closed, physical read still owns the DB.
                    if (cancelClose) close.cancel()
                    val before = queries
                    assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), reader.read(fixture.projectId, fixture.measurementUuid))
                    assertEquals(PricingProblemCode.DATABASE_UNAVAILABLE, reader.verifyFrozenRevision(fixture.projectId, "s", "r"))
                    assertEquals(before, queries)
                    assertFalse(close.isCompleted)
                    release.countDown()
                    close.join() // Owner can now replace/close the driver safely, even after cancellation.
                    assertEquals(if (verify) PricingProblemCode.DATABASE_UNAVAILABLE else
                        PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), inFlight.get(20, TimeUnit.SECONDS))
                    assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), reader.read(fixture.projectId, fixture.measurementUuid))
                }
            } finally { release.countDown(); executor.shutdownNow(); directory.deleteRecursively() }
        }
    }

    private fun coherentRead(open: (java.io.File) -> SqlDriver) {
        val directory = Files.createTempDirectory("p4a-coherent-").toFile()
        val file = directory.resolve("pricing.db")
        val executor = Executors.newSingleThreadExecutor()
        try {
            open(file).use { readDriver ->
                PriceCatalogSchema.create(readDriver)
                readDriver.executeQuery(null, "PRAGMA journal_mode=WAL", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value
                val db = PrototypeDatabase(readDriver)
                val fixture = LivePricingProof.seed(db)
                val before = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
                val reachedSnapshot = CountDownLatch(1)
                val writerFinished = CountDownLatch(1)
                var armed = true
                val pausedDriver = object : SqlDriver by readDriver {
                    override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                        val result = readDriver.executeQuery(identifier, sql, mapper, parameters, binders)
                        if (armed && sql.contains("FROM projects")) {
                            armed = false
                            reachedSnapshot.countDown()
                            check(writerFinished.await(20, TimeUnit.SECONDS))
                        }
                        return result
                    }
                }
                val pending = executor.submit<PricingReadResult> { LivePricingReader(PrototypeDatabase(pausedDriver)).read(fixture.projectId, fixture.measurementUuid) }
                assertTrue(reachedSnapshot.await(20, TimeUnit.SECONDS))
                try {
                    open(file).use { writer ->
                        val writable = PrototypeDatabase(writer)
                        writable.transaction {
                            val elements = MeasurementElementRepository(writable)
                            val e = elements.find(fixture.elementUuid)!!
                            elements.edit(e.version, "simultaneous", e.approval, e.quantity, 200, businessRole = BusinessRole.ADDITIONAL_WORK)
                            val prices = PriceRepository(writable)
                            prices.updateSalePrice(prices.find(fixture.priceId)!!, 20.0, 200)
                        }
                    }
                } finally { writerFinished.countDown() }
                val during = assertIs<PricingReadResult.Loaded>(pending.get(20, TimeUnit.SECONDS))
                assertEquals(before.source.signature, during.source.signature)
                assertEquals(before.catalog.signature, during.catalog.signature)
                assertEquals(BusinessRole.CANVAS, during.source.elements.first { it.uuid == fixture.elementUuid }.businessRole)
                val after = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
                assertNotEquals(before.source.signature, after.source.signature)
                assertNotEquals(before.catalog.signature, after.catalog.signature)
                assertEquals(BusinessRole.ADDITIONAL_WORK, after.source.elements.first { it.uuid == fixture.elementUuid }.businessRole)
                assertFalse(after.buildPreview().canFinalizeFull)
                val closing = LivePricingReader(db)
                runBlocking { closing.closeReads() } // JDBC may reopen a connection: session lifecycle must be explicit.
                readDriver.close()
                assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), closing.read(fixture.projectId, fixture.measurementUuid))
            }
        } finally { executor.shutdownNow(); directory.deleteRecursively() }
    }
}
