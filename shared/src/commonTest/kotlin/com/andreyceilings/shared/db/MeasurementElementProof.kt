package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Same production API/SQL contracts on JDBC, Android WAL and iOS SQLCipher. */
object MeasurementElementProof {
    suspend fun run(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val measurements = MeasurementRepository(db)
        val projects = ProjectRepository(db)
        val project = projects.createReturningId(null, "Element fixture", createdAt = 100)
        val otherProject = projects.createReturningId(null, "Other fixture", createdAt = 100)
        val id = measurements.createReturningId(project, "First", drawingJson = "{\"precision\":0.005}", createdAt = 101)
        val siblingId = measurements.createReturningId(project, "Second", createdAt = 101)
        val otherId = measurements.createReturningId(otherProject, "Other", createdAt = 101)
        val parent = checkNotNull(measurements.find(id)).uuid
        val sibling = checkNotNull(measurements.find(siblingId)).uuid
        val other = checkNotNull(measurements.find(otherId)).uuid
        val originalGeometry = measurements.find(id)
        val repo = MeasurementElementRepository(db)
        assertTrue(repo.observeForMeasurement(parent).firstValue().isEmpty())
        var wish = repo.createWish(parent, "  Карниз уточнити  ", 102, uuid = "wish", note = "Review")
        assertEquals("Карниз уточнити", wish.label)
        assertNull(wish.unit)
        assertEquals(ElementQuantity.Unknown, wish.quantity)
        assertEquals(ElementPlacement.UNPLACED, wish.placement)
        assertEquals(0L, wish.placedQuantityMilli)
        assertEquals(wish, repo.createWish(parent, "Карниз уточнити", 999, uuid = "wish", note = "Review"))
        assertFailsWith<MeasurementElementFailure.RetryConflict> { repo.createWish(parent, "Different", 103, uuid = "wish") }
        assertFailsWith<MeasurementElementFailure.RetryConflict> { repo.createWish(sibling, "Карниз уточнити", 103, uuid = "wish", note = "Review") }
        val exact = repo.createWish(parent, "Six", 102, uuid = "six", unit = "шт.", quantity = ElementQuantity.Exact(6000))
        val range = repo.createWish(parent, "Four to six", 102, uuid = "range", unit = "шт.", quantity = ElementQuantity.Range(4000, 6000))
        assertNotEquals(exact.quantity, range.quantity)
        repo.createWish(sibling, "Sibling", 102, uuid = "sibling")
        repo.createWish(other, "Other", 102, uuid = "other")
        assertEquals(listOf("range", "six", "wish"), repo.observeForMeasurement(parent).firstValue().map { it.uuid })
        assertEquals(listOf("sibling"), repo.observeForMeasurement(sibling).firstValue().map { it.uuid })
        assertEquals(listOf("other"), repo.observeForMeasurement(other).firstValue().map { it.uuid })
        wish = repo.edit(wish.version, wish.note, ElementApproval.APPROVED, ElementQuantity.Unknown, 100)
        assertEquals(103L, wish.updatedAt)
        assertEquals(ElementPlacement.UNPLACED, wish.placement)
        assertFailsWith<MeasurementElementFailure.StaleVersion> { repo.edit(exact.version.copy(expectedUpdatedAt = 0), "", exact.approval, exact.quantity, 104) }
        assertFailsWith<MeasurementElementFailure.StaleVersion> { repo.delete(exact.version.copy(measurementUuid = sibling), 104) }
        assertFailsWith<MeasurementElementFailure.InvalidQuantity> { repo.createWish(parent, "Fraction", 104, unit = "шт.", quantity = ElementQuantity.Exact(2500)) }
        assertFailsWith<MeasurementElementFailure.ParentUnavailable> { repo.createWish("missing", "Wish", 104) }
        val item = PriceRepository(db).all().single { it.code == "light.spot" }
        val selected = repo.createCatalogSelection(parent, item.uuid, ElementQuantity.Exact(6000), 104, uuid = "selected", businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        val duplicateItem = repo.createCatalogSelection(parent, item.uuid, ElementQuantity.Range(4000, 6000), 104, uuid = "selected-again", businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        assertNotEquals(selected.uuid, duplicateItem.uuid)
        val source = assertIs<ElementSource.CatalogSelection>(selected.source)
        assertEquals(item.uuid, source.priceItemUuid)
        assertEquals(item.title, selected.label)
        assertEquals(item.code, source.code)
        assertEquals(item.unit, selected.unit)
        assertEquals(item.quantityMode, source.quantityMode.value)
        assertEquals(item.placementKind, source.placementKind.value)
        wishUnitEdits(db, parent, selected)
        val kept = repo.linkWishToCatalog(range.version, item.uuid, LinkQuantity.Keep, 105, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        assertEquals(range.uuid, kept.uuid)
        assertEquals(range.quantity, kept.quantity)
        assertEquals(range.note, kept.note)
        val prices = PriceRepository(db)
        prices.updateConfigured(item, item.groupUuid, "Renamed spot", item.unit, QuantityMode.MANUAL,
            PlacementKind.NONE, 999.0, 105)
        assertEquals(selected, repo.find(selected.uuid), "Catalog edits must not rewrite selection snapshots")
        assertFailsWith<MeasurementElementFailure.InvalidInput> { repo.edit(selected.version, "", selected.approval, selected.quantity, 105, wishLabel = "Override") }
        val length = prices.all().first { it.quantityMode == "entered_length" }
        val lengthWish = repo.createWish(parent, "Six pieces", 105, uuid = "to-link", note = "Keep note",
            approval = ElementApproval.APPROVED, unit = "шт.", quantity = ElementQuantity.Exact(6000))
        assertFailsWith<MeasurementElementFailure.IncompatibleQuantity> { repo.linkWishToCatalog(lengthWish.version, length.uuid, LinkQuantity.Keep, 106, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK) }
        assertEquals(lengthWish, repo.find(lengthWish.uuid))
        val linked = repo.linkWishToCatalog(lengthWish.version, length.uuid, LinkQuantity.Replace(ElementQuantity.Exact(2500)), 106, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        assertEquals(lengthWish.uuid, linked.uuid)
        assertEquals("Keep note", linked.note)
        assertEquals(ElementApproval.APPROVED, linked.approval)
        assertEquals(ElementQuantity.Exact(2500), linked.quantity)
        assertEquals("м.п.", linked.unit)
        for ((mode, quantity) in listOf("ceiling_area" to ElementQuantity.CeilingArea, "ceiling_perimeter" to ElementQuantity.CeilingPerimeter)) {
            val derived = prices.all().first { it.quantityMode == mode }
            assertEquals(quantity, repo.createCatalogSelection(parent, derived.uuid, quantity, 106, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK).quantity)
            assertFailsWith<MeasurementElementFailure.InvalidQuantity> { repo.createCatalogSelection(parent, derived.uuid, ElementQuantity.Exact(1000), 106, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK) }
        }
        prices.delete(item.id)
        val unavailable = checkNotNull(repo.find(selected.uuid))
        assertEquals(CatalogAvailability.DELETED_OR_MISSING, assertIs<ElementSource.CatalogSelection>(unavailable.source).availability)
        assertEquals(selected.label, unavailable.label)
        assertEquals(unavailable, repo.createCatalogSelection(parent, item.uuid, selected.quantity, 107, uuid = selected.uuid, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK))
        assertFailsWith<MeasurementElementFailure.CatalogUnavailable> { repo.createCatalogSelection(parent, item.uuid, selected.quantity, 107, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK) }
        val reusedId = prices.createInGroup(item.code, item.title, item.groupUuid, 1.0, 107, item.unit, QuantityMode.MANUAL)
        assertNotEquals(item.uuid, prices.find(reusedId)?.uuid)
        assertEquals(item.uuid, assertIs<ElementSource.CatalogSelection>(repo.find(selected.uuid)?.source).priceItemUuid)
        val group = checkNotNull(PriceGroupRepository(db).find(item.groupUuid))
        val groupRenamed = PriceGroupRepository(db)
        groupRenamed.rename(group.uuid, group.updatedAt, "Renamed lighting", 108)
        assertEquals(selected.label, repo.find(selected.uuid)?.label)
        val blockedItem = checkNotNull(prices.find(reusedId))
        driver.execute(null, "UPDATE price_groups SET deletedAt=109 WHERE uuid='${blockedItem.groupUuid}'", 0)
        assertFailsWith<MeasurementElementFailure.CatalogUnavailable> {
            repo.createCatalogSelection(parent, blockedItem.uuid, ElementQuantity.Unknown, 109, businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
        }
        driver.execute(null, "UPDATE price_groups SET deletedAt=NULL WHERE uuid='${blockedItem.groupUuid}'", 0)
        val tombstone = repo.delete(exact.version, 108)
        assertNotNull(tombstone.deletedAt)
        assertFailsWith<MeasurementElementFailure.StaleVersion> { repo.delete(exact.version, 109) }
        assertFailsWith<MeasurementElementFailure.RetryConflict> { repo.createWish(parent, "Six", 109, uuid = "six", unit = "шт.", quantity = exact.quantity) }
        assertEquals(originalGeometry, measurements.find(id), "Element operations never mutate geometry/precision/version")
        val geometry = checkNotNull(measurements.find(id))
        assertNotNull(measurements.updateIfUnchanged(id, project, geometry.updatedAt, "Geometry edit", geometry.shape,
            geometry.lengthMeters, geometry.widthMeters, geometry.areaSquareMeters, geometry.perimeterMeters,
            geometry.drawingJson, geometry.notes, 110))
        repo.edit(wish.version, "Independent", wish.approval, wish.quantity, 110)
        projects.delete(otherProject)
        assertTrue(repo.observeForMeasurement(other).firstValue().isEmpty())
        assertFailsWith<MeasurementElementFailure.ParentUnavailable> { repo.createWish(other, "Blocked", 111) }
        val hidden = checkNotNull(repo.find("other"))
        assertFailsWith<MeasurementElementFailure.ParentUnavailable> { repo.delete(hidden.version, 111) }
        assertFailsWith<MeasurementElementFailure.ParentUnavailable> { repo.edit(hidden.version, "Blocked", hidden.approval, hidden.quantity, 111) }
        measurements.delete(id)
        assertTrue(repo.observeForMeasurement(parent).firstValue().isEmpty())
        assertTrue(repo.all().filter { it.measurementUuid == parent }.all { it.deletedAt != null })
        assertFailsWith<MeasurementElementFailure.ParentUnavailable> { repo.createWish(parent, "Blocked", 111) }
        assertEquals(1, repo.observeForMeasurement(sibling).firstValue().size)
        assertTrue(repo.tombstones().any { it.uuid == "six" })
        liveFlow(db, sibling, project)
    }

    private fun wishUnitEdits(db: PrototypeDatabase, parent: String, selected: MeasurementElement) {
        val repo = MeasurementElementRepository(db)
        var wish = repo.createWish(parent, "Unit edit", 104, uuid = "unit-edit", note = "Preserve note",
            unit = "м.п.", quantity = ElementQuantity.Exact(2500))
        val rangeWish = repo.createWish(parent, "Unit range edit", 104, uuid = "unit-edit-range",
            unit = "м.п.", quantity = ElementQuantity.Range(2000, 3000))
        for (original in listOf(wish, rangeWish)) {
            assertFailsWith<MeasurementElementFailure.InvalidQuantity> {
                repo.edit(original.version, "Must roll back", ElementApproval.APPROVED, original.quantity, 105,
                    wishUnitChange = WishUnitChange.Clear, wishLabel = "Must roll back")
            }
            assertEquals(original, repo.find(original.uuid), "Rejected clearing must preserve every field and CAS token")
        }
        wish = repo.edit(wish.version, wish.note, wish.approval, wish.quantity, 105,
            wishUnitChange = WishUnitChange.Set(" custom "))
        assertEquals("custom", wish.unit)
        wish = repo.edit(wish.version, wish.note, wish.approval, wish.quantity, 106,
            wishUnitChange = WishUnitChange.Keep)
        assertEquals("custom", wish.unit)
        // Existing calls retain their original semantics, including explicit null and strings.
        wish = repo.edit(wish.version, wish.note, wish.approval, wish.quantity, 107)
        assertEquals("custom", wish.unit)
        wish = repo.edit(wish.version, wish.note, wish.approval, wish.quantity, 108, wishUnit = null)
        assertEquals("custom", wish.unit)
        wish = repo.edit(wish.version, wish.note, wish.approval, wish.quantity, 109, wishUnit = " м.п. ")
        assertEquals("м.п.", wish.unit)
        val beforeClear = wish
        val cleared = repo.edit(wish.version, wish.note, wish.approval, ElementQuantity.Unknown, 109,
            wishUnitChange = WishUnitChange.Clear)
        assertEquals(beforeClear.copy(unit = null, quantity = ElementQuantity.Unknown, updatedAt = 110), cleared)
        val stored = checkNotNull(db.measurementElementsQueries.selectByUuid(wish.uuid).executeAsOneOrNull())
        assertNull(stored.unit, "Clear must persist SQL NULL")
        assertEquals("unknown", stored.quantityKind)
        assertNull(stored.quantityMilli)
        assertNull(stored.quantityMinMilli)
        assertNull(stored.quantityMaxMilli)
        assertFailsWith<MeasurementElementFailure.StaleVersion> {
            repo.edit(beforeClear.version, "Stale", beforeClear.approval, ElementQuantity.Unknown, 111,
                wishUnitChange = WishUnitChange.Set("шт."))
        }
        assertEquals(cleared, repo.find(cleared.uuid))
        for (change in listOf(WishUnitChange.Clear, WishUnitChange.Set("м.п."))) {
            assertFailsWith<MeasurementElementFailure.InvalidInput> {
                repo.edit(selected.version, "Must roll back", selected.approval, ElementQuantity.Unknown, 111,
                    wishUnitChange = change)
            }
            assertEquals(selected, repo.find(selected.uuid), "Catalog selection snapshots must remain immutable")
        }
        assertFailsWith<MeasurementElementFailure.InvalidInput> {
            repo.edit(selected.version, selected.note, selected.approval, selected.quantity, 111, wishUnit = "м.п.")
        }
        assertEquals(selected, repo.find(selected.uuid))
    }

    private suspend fun liveFlow(db: PrototypeDatabase, parent: String, project: Long) = coroutineScope {
        val repo = MeasurementElementRepository(db)
        val events = Channel<List<MeasurementElement>>(Channel.UNLIMITED)
        val job = launch { repo.observeForMeasurement(parent).collect { events.send(it) } }
        suspend fun await(predicate: (List<MeasurementElement>) -> Boolean): List<MeasurementElement> = withTimeout(10000) {
            var rows = events.receive()
            while (!predicate(rows)) rows = events.receive()
            rows
        }
        try {
            await { it.size == 1 }
            val row = repo.createWish(parent, "Flow", 120, uuid = "flow")
            await { it.any { e -> e.uuid == row.uuid } }
            repo.edit(row.version, "Changed", row.approval, row.quantity, 121)
            await { it.any { e -> e.note == "Changed" } }
            val catalogItem = PriceRepository(db).all().first { it.code == "light.chandelier" }
            val selected = repo.createCatalogSelection(parent, catalogItem.uuid, ElementQuantity.Unknown, 122, uuid = "flow-catalog", businessRole = com.andreyceilings.shared.livepricing.BusinessRole.ADDITIONAL_WORK)
            await { it.any { e -> e.uuid == selected.uuid } }
            PriceRepository(db).delete(catalogItem.id)
            await { rows -> rows.any { e -> e.uuid == selected.uuid &&
                (e.source as? ElementSource.CatalogSelection)?.availability == CatalogAvailability.DELETED_OR_MISSING } }
            ProjectRepository(db).delete(project)
            assertTrue(await { it.isEmpty() }.isEmpty())
        } finally { job.cancelAndJoin(); events.close() }
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

    fun atomicParentDelete(driver: SqlDriver) {
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "Rollback", createdAt = 1)
        val id = MeasurementRepository(db).createReturningId(project, "Rollback", createdAt = 2)
        val parent = checkNotNull(MeasurementRepository(db).find(id)).uuid
        val row = MeasurementElementRepository(db).createWish(parent, "Rollback", 3, uuid = "rollback")
        val failing = object : SqlDriver by driver {
            override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
                if (sql.trimStart().startsWith("UPDATE room_measurements")) error("Injected parent deletion failure")
                return driver.execute(identifier, sql, parameters, binders)
            }
        }
        assertFails { MeasurementRepository(PrototypeDatabase(failing)).delete(id) }
        assertEquals(row, MeasurementElementRepository(db).find(row.uuid))
        assertNotNull(MeasurementRepository(db).find(id))
    }
}
