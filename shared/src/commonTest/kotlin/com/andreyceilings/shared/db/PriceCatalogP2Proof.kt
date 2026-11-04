package com.andreyceilings.shared.db

import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** The identical P2 store contract runs on JDBC, Android WAL, Windows and iOS SQLCipher. */
object PriceCatalogP2Proof {
    suspend fun run(db: PrototypeDatabase) {
        val repository = PriceRepository(db)
        val groups = PriceGroupRepository(db)
        val store = PriceCatalogStore(repository) { 500L }
        val initialItemCount = repository.count()
        val initialGroupCount = groups.all().size
        suspend fun group(uuid: String) = store.groups.first().single { it.uuid == uuid }
        suspend fun item(id: Long) = store.items.first().single { it.id == id }
        val a = store.saveGroup(null, PriceGroupDraft("P2 Категорія", "м²", QuantityMode.CEILING_AREA, PlacementKind.AREA))
        val b = store.saveGroup(null, PriceGroupDraft("P2 Порожня", "  ", QuantityMode.ENTERED_LENGTH, PlacementKind.LINE))
        assertNull(groups.find(b)!!.defaultUnit)
        assertEquals("entered_length", groups.find(b)!!.defaultQuantityMode)
        assertEquals(initialGroupCount + 2, store.groups.first().size)
        assertEquals(initialItemCount, repository.count())
        assertFailsWith<DuplicateGroupName> { store.saveGroup(null, PriceGroupDraft(" p2 КАТЕГОРІЯ ")) }
        val firstGroup = group(a)
        val originalEditor = PriceItemEditor.open(null).copy(draft = ConfiguredPriceDraft(
            title = "P2 Позиція", salePriceText = "999999999,99"), focusField = "price")
        val editor = originalEditor.categoryCreated(firstGroup)
        val id = store.saveConfigured(null, editor.draft)
        val original = item(id)
        assertEquals("м²", original.unit)
        assertEquals(QuantityMode.CEILING_AREA, original.quantityMode)
        assertEquals(PlacementKind.AREA, original.placementKind)
        val identity = Triple(original.id, original.uuid, original.code)

        // Pure group rename/default update cannot alter the item's own row/token or UUID filter.
        store.saveGroup(firstGroup, PriceGroupDraft("P2 Нова назва", "шт.", QuantityMode.MANUAL, PlacementKind.NONE))
        val renamed = group(a)
        assertEquals("P2 Нова назва", item(id).category)
        assertEquals(original.updatedAt, item(id).updatedAt)
        assertEquals(original.quantityMode, item(id).quantityMode)
        assertEquals(a, activePriceFilter(a, store.groups.first()))
        assertFailsWith<PriceCatalogConflict> { store.saveGroup(firstGroup, PriceGroupDraft("Stale")) }
        assertFailsWith<PriceCatalogConflict> { store.deleteGroup(firstGroup) }
        assertFailsWith<PriceGroupNotEmpty> { store.deleteGroup(renamed) }
        assertEquals("P2 Нова назва", groups.find(a)!!.name)
        assertFailsWith<DuplicateGroupName> { store.saveGroup(group(b), PriceGroupDraft(" p2 НОВА НАЗВА ")) }
        store.saveGroup(renamed, PriceGroupDraft("p2 НОВА НАЗВА", "шт.")) // Case-only rename allowed.

        // Changing membership preserves independently owned values, including in quick-price.
        val movedDraft = ConfiguredPriceDraft.from(original).selectGroup(group(b))
        store.saveConfigured(original, movedDraft)
        val moved = item(id)
        assertEquals(identity, Triple(moved.id, moved.uuid, moved.code))
        assertEquals(b, moved.groupUuid)
        assertEquals(original.unit, moved.unit)
        assertEquals(original.price, moved.price)
        assertEquals(original.quantityMode, moved.quantityMode)
        assertEquals(original.placementKind, moved.placementKind)
        store.quickPrice(moved, "25.50")
        val quick = item(id)
        assertEquals(moved.copy(price = 25.5, updatedAt = quick.updatedAt), quick)
        assertFailsWith<PriceCatalogConflict> { store.quickPrice(moved, "11") }
        assertFailsWith<PriceCatalogConflict> { store.saveConfigured(moved, movedDraft.copy(title = "stale")) }
        assertFailsWith<PriceCatalogConflict> { store.delete(moved) }
        assertEquals(25.5, item(id).price)

        // Duplicate item Save keeps the separately confirmed category and immutable draft.
        val child = store.saveGroup(null, PriceGroupDraft("P2 Явна категорія"))
        val retained = PriceItemEditor.open(null).copy(draft = ConfiguredPriceDraft(
            title = " p2 ПОЗИЦІЯ ", unit = "шт.", salePriceText = "9,90", parametersEdited = true), focusField = "price")
            .categoryCreated(group(child))
        assertFailsWith<DuplicateItemTitle> { store.saveConfigured(null, retained.draft) }
        assertNotNull(groups.find(child))
        assertEquals("9,90", retained.draft.salePriceText)
        assertEquals("price", retained.focusField)
        // Same-row case-only rename is legal; a different row with that global key is not.
        store.saveConfigured(quick, ConfiguredPriceDraft.from(quick).copy(title = " p2 ПОЗИЦІЯ "))
        val secondId = store.saveConfigured(null, retained.draft.copy(title = "P2 Друга позиція"))
        val second = item(secondId)
        assertFailsWith<DuplicateItemTitle> {
            store.saveConfigured(second, ConfiguredPriceDraft.from(second).copy(title = "p2 позиція"))
        }
        store.delete(second)
        val latest = item(id)
        val invalidPair = ConfiguredPriceDraft.from(latest).copy(unit = "шт.")
        assertFailsWith<IllegalArgumentException> { store.saveConfigured(latest, invalidPair) }
        store.saveConfigured(latest, invalidPair.copy(quantityMode = QuantityMode.MANUAL))
        val manual = item(id)
        assertEquals(QuantityMode.MANUAL, manual.quantityMode)
        assertEquals(PlacementKind.AREA, manual.placementKind)
        assertEquals(identity, Triple(manual.id, manual.uuid, manual.code))
        for (bad in listOf("-1", "NaN", "Infinity", "1.234", "1000000000", "1e3", "12,345")) {
            assertFailsWith<IllegalArgumentException> { store.saveConfigured(null, retained.draft.copy(title = "P2 Bad", salePriceText = bad)) }
            assertFailsWith<IllegalArgumentException> { store.quickPrice(manual, bad) }
        }
        assertEquals(manual, item(id))
        assertEquals(initialItemCount + 1, repository.count())
        assertEquals(initialGroupCount + 3, groups.all().size)
        store.delete(manual)
        // Deleted-item tombstones do not prevent category deletion; stale child Save must not resurrect it.
        val removed = group(b)
        store.deleteGroup(removed)
        assertNull(groups.find(b))
        assertFailsWith<PriceCatalogConflict> {
            store.saveConfigured(null, movedDraft.copy(title = "P2 Missing group"))
        }
        assertNull(groups.find(b))
        store.deleteGroup(group(a))
        store.deleteGroup(group(child))
        assertEquals(initialItemCount, repository.count())
        assertEquals(initialGroupCount, groups.all().size)
        // Tombstones free global Unicode names without reusing their UUID.
        val replacement = store.saveGroup(null, PriceGroupDraft(" p2 ПОРОЖНЯ "))
        assertNotEquals(b, replacement)
        val replacementId = store.saveConfigured(null, ConfiguredPriceDraft("p2 позиція", replacement, "шт.", "0"))
        assertNotEquals(identity.second, item(replacementId).uuid)
        store.delete(item(replacementId))
        store.deleteGroup(group(replacement))
    }
}
