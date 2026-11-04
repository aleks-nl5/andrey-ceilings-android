package com.andreyceilings.shared.prices

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.test.*

class PriceCatalogP2FlowTest {
    @Test fun emptyGroupRenameAndDeletionEmitWithoutAnyItemUpdate(): Unit = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val repository = PriceRepository(db)
            val store = PriceCatalogStore(repository) { 1L }
            val events = Channel<List<PriceCatalogItem>>(Channel.UNLIMITED)
            val groupEvents = Channel<List<PriceCatalogGroup>>(Channel.UNLIMITED)
            val itemJob = launch { store.items.collect { events.send(it) } }
            val groupJob = launch { store.groups.collect { groupEvents.send(it) } }
            try {
                withTimeout(10000) {
                    assertTrue(events.receive().isEmpty())
                    assertTrue(groupEvents.receive().isEmpty())
                    val uuid = store.saveGroup(null, PriceGroupDraft("Порожня", "шт."))
                    var allGroups = groupEvents.receive()
                    while (allGroups.none { it.uuid == uuid }) allGroups = groupEvents.receive()
                    assertEquals("Порожня", allGroups.single().name)
                    val id = store.saveConfigured(null, ConfiguredPriceDraft("Позиція", uuid, "шт.", "12"))
                    var rows = events.receive()
                    while (rows.none { it.id == id }) rows = events.receive()
                    val original = repository.find(id)!!
                    val open = PriceItemEditor.open(rows.single())
                    store.saveGroup(allGroups.single(), PriceGroupDraft("Нова назва", "год."))
                    do { rows = events.receive() } while (rows.singleOrNull()?.category != "Нова назва")
                    assertEquals(original.updatedAt, rows.single().updatedAt)
                    assertEquals(original, repository.find(id)!!.copy(category = original.category))
                    assertEquals(uuid, open.draft.groupUuid)
                    assertEquals("шт.", open.draft.unit)
                    assertNull(open.draft.validationError(store.groups.first()))
                    store.saveConfigured(open.original, open.draft.copy(salePriceText = "13"))
                    val latest = store.items.first().single()
                    store.delete(latest)
                    val group = store.groups.first().single()
                    store.deleteGroup(group)
                    do { allGroups = groupEvents.receive() } while (allGroups.isNotEmpty())
                    assertNull(activePriceFilter(uuid, allGroups))
                    assertEquals(uuid, open.draft.groupUuid)
                    assertNotNull(open.draft.validationError(allGroups))
                    assertFailsWith<PriceCatalogConflict> { store.saveConfigured(null, open.draft) }
                }
            } finally { itemJob.cancelAndJoin(); groupJob.cancelAndJoin() }
        }
    }

    @Test fun quickPricePreservesMigratedDisplayTextExactly() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val repository = PriceRepository(db)
            val store = PriceCatalogStore(repository) { 500L }
            val group = store.saveGroup(null, PriceGroupDraft("Group"))
            store.saveConfigured(null, ConfiguredPriceDraft("Title", group, "шт.", "10"))
            // v5 display strings are intentionally not normalized during migration.
            driver.execute(null, "UPDATE price_items SET title = '  Title  ', unit = ' шт. '", 0)
            val initial = store.items.first().single()
            store.quickPrice(initial, "999999999,99")
            val saved = store.items.first().single()
            assertEquals(initial.copy(price = 999_999_999.99, updatedAt = saved.updatedAt), saved)
        }
    }

    @Test fun databaseErrorsRemainDistinctFromDuplicatesAndStaleVersions() {
        assertEquals("Категорія з такою назвою вже існує", catalogErrorMessage(DuplicateGroupName()))
        assertEquals("Позиція з такою назвою вже існує", catalogErrorMessage(DuplicateItemTitle()))
        assertEquals("Дані змінилися. Оновіть і повторіть", catalogErrorMessage(PriceCatalogConflict()))
        assertFalse(catalogErrorMessage(IllegalStateException("SQLCipher I/O failure")).contains("вже існує"))
    }
}
