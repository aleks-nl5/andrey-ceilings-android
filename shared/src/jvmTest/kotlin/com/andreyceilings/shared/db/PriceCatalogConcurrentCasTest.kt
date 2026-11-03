package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
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
}
