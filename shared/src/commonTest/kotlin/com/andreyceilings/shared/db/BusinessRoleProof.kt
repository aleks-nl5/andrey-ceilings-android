package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

/** Same role/write/read/geometry/money assertions on every physical SQLCipher platform. */
object BusinessRoleProof {
    suspend fun run(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val f = LivePricingProof.seed(db)
        val repo = MeasurementElementRepository(db)
        val reader = LivePricingReader(db)
        fun read() = assertIs<PricingReadResult.Loaded>(reader.read(f.projectId, f.measurementUuid))
        fun element(uuid: String) = checkNotNull(repo.find(uuid))
        val canvas = element(f.elementUuid)
        val profile = element(f.profileUuid)
        assertEquals(BusinessRole.CANVAS, canvas.businessRole)
        assertEquals(BusinessRole.PRIMARY_PROFILE, profile.businessRole)
        assertTrue(read().buildPreview().canFinalizeFull)
        assertEquals(12000, read().buildPreview().confirmedTotalCents)
        val tables = listOf("estimates", "estimate_series", "estimate_revisions")
        fun financialRows() = tables.map { PriceNameUniquenessProof.scalar(driver, "SELECT count(*) FROM $it") }
        val beforeFinance = financialRows()
        val item = PriceRepository(db).find(f.priceId)!!
        assertEquals(canvas, repo.createCatalogSelection(f.measurementUuid, item.uuid, canvas.quantity, 900,
            uuid = canvas.uuid, approval = canvas.approval, businessRole = BusinessRole.CANVAS))
        assertFailsWith<MeasurementElementFailure.RetryConflict> {
            repo.createCatalogSelection(f.measurementUuid, item.uuid, canvas.quantity, 900,
                uuid = canvas.uuid, approval = canvas.approval, businessRole = BusinessRole.ADDITIONAL_WORK)
        }
        assertFailsWith<MeasurementElementFailure.DuplicateRole> {
            repo.createCatalogSelection(f.measurementUuid, item.uuid, canvas.quantity, 900, businessRole = BusinessRole.CANVAS)
        }
        assertFailsWith<MeasurementElementFailure.IncompatibleRole> {
            repo.edit(profile.version, "Rejected", profile.approval, profile.quantity, 900, businessRole = BusinessRole.CANVAS)
        }
        assertEquals(profile, element(profile.uuid))
        val optionalId = PriceRepository(db).createInGroup("optional.${newRecordUuid()}", "Optional ${newRecordUuid()}",
            item.groupUuid, 5.0, 901, "шт.", QuantityMode.MANUAL, PlacementKind.NONE)
        val wish = repo.createWish(f.measurementUuid, "Manual optional", 902, unit = "шт.", quantity = ElementQuantity.Exact(3000))
        val linked = repo.linkWishToCatalog(wish.version, PriceRepository(db).find(optionalId)!!.uuid, LinkQuantity.Keep, 903,
            businessRole = BusinessRole.ADDITIONAL_WORK)
        assertEquals(wish.uuid, linked.uuid)
        assertEquals(wish.quantity, linked.quantity)
        assertEquals(1500, read().buildPreview().potentialTotalCents)
        assertEquals(12000, read().buildPreview().confirmedTotalCents)
        val beforeRole = read().source.signature
        val changed = repo.edit(canvas.version, canvas.note, canvas.approval, canvas.quantity, 904,
            businessRole = BusinessRole.ADDITIONAL_WORK)
        assertNotEquals(beforeRole, read().source.signature)
        assertFalse(read().buildPreview().canFinalizeFull)
        assertTrue(read().buildPreview().mandatoryProblems.any { it.code == PricingProblemCode.MISSING_MANDATORY_CANVAS })
        assertFailsWith<MeasurementElementFailure.StaleVersion> {
            repo.edit(canvas.version, canvas.note, canvas.approval, canvas.quantity, 905, businessRole = BusinessRole.CANVAS)
        }
        val restored = repo.edit(changed.version, changed.note, changed.approval, changed.quantity, 906, businessRole = BusinessRole.CANVAS)
        val extra = repo.createCatalogSelection(f.measurementUuid, item.uuid, canvas.quantity, 907, businessRole = BusinessRole.ADDITIONAL_WORK)
        assertFails { driver.execute(null, "UPDATE measurement_elements SET businessRole='CANVAS' WHERE uuid='${extra.uuid}'", 0) }
        assertFails { driver.execute(null, "UPDATE measurement_elements SET businessRole='UNKNOWN' WHERE uuid='${extra.uuid}'", 0) }
        assertEquals(extra, element(extra.uuid))
        val unlinkedWish = repo.createWish(f.measurementUuid, "Cannot classify Wish", 907)
        assertFailsWith<MeasurementElementFailure.IncompatibleRole> {
            repo.edit(unlinkedWish.version, "Rejected", unlinkedWish.approval, unlinkedWish.quantity, 908, businessRole = BusinessRole.ADDITIONAL_WORK)
        }
        repo.delete(extra.version, 909)
        val editStore = MeasurementEditStore(db) { 910 }
        val row = MeasurementRepository(db).findByUuid(f.measurementUuid)!!
        val doc = editStore.load(f.projectId, row.id)
        editStore.update(doc, doc.draft.reduce(MeasurementCreateIntent.Dimension("length", "5")), now = 910)
        assertEquals(15000, read().buildPreview().confirmedTotalCents)
        assertEquals(linked.quantity, element(linked.uuid).quantity)
        assertEquals(1500, read().buildPreview().potentialTotalCents)
        // Price-only changes reprice this measurement without creating a financial document.
        driver.execute(null, "UPDATE price_items SET price=20, updatedAt=updatedAt+1 WHERE uuid='${item.uuid}'", 0)
        assertEquals(30000, read().buildPreview().confirmedTotalCents)
        driver.execute(null, "UPDATE price_items SET deletedAt=updatedAt+1, updatedAt=updatedAt+1 WHERE uuid='${item.uuid}'", 0)
        assertTrue(read().buildPreview().unresolvedItems.any { it.elementUuid == restored.uuid && PricingProblemCode.CATALOG_MISSING in it.reasons })
        assertFalse(read().buildPreview().canFinalizeFull)
        assertEquals(beforeFinance, financialRows())
        // Tombstoning frees the main slot; it does not erase its historical classification.
        val deleted = repo.delete(element(restored.uuid).version, 920)
        assertEquals(BusinessRole.CANVAS, deleted.businessRole)
        reader.closeReads()
        assertIs<PricingReadResult.Unavailable>(reader.read(f.projectId, f.measurementUuid))
    }
}

/** Frozen v14 DB with no role column, real migration rollback and close/reopen persistence. */
object BusinessRoleMigrationProof {
    val v14Schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 14L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            CeilingReceiptMigrationProof.v13Schema.create(driver)
            PrototypeDatabase.Schema.migrate(driver, 13, 14)
            PriceCatalogSchema.verifyStructure(driver, 14)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = error("Frozen v14 fixture")
    }
    suspend fun lifecycle(production: SqlSchema<QueryResult.Value<Unit>>, open: suspend (SqlSchema<QueryResult.Value<Unit>>) -> SqlDriver) {
        val (fixture, before, structure) = open(v14Schema).use { d ->
            val fixture = LivePricingProof.seed(historicalElementDatabase(d))
            CeilingReceiptMigrationProof.populate(d)
            Triple(fixture, CeilingReceiptMigrationProof.snapshot(d), PriceNameMigrationProof.schema(d))
        }
        val interrupted = object : SqlSchema<QueryResult.Value<Unit>> by production {
            override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
                val fail = object : SqlDriver by driver {
                    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                        val result = driver.execute(identifier, sql, parameters, binders)
                        if (sql.startsWith("CREATE UNIQUE INDEX index_measurement_elements_canvas")) error("Interrupted v15 migration")
                        return result
                    }
                }
                return production.migrate(fail, oldVersion, newVersion, *callbacks)
            }
        }
        assertFails { open(interrupted).close() }
        open(v14Schema).use { d ->
            assertEquals(before, CeilingReceiptMigrationProof.snapshot(d))
            assertEquals(structure, PriceNameMigrationProof.schema(d))
        }
        open(production).use { d ->
            PriceCatalogSchema.verifyStructure(d)
            assertEquals(before, CeilingReceiptMigrationProof.snapshot(d))
            val db = PrototypeDatabase(d)
            val reader = LivePricingReader(db)
            val legacy = assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid))
            assertTrue(legacy.source.elements.all { it.businessRole == null })
            assertTrue(legacy.buildPreview().fatalProblems.any { it.code == PricingProblemCode.ROLE_UNDETERMINED })
            assertFalse(legacy.buildPreview().canFinalizeFull)
            val repo = MeasurementElementRepository(db)
            for ((uuid, role) in listOf(fixture.elementUuid to BusinessRole.CANVAS, fixture.profileUuid to BusinessRole.PRIMARY_PROFILE)) {
                val element = repo.find(uuid)!!
                repo.edit(element.version, element.note, element.approval, element.quantity, 1000, businessRole = role)
            }
            assertTrue(assertIs<PricingReadResult.Loaded>(reader.read(fixture.projectId, fixture.measurementUuid)).buildPreview().canFinalizeFull)
            reader.closeReads()
        }
        open(production).use { d ->
            val db = PrototypeDatabase(d)
            assertEquals(BusinessRole.CANVAS, MeasurementElementRepository(db).find(fixture.elementUuid)!!.businessRole)
            assertEquals(BusinessRole.PRIMARY_PROFILE, MeasurementElementRepository(db).find(fixture.profileUuid)!!.businessRole)
            BusinessRoleProof.run(d)
        }
    }
}
