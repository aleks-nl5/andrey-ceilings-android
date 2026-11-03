package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.prices.*
import kotlin.test.*

/** The same assertions run against JVM SQLite and all three real SQLCipher drivers. */
object PriceNameUniquenessProof {
    fun normalization() {
        for ((expected, variants) in listOf(
            "полотна" to listOf("Полотна", "полотна", " ПОЛОТНА "),
            "точковий світильник" to listOf("Точковий світильник", "точковий світильник", " ТОЧКОВИЙ СВІТИЛЬНИК "),
            "ґєії ascii" to listOf("ҐЄІЇ ASCII", "ґєії ascii", "\t ҐєІї AsCiI \n")
        )) variants.forEach { assertEquals(expected, normalizedNameKey(it)) }
        for ((a, b) in listOf("Два пробіли" to "Два  пробіли", "е" to "є", "и" to "і",
            "-" to "—", "e\u0301" to "é", "a" to "а", "Ａ" to "A", "'" to "’")) {
            assertNotEquals(normalizedNameKey(a), normalizedNameKey(b))
        }
        assertEquals("", normalizedNameKey("\t \n\u00a0"))
    }

    fun crud(driver: SqlDriver) {
        normalization()
        val db = PrototypeDatabase(driver)
        val groups = PriceGroupRepository(db)
        val prices = PriceRepository(db)
        val first = groups.create(" ҐЄІЇ ASCII ", 100, "шт.")
        assertEquals(first, groups.ensureByName("ґєії ascii", 101))
        assertEquals(first, groups.findByName(" ґєії ASCII ")?.uuid)
        assertFailsWith<DuplicateGroupName> { groups.create("ґєії ascii", 102) }
        val second = groups.create("Інша група", 100, "шт.")
        assertFailsWith<DuplicateGroupName> { groups.rename(second, 100, " ґєії ascii ", 103) }
        groups.rename(first, 100, " ґєії ascii ", 104)
        assertEquals("ґєії ascii", groups.find(first)?.name)
        assertEquals(104L, groups.find(first)?.updatedAt)
        assertFailsWith<IllegalArgumentException> { groups.create(" \t\n ", 105) }

        val id = prices.all().singleOrNull { it.titleKey == normalizedNameKey("Точковий світильник") }?.id
            ?: prices.createInGroup("unique.spot", " Точковий світильник ", first, 123.45, 110)
        val original = assertNotNull(prices.find(id))
        assertEquals("Точковий світильник", original.title)
        assertEquals(normalizedNameKey(original.title), original.titleKey)
        for (group in listOf(original.groupUuid, first, second).distinct()) {
            assertFailsWith<DuplicateItemTitle> {
                prices.createInGroup("unique.other", " ТОЧКОВИЙ СВІТИЛЬНИК ", group, 999.0, 111)
            }
        }
        assertFailsWith<IllegalArgumentException> { prices.createInGroup("blank", "\t ", first, 1.0, 111) }
        val otherId = prices.createInGroup("unique.other", " Інша позиція ", second, 42.0, 112)
        val other = assertNotNull(prices.find(otherId))
        assertEquals("Інша позиція", other.title)
        assertFailsWith<DuplicateItemTitle> {
            prices.updateConfigured(other, first, "точковий світильник", "шт.", QuantityMode.MANUAL, PlacementKind.POINT, 888.0, 113)
        }
        assertEquals(other, prices.find(otherId))
        val store = PriceCatalogStore(prices) { 114L }
        assertFailsWith<DuplicateItemTitle> {
            store.save(null, PriceCatalogDraft("ТОЧКОВИЙ СВІТИЛЬНИК", "Не залишати порожню групу", "шт.", 2.0))
        }
        assertNull(groups.findByName("Не залишати порожню групу"))
        assertFailsWith<DuplicateItemTitle> {
            prices.updateIfUnchanged(otherId, other.code, other.updatedAt, "ТОЧКОВИЙ СВІТИЛЬНИК", "Нова група при зміні", "шт.", 3.0, 115)
        }
        assertNull(groups.findByName("Нова група при зміні"))
        assertEquals(other, prices.find(otherId))

        prices.updateConfigured(original, first, " точковий СВІТИЛЬНИК ", "шт.", QuantityMode.MANUAL, PlacementKind.NONE, 123.45, 116)
        val renamed = assertNotNull(prices.find(id))
        assertEquals(original.uuid, renamed.uuid)
        assertEquals(original.code, renamed.code)
        assertEquals(original.titleKey, renamed.titleKey)
        assertEquals(116L, renamed.updatedAt)
        prices.move(renamed, second, 117)
        val moved = assertNotNull(prices.find(id))
        assertEquals(renamed.title, moved.title)
        assertEquals(renamed.titleKey, moved.titleKey)
        // A stale edit proposing an occupied title must remain a version conflict.
        val stale = assertFailsWith<IllegalStateException> {
            prices.updateConfigured(original, first, other.title, "шт.", QuantityMode.MANUAL, PlacementKind.NONE, 1.0, 118)
        }
        assertFalse(stale is DuplicateItemTitle)
        val staleGroup = assertFailsWith<IllegalStateException> { groups.rename(second, 99, "ґєії ascii", 118) }
        assertFalse(staleGroup is DuplicateGroupName)
        prices.deleteIfUnchanged(id, moved.code, moved.updatedAt)
        val replacement = prices.createInGroup("replacement", "ТОЧКОВИЙ СВІТИЛЬНИК", first, 9.0, 119)
        assertNotEquals(original.uuid, prices.find(replacement)?.uuid)
        assertTrue(prices.tombstones().any { it.uuid == original.uuid })
        val disposable = groups.create("Для повторного використання", 120)
        groups.delete(disposable, 120, 121)
        val reused = groups.create("ДЛЯ ПОВТОРНОГО ВИКОРИСТАННЯ", 122)
        assertNotEquals(disposable, reused)
        assertEquals(1L, scalar(driver, "SELECT count(*) FROM price_groups WHERE uuid = '$disposable' AND deletedAt IS NOT NULL"))
        prices.createInGroup("spaces", "Два  пробіли", first, 1.0, 123)
        prices.createInGroup("space", "Два пробіли", first, 1.0, 123)
        assertKeys(driver)
        rejectsMissingKeys(driver, first)
        legacyQueries(driver, groups.find(first)!!.name)
        // Genuine SQL errors are never translated into duplicate-name results.
        val broken = object : SqlDriver by driver {
            override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                if (sql.contains("INSERT INTO price_groups")) error("Injected I/O failure")
                return driver.executeQuery(identifier, sql, mapper, parameters, binders)
            }
        }
        val io = assertFailsWith<IllegalStateException> { PriceGroupRepository(PrototypeDatabase(broken)).create("I/O", 125) }
        assertFalse(io is DuplicateGroupName)
    }

    fun assertKeys(driver: SqlDriver) {
        for ((table, name, key) in listOf(Triple("price_groups", "name", "nameKey"), Triple("price_items", "title", "titleKey"))) {
            driver.executeQuery(null, "SELECT $name, $key FROM $table WHERE deletedAt IS NULL", { cursor ->
                while (cursor.next().value) {
                    assertEquals(normalizedNameKey(checkNotNull(cursor.getString(0))), cursor.getString(1))
                    assertFalse(cursor.getString(1).isNullOrEmpty())
                }
                QueryResult.Unit
            }, 0).value
        }
    }

    private fun rejectsMissingKeys(driver: SqlDriver, group: String) {
        for (value in listOf("NULL", "''")) {
            assertFails { driver.execute(null, "INSERT INTO price_groups(uuid,name,nameKey,createdAt,updatedAt) VALUES ('bad','Bad',$value,1,1)", 0) }
            assertFails { driver.execute(null, "INSERT INTO price_items(uuid,code,title,titleKey,groupUuid,unit,price,updatedAt) VALUES ('bad','bad','Bad',$value,'$group','шт.',1,1)", 0) }
            assertFails { driver.execute(null, "UPDATE price_groups SET nameKey=$value WHERE uuid='$group'", 0) }
            assertFails { driver.execute(null, "UPDATE price_items SET titleKey=$value WHERE deletedAt IS NULL", 0) }
        }
    }

    private fun legacyQueries(driver: SqlDriver, category: String) {
        val db = PrototypeDatabase(driver)
        val queries = db.businessSchemaQueries
        val prices = PriceRepository(db)
        val categoryKey = normalizedNameKey(category)
        val title = "Legacy ҐЄІЇ"
        val key = normalizedNameKey(title)
        queries.insertPriceItem("legacy.insert", title, key, categoryKey, "шт.", 1.0, 130, "legacy-insert")
        val id = prices.all().single { it.uuid == "legacy-insert" }.id
        queries.updatePriceItem("Legacy перейменовано", normalizedNameKey("Legacy перейменовано"), categoryKey, "шт.", 2.0, 131, "legacy.insert", id)
        val changed = "Legacy CAS ҐЄІЇ"
        assertEquals(id, queries.updatePriceItemIfVersion(changed, normalizedNameKey(changed), categoryKey,
            "шт.", 3.0, 132, id, "legacy.insert", 131).executeAsOne())
        val returningTitle = "Legacy returning"
        queries.insertPriceItemReturningId("legacy.returning", returningTitle, normalizedNameKey(returningTitle),
            categoryKey, "шт.", 4.0, 133, "legacy-returning").executeAsOne()
        val importedTitle = "Legacy import"
        queries.importPriceItemWithId(90001, "legacy.import", importedTitle, normalizedNameKey(importedTitle),
            categoryKey, "шт.", 5.0, 134, "legacy-import")
        for (invalid in listOf(null, "")) {
            assertFails { queries.insertPriceItem("bad.insert", "Bad", invalid, categoryKey, "шт.", 0.0, 135, "bad-insert") }
            assertFails { queries.insertPriceItemReturningId("bad.returning", "Bad", invalid, categoryKey, "шт.", 0.0, 135, "bad-returning").executeAsOne() }
            assertFails { queries.importPriceItemWithId(90002, "bad.import", "Bad", invalid, categoryKey, "шт.", 0.0, 135, "bad-import") }
            assertFails { queries.updatePriceItem("Bad", invalid, categoryKey, "шт.", 0.0, 135, "legacy.insert", id) }
            assertFails { queries.updatePriceItemIfVersion("Bad", invalid, categoryKey, "шт.", 0.0, 135, id, "legacy.insert", 132).executeAsOneOrNull() }
        }
        assertEquals(changed, prices.find(id)?.title)
        assertKeys(driver)
    }

    fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(null, sql, { cursor ->
        check(cursor.next().value); QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }, 0).value
}
