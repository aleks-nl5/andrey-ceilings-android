package com.andreyceilings.shared.db

import app.cash.sqldelight.db.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*
import kotlinx.coroutines.*

class LivePricingReaderTest {
    @Test fun walSnapshotNeverMixesChildAndCatalogFromDifferentCommits() {
        coherentRead { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun windowsSqlCipherCoherentRead() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 37 }
        try { coherentRead { SqlCipherWindowsDriverFactory.open(it, key) } } finally { key.fill(0) }
    }

    @Test fun closeDrainsPausedTransactionsAndRejectsNewReads() {
        closingRead { JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}") }
    }

    @Test fun windowsCipherCloseDrainsPausedTransactions() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 38 }
        try { closingRead { SqlCipherWindowsDriverFactory.open(it, key) } } finally { key.fill(0) }
    }

    private fun closingRead(open: (java.io.File) -> SqlDriver) = runBlocking {
        for (failRead in listOf(false, true)) for (cancelClose in listOf(false, true)) for (verify in listOf(false, true)) {
            val directory = Files.createTempDirectory("p4a-close-").toFile()
            val executor = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                open(directory.resolve("close.db")).use { driver ->
                    PriceCatalogSchema.create(driver)
                    val fixture = LivePricingProof.seed(PrototypeDatabase(driver))
                    var queries = 0
                    val paused = object : SqlDriver by driver {
                        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
                            binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
                            queries++
                            val value = driver.executeQuery(identifier, sql, mapper, parameters, binders)
                            if (sql.contains("FROM projects")) {
                                entered.countDown()
                                check(release.await(20, TimeUnit.SECONDS))
                                if (failRead) error("Injected read failure after close began")
                            }
                            return value
                        }
                    }
                    val reader = LivePricingReader(PrototypeDatabase(paused))
                    val inFlight = executor.submit<Any> {
                        if (verify) reader.verifyFrozenRevision(fixture.projectId, "missing", "missing")
                        else reader.read(fixture.projectId, fixture.measurementUuid)
                    }
                    assertTrue(entered.await(20, TimeUnit.SECONDS))
                    val close = launch(start = CoroutineStart.UNDISPATCHED) { reader.closeReads() }
                    assertFalse(close.isCompleted) // Admission closed, physical read still owns the DB.
                    if (cancelClose) close.cancel()
                    val before = queries
                    assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), reader.read(fixture.projectId, fixture.measurementUuid))
                    assertEquals(PricingProblemCode.DATABASE_UNAVAILABLE, reader.verifyFrozenRevision(fixture.projectId, "s", "r"))
                    assertEquals(before, queries)
                    assertFalse(close.isCompleted)
                    release.countDown()
                    close.join() // Owner can now replace/close the driver safely, even after cancellation.
                    assertEquals(if (verify) PricingProblemCode.DATABASE_UNAVAILABLE else
                        PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), inFlight.get(20, TimeUnit.SECONDS))
                    assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), reader.read(fixture.projectId, fixture.measurementUuid))
                }
            } finally { release.countDown(); executor.shutdownNow(); directory.deleteRecursively() }
        }
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
                runBlocking { closing.closeReads() } // JDBC may reopen a connection: session lifecycle must be explicit.
                readDriver.close()
                assertEquals(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE), closing.read(fixture.projectId, fixture.measurementUuid))
            }
        } finally { executor.shutdownNow(); directory.deleteRecursively() }
    }
}
