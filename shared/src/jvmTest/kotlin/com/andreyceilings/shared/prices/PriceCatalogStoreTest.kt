package com.andreyceilings.shared.prices

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceCatalogStoreTest {
    @Test
    fun commonStoreCreatesAndUpdatesWithoutChangingIdentity() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val repository = PriceRepository(PrototypeDatabase(driver))
            var now = 100L
            val store = PriceCatalogStore(repository) { now }

            val id = store.save(null, PriceCatalogDraft("ПВХ", "Полотна", "м²", 290.5))
            val initial = store.items.first().single()
            assertEquals(id, initial.id)
            assertEquals(290.5, initial.price)
            assertTrue(initial.code.startsWith("custom."))
            assertTrue(initial.updatedAt > 0L)

            now = 101L
            assertEquals(id, store.save(initial, PriceCatalogDraft(
                "ПВХ оновлене", "Полотна", "м²", 310.0
            )))
            val updated = store.items.first().single()
            assertEquals(id, updated.id)
            assertEquals(initial.code, updated.code)
            assertEquals("ПВХ оновлене", updated.title)
            assertEquals(310.0, updated.price)
            assertTrue(updated.updatedAt > initial.updatedAt)
            assertEquals(1L, repository.count())

            store.delete(updated)
            assertEquals(0L, repository.count())
            assertNull(repository.find(id))
            assertEquals(1, repository.tombstones().size)
        }
    }

    @Test
    fun staleEditAndDeleteFailClosedWithoutOverwritingNewerData() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val repository = PriceRepository(PrototypeDatabase(driver))
            var now = 10L
            val store = PriceCatalogStore(repository) { now }
            val id = store.save(null, PriceCatalogDraft("Світильник", "Освітлення", "шт.", 100.0))
            val stale = store.items.first().single()

            now = 11L
            store.save(stale, PriceCatalogDraft("Світильник", "Освітлення", "шт.", 200.0))

            assertFailsWith<IllegalStateException> {
                store.save(stale, PriceCatalogDraft("Застаріла назва", "Освітлення", "шт.", 50.0))
            }
            assertFailsWith<IllegalStateException> { store.delete(stale) }
            assertEquals(200.0, repository.find(id)?.price)
            assertEquals("Світильник", repository.find(id)?.title)
            assertEquals(1L, repository.count())
        }
    }

    @Test
    fun inputValidationAndNewRecordCodesAreSharedAcrossHosts() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val repo = PriceRepository(PrototypeDatabase(driver))
            val store = PriceCatalogStore(repo) { 25L }
            val a = store.save(null, PriceCatalogDraft("Перша", "Роботи", "шт.", 999_999_999.99))
            val b = store.save(null, PriceCatalogDraft("Друга", "Роботи", "шт.", 0.0))
            assertNotEquals(repo.find(a)?.code, repo.find(b)?.code)
            for (price in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 1.234)) {
                assertFailsWith<IllegalArgumentException> {
                    store.save(null, PriceCatalogDraft("Помилка", "Роботи", "шт.", price))
                }
            }
            assertFailsWith<IllegalArgumentException> {
                store.save(null, PriceCatalogDraft(" ", "Роботи", "шт.", 10.0))
            }
            assertEquals(2L, repo.count())
        }
    }
}
