package com.andreyceilings.shared.db

import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** The same generated SQL contract runs on JVM, Android WAL, Windows and iOS SQLCipher. */
object PriceCatalogPersistenceProof {
    fun starter(database: PrototypeDatabase) {
        StarterPriceCatalog.initialize(database)
        val groups = PriceGroupRepository(database)
        val prices = PriceRepository(database)
        assertEquals(5, groups.all().size)
        groups.all().forEach { assertEquals(normalizedNameKey(it.name), it.nameKey) }
        assertEquals(22, prices.all().size)
        val actual = prices.all().associateBy { it.code }
        StarterPriceCatalog.items.forEach { expected ->
            val row = actual.getValue(expected.code)
            assertEquals(expected.title, row.title)
            assertEquals(normalizedNameKey(expected.title), row.titleKey)
            assertEquals(expected.category, row.category)
            assertEquals(expected.unit, row.unit)
            assertEquals(expected.price, row.price)
            assertEquals(expected.quantityMode.value, row.quantityMode)
            assertEquals(expected.placementKind.value, row.placementKind)
        }
        StarterPriceCatalog.initialize(database)
        assertEquals(actual, prices.all().associateBy { it.code })
    }

    suspend fun groupsAndParameters(database: PrototypeDatabase) {
        val groups = PriceGroupRepository(database)
        val prices = PriceRepository(database)
        val starter = prices.all().single { it.code == "light.spot" }
        prices.updateConfigured(starter, starter.groupUuid, "Мій світильник", starter.unit,
            QuantityMode.MANUAL, PlacementKind.POINT, 432.0, 9)
        val editedStarter = prices.find(starter.id)
        StarterPriceCatalog.initialize(database)
        assertEquals(editedStarter, prices.find(starter.id))
        val group = groups.create("Власна група", 10, "м²", QuantityMode.CEILING_AREA, PlacementKind.AREA)
        assertEquals(group, groups.observeAll().first().single { it.uuid == group }.uuid)
        assertFails { groups.create("Власна група", 11) }
        assertFails { groups.create("  ", 11) }
        assertFails { groups.create("Bad defaults", 11, "шт.", QuantityMode.CEILING_AREA) }
        val a = prices.createInGroup("proof.default", "Defaults", group, 12.0, 12)
        val initial = checkNotNull(prices.find(a))
        assertEquals("м²", initial.unit)
        assertEquals("ceiling_area", initial.quantityMode)
        assertEquals("area", initial.placementKind)
        val b = prices.createInGroup("proof.override", "Overrides", group, 15.0, 13,
            "коробка", QuantityMode.MANUAL, PlacementKind.POINT)
        val override = checkNotNull(prices.find(b))
        assertEquals("коробка", override.unit)
        assertEquals("manual", override.quantityMode)
        assertEquals("point", override.placementKind)
        assertFails { prices.createInGroup("bad", "Bad", group, 0.0, 13, "шт.") }
        assertFails { groups.delete(group, 10, 14) }
        groups.update(group, 10, "Перейменована", 15, "м.п.", QuantityMode.ENTERED_LENGTH, PlacementKind.LINE)
        val renamed = checkNotNull(groups.find(group))
        assertEquals(group, renamed.uuid)
        assertEquals("Перейменована", prices.observeAll().first().single { it.id == a }.category)
        assertEquals(initial.copy(category = "Перейменована"), prices.find(a))
        assertFails { groups.update(group, 10, "Stale", 16) }
        assertFails { groups.delete(group, 10, 16) }
        val copiedLater = prices.createInGroup("proof.later", "Later", group, 8.0, 17)
        assertEquals("entered_length", prices.find(copiedLater)?.quantityMode)
        val target = groups.create("Інша група", 18, "шт.", QuantityMode.MANUAL, PlacementKind.NONE)
        prices.move(initial, target, 19)
        val moved = checkNotNull(prices.find(a))
        assertEquals(initial.uuid, moved.uuid)
        assertEquals(initial.code, moved.code)
        assertEquals(initial.unit, moved.unit)
        assertEquals(initial.quantityMode, moved.quantityMode)
        assertEquals(initial.placementKind, moved.placementKind)
        assertEquals(initial.price, moved.price)
        assertFails { prices.move(initial, group, 20) }
        assertFails { prices.update(a, "changed.code", "Change", "Інша група", "м²", 1.0, 20) }
        assertFails { prices.updateConfigured(moved, target, "Bad unit", "шт.", QuantityMode.CEILING_AREA, PlacementKind.NONE, 1.0, 20) }
        prices.updateConfigured(moved, target, "Updated", "м.п.", QuantityMode.CEILING_PERIMETER, PlacementKind.POINT, 22.0, 21)
        assertFails { prices.updateConfigured(moved, target, "Stale", "м²", QuantityMode.CEILING_AREA, PlacementKind.AREA, 0.0, 22) }
        prices.deleteIfUnchanged(b, override.code, override.updatedAt)
        prices.delete(copiedLater)
        groups.delete(group, renamed.updatedAt, 23)
        assertNull(groups.find(group))
        assertFails { prices.createInGroup("deleted", "Deleted group", group, 0.0, 24, "шт.") }
        assertNotEquals(group, groups.create("Перейменована", 25))
    }

    fun removeEverything(database: PrototypeDatabase) {
        val prices = PriceRepository(database)
        prices.all().forEach { prices.deleteIfUnchanged(it.id, it.code, it.updatedAt) }
        val groups = PriceGroupRepository(database)
        groups.all().forEach { groups.delete(it.uuid, it.updatedAt, 1000) }
        StarterPriceCatalog.initialize(database)
        assertTrue(prices.all().isEmpty())
        assertTrue(groups.all().isEmpty())
        assertTrue(prices.tombstones().isNotEmpty())
    }
}
