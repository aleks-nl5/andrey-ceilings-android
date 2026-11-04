package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PriceCatalogConcurrentCasTest {
    @Test fun twoReadersReachAtomicCasAndTheLoserGetsAVersionConflict() {
        val file = Files.createTempFile("price-cas-", ".db").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                PrototypeDatabase.Schema.create(driver)
                val prices = PriceRepository(PrototypeDatabase(driver))
                val id = prices.upsertReturningId(code = "cas", title = "Before", category = "Group",
                    unit = "шт.", price = 10.0, updatedAt = 100)
                val readers = CountDownLatch(2)
                val observed = object : SqlDriver by driver {
                    override fun <R> executeQuery(identifier: Int?, sql: String,
                        mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                        val result = driver.executeQuery(identifier, sql, mapper, parameters, binders)
                        if (sql.contains("FROM price_catalog_items WHERE id =")) {
                            readers.countDown()
                            check(readers.await(10, TimeUnit.SECONDS)) { "Concurrent readers did not meet" }
                        }
                        return result
                    }
                }
                val competing = PriceRepository(PrototypeDatabase(observed))
                val writes = (1..2).map { writer -> executor.submit<Result<Long>> {
                    runCatching { competing.updateIfUnchanged(id, "cas", 100, "Writer $writer", "Group", "шт.", 20.0, 101) }
                } }
                val results = writes.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(listOf(id), results.mapNotNull { it.getOrNull() })
                assertIs<IllegalStateException>(results.single { it.isFailure }.exceptionOrNull())
                assertEquals(101L, prices.find(id)?.updatedAt)
                assertEquals(20.0, prices.find(id)?.price)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
            file.delete()
        }
    }

    @Test fun configuredEditorAndQuickPriceRaceRejectsOneExactVersionWithoutPartialWrites() = runBlocking {
        val file = Files.createTempFile("price-p2-cas-", ".db").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                PrototypeDatabase.Schema.create(driver)
                val db = PrototypeDatabase(driver)
                val repository = PriceRepository(db)
                val group = repository.groups.create("Group", 1, "м²", QuantityMode.CEILING_AREA, PlacementKind.AREA)
                val target = repository.groups.create("Target", 1, "шт.")
                val id = repository.createInGroup("code", "Title", group, 10.0, 100)
                val original = PriceCatalogStore(repository) { 101L }.items.first().single()
                val readers = CountDownLatch(2)
                val observed = object : SqlDriver by driver {
                    override fun <R> executeQuery(identifier: Int?, sql: String,
                        mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                        val result = driver.executeQuery(identifier, sql, mapper, parameters, binders)
                        if (sql.contains("FROM price_catalog_items WHERE id =")) {
                            readers.countDown()
                            check(readers.await(10, TimeUnit.SECONDS)) { "Concurrent readers did not meet" }
                        }
                        return result
                    }
                }
                val store = PriceCatalogStore(PriceRepository(PrototypeDatabase(observed))) { 101L }
                val configured = executor.submit<Result<Long>> { runCatching {
                    store.saveConfigured(original, ConfiguredPriceDraft.from(original).copy(groupUuid = target,
                        title = "Changed", unit = "шт.", quantityMode = QuantityMode.MANUAL,
                        placementKind = PlacementKind.POINT, salePriceText = "30"))
                } }
                val quick = executor.submit<Result<Long>> { runCatching { store.quickPrice(original, "20") } }
                val results = listOf(configured.get(20, TimeUnit.SECONDS), quick.get(20, TimeUnit.SECONDS))
                assertEquals(1, results.count { it.isSuccess })
                assertIs<PriceCatalogConflict>(results.single { it.isFailure }.exceptionOrNull())
                val saved = repository.find(id)!!
                assertEquals(original.uuid, saved.uuid)
                assertEquals(original.code, saved.code)
                assertEquals(101L, saved.updatedAt)
                if (results.first().isSuccess) {
                    assertEquals(target, saved.groupUuid)
                    assertEquals("Changed", saved.title)
                    assertEquals("manual", saved.quantityMode)
                    assertEquals("point", saved.placementKind)
                    assertEquals("шт.", saved.unit)
                    assertEquals(30.0, saved.price)
                } else {
                    assertEquals(group, saved.groupUuid)
                    assertEquals(original.title, saved.title)
                    assertEquals("ceiling_area", saved.quantityMode)
                    assertEquals("area", saved.placementKind)
                    assertEquals("м²", saved.unit)
                    assertEquals(20.0, saved.price)
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
            file.delete()
        }
    }

}
