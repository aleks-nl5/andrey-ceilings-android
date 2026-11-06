package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class LivePricingReaderTest {
    @Test fun walSnapshotNeverMixesChildAndCatalogFromDifferentCommits() {
        coherentRead { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun windowsSqlCipherCoherentRead() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 37 }
        try { coherentRead { SqlCipherWindowsDriverFactory.open(it, key) } } finally { key.fill(0) }
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
                            elements.edit(e.version, "simultaneous", e.approval, e.quantity, 200)
                            val prices = PriceRepository(writable)
                            prices.updateSalePrice(prices.find(fixture.priceId)!!, 20.0, 200)
                        }
                    }
                } finally { writerFinished.countDown() }
                val during = assertIs<PricingReadResult.Loaded>(pending.get(20, TimeUnit.SECONDS))
                assertEquals(before.source.signature, during.source.signature)
                assertEquals(before.catalog.signature, during.catalog.signature)
                val after = assertIs<PricingReadResult.Loaded>(LivePricingReader(db).read(fixture.projectId, fixture.measurementUuid))
                assertNotEquals(before.source.signature, after.source.signature)
                assertNotEquals(before.catalog.signature, after.catalog.signature)
                val closing = LivePricingReader(db)
                closing.closeReads() // JDBC may reopen a connection: session lifecycle must be explicit.
                readDriver.close()
                assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), closing.read(fixture.projectId, fixture.measurementUuid))
            }
        } finally { executor.shutdownNow(); directory.deleteRecursively() }
    }
}
