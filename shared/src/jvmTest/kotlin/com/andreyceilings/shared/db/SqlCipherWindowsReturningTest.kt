package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import com.andreyceilings.shared.estimate.ImmutableEstimateDraft
import com.andreyceilings.shared.estimate.ImmutableEstimateLine
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** These tests use the encrypted native Windows runtime, never JdbcSqliteDriver. */
class SqlCipherWindowsReturningTest {
    @Test
    fun generatedReturningInsertReturnsItsOwnId() = withEncryptedDatabase { driver ->
        PrototypeDatabase.Schema.create(driver)
        val clients = ClientRepository(PrototypeDatabase(driver))
        val id = clients.createReturningId("RETURNING regression", createdAt = 1)
        assertEquals("RETURNING regression", clients.find(id)?.name)
    }

    @Test
    fun nativeJdbcDispatchProbe() = withEncryptedDatabase { driver ->
        (driver as JdbcDriver).getConnection().use { connection ->
            connection.prepareStatement("CREATE TABLE probe(id INTEGER PRIMARY KEY, value TEXT)").use { it.execute() }
            connection.prepareStatement("BEGIN").use { it.execute() }
            try {
                connection.prepareStatement("INSERT INTO probe(value) VALUES (?) RETURNING id").use { statement ->
                    statement.setString(1, "direct-query")
                    assertEquals("executeQuery cannot be used for non-query SQL",
                        assertFailsWith<SQLException> { statement.executeQuery() }.message)
                    assertNull(statement.resultSet)
                    // execute() is not a solution in core 0.2.13: it steps the write,
                    // then rejects SQLITE_ROW and leaves no readable ResultSet.
                    assertEquals("Statement produced a result set. Use execute()/executeQuery()",
                        assertFailsWith<SQLException> { statement.execute() }.message)
                    assertNull(statement.resultSet)
                }
                connection.prepareStatement("SELECT count(*) FROM probe").use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1L, rows.getLong(1), "Rejected execute() already performed the write")
                    }
                }
                // A legal, unused CTE selects the facade's rowset path without
                // splitting the SQLite mutation and its RETURNING result.
                listOf(
                    "INSERT INTO probe(value) VALUES ('cte') RETURNING id" to 1,
                    "UPDATE probe SET value = 'updated' RETURNING id" to 2,
                    "DELETE FROM probe RETURNING id" to 2,
                    "UPDATE probe SET value = 'missing' RETURNING id" to 0,
                    "DELETE FROM probe RETURNING id" to 0
                ).forEach { (sql, expectedRows) ->
                    connection.prepareStatement("WITH __sqldelight_returning AS (SELECT 1) $sql").use { statement ->
                        assertTrue(statement.execute(), sql)
                        val rows = assertNotNull(statement.resultSet, sql)
                        rows.use {
                            var count = 0
                            while (it.next()) { assertTrue(it.getLong(1) > 0); count++ }
                            assertEquals(expectedRows, count, sql)
                        }
                        assertTrue(rows.isClosed)
                    }
                }
            } finally {
                connection.prepareStatement("ROLLBACK").use { it.execute() }
            }
        }
    }

    @Test
    fun optimisticPriceWritesReturnOnlyMatchingLiveRows() = withEncryptedDatabase { driver ->
        PrototypeDatabase.Schema.create(driver)
        val db = PrototypeDatabase(driver)
        val prices = PriceRepository(db)
        val id = prices.upsertReturningId(code = "P1", title = "Canvas", category = "material",
            unit = "m2", price = 10.0, updatedAt = 100)
        assertEquals(id, prices.updateIfUnchanged(id, "P1", 100, "New canvas", "material", "m2", 12.0, 101))
        val current = assertNotNull(prices.find(id))
        assertEquals(101L, current.updatedAt)
        assertEquals(12.0, current.price)
        assertFailsWith<IllegalStateException> {
            prices.updateIfUnchanged(id, "P1", 100, "Stale", "material", "m2", 99.0, 102)
        }
        assertNull(db.businessSchemaQueries.updatePriceItemIfVersion(
            "Wrong code", "material", "m2", 99.0, 102, id, "OTHER", 101).executeAsOneOrNull())
        assertNull(db.businessSchemaQueries.deletePriceItemIfVersion(id, "P1", 100).executeAsOneOrNull())
        assertEquals(current, prices.find(id), "Stale RETURNING must not mutate the row")
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writes = (1..2).map { writer -> executor.submit<Result<Long>> {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                runCatching { prices.updateIfUnchanged(id, "P1", 101, "Writer $writer", "material", "m2", 13.0, 102) }
            } }
            start.countDown()
            val results = writes.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(listOf(id), results.mapNotNull { it.getOrNull() }, "Exactly one optimistic writer wins")
            assertIs<IllegalStateException>(results.single { it.isFailure }.exceptionOrNull())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
        assertEquals(102L, prices.find(id)?.updatedAt)
        assertEquals(id, prices.deleteIfUnchanged(id, "P1", 102))
        assertNull(prices.find(id))
        assertEquals(id, prices.tombstones().single().id)
        assertNull(db.businessSchemaQueries.deletePriceItemIfVersion(id, "P1", 101).executeAsOneOrNull())
        assertFailsWith<IllegalStateException> { prices.deleteIfUnchanged(id, "P1", 101) }
    }

    @Test
    fun multirowDeleteEmptyResultsBindingsAndOrdinaryCrud() = withEncryptedDatabase { driver ->
        driver.execute(null, "CREATE TABLE probe(id INTEGER PRIMARY KEY, value TEXT UNIQUE, amount REAL, flag INTEGER, payload BLOB)", 0)
        val ids = driver.executeQuery(null,
            "/* leading comment */ INSERT INTO probe(value, amount, flag, payload) VALUES (?, ?, ?, ?), ('other', NULL, NULL, NULL) RETURNING id",
            { cursor -> QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) }) }, 4) {
            bindString(0, "bound")
            bindDouble(1, 1.25)
            bindBoolean(2, true)
            bindBytes(3, byteArrayOf(1, 2, 3))
        }.value
        assertEquals(listOf(1L, 2L), ids)
        driver.executeQuery(null, "SELECT value, amount, flag, payload FROM probe ORDER BY id", { cursor ->
            assertTrue(cursor.next().value)
            assertEquals("bound", cursor.getString(0))
            assertEquals(1.25, cursor.getDouble(1))
            // SQLite booleans are stored as integers; the facade exposes getLong.
            assertEquals(1L, cursor.getLong(2))
            assertContentEquals(byteArrayOf(1, 2, 3), cursor.getBytes(3))
            assertTrue(cursor.next().value)
            assertNull(cursor.getDouble(1)); assertNull(cursor.getLong(2)); assertNull(cursor.getBytes(3))
            assertFalse(cursor.next().value)
            QueryResult.Unit
        }, 0)
        assertEquals(listOf(1L, 2L), returningIds(driver, "UPDATE probe SET amount = 2 RETURNING id"))
        assertEquals(emptyList(), returningIds(driver, "UPDATE probe SET amount = 3 WHERE id = 999 RETURNING id"))
        assertEquals(emptyList(), returningIds(driver, "INSERT INTO probe(value) VALUES ('bound') ON CONFLICT DO NOTHING RETURNING id"))
        // The compatibility CTE must never shadow an identifier used by the query.
        driver.execute(null, "CREATE TABLE __sqldelight_returning(value TEXT)", 0)
        driver.execute(null, "INSERT INTO __sqldelight_returning VALUES ('source')", 0)
        assertEquals(listOf(3L), returningIds(driver,
            "INSERT INTO probe(value) SELECT value FROM __sqldelight_returning RETURNING id"))
        assertEquals(listOf(1L, 2L, 3L), returningIds(driver, "DELETE FROM probe RETURNING id"))
        assertEquals(emptyList(), returningIds(driver, "DELETE FROM probe RETURNING id"))
        // Already WITH-prefixed statements use the native rowset path unchanged.
        assertEquals(listOf(1L), returningIds(driver,
            "WITH source(value) AS (SELECT 'existing CTE') INSERT INTO probe(value) SELECT value FROM source RETURNING id"))
        assertEquals(1L, driver.execute(null, "INSERT INTO probe(value) VALUES ('ordinary')", 0).value)
        assertEquals(1L, driver.execute(null, "UPDATE probe SET amount = 4 WHERE value = 'ordinary'", 0).value)
        assertEquals(1L, driver.execute(null, "DELETE FROM probe WHERE value = 'ordinary'", 0).value)
        assertEquals(listOf(1L), returningIds(driver, "SELECT id FROM probe"))
    }

    @Test
    fun returningTransactionsAndFlowsPublishOnlyCommittedChanges() = withEncryptedDatabase { driver -> runBlocking {
        PrototypeDatabase.Schema.create(driver)
        val db = PrototypeDatabase(driver)
        val clients = ClientRepository(db)
        val values = Channel<List<String>>(Channel.UNLIMITED)
        val job = launch { clients.observeAll().collect { values.send(it.map { row -> row.name }) } }
        var notifications = 0
        val query = clients.observeQuery()
        val listener = Query.Listener { notifications++ }
        query.addListener(listener)
        try {
            assertEquals(emptyList(), withTimeout(20_000) { values.receive() })
            db.transaction {
                val first = clients.createReturningId("A", createdAt = 1)
                db.transaction {
                    val second = clients.createReturningId("B", createdAt = 2)
                    assertNotEquals(first, second)
                    assertEquals(listOf("A", "B"), clients.all().map { it.name })
                }
                assertEquals(0, notifications, "No notifications before the outer commit")
            }
            assertEquals(1, notifications)
            assertEquals(listOf("A", "B"), withTimeout(20_000) { values.receive() })
            assertFails {
                db.transaction {
                    clients.createReturningId("Rollback outer", createdAt = 3)
                    db.transaction { clients.createReturningId("Rollback inner", createdAt = 4) }
                    error("rollback after successful nested transaction")
                }
            }
            assertFails {
                db.transaction {
                    clients.createReturningId("Rollback parent", createdAt = 5)
                    db.transaction {
                        clients.createReturningId("Rollback child", createdAt = 6)
                        error("nested failure")
                    }
                }
            }
            assertEquals(1, notifications, "Rolled-back writes must not notify")
            assertEquals(listOf("A", "B"), clients.all().map { it.name })
            clients.createReturningId("C", createdAt = 7)
            assertEquals(listOf("A", "B", "C"), withTimeout(20_000) { values.receive() })
        } finally {
            query.removeListener(listener)
            job.cancelAndJoin()
            values.close()
        }
    } }

    @Test
    fun generatedPriceReturningNotifiesFlowOnInsertUpdateAndTombstone() = withEncryptedDatabase { driver -> runBlocking {
        PrototypeDatabase.Schema.create(driver)
        val prices = PriceRepository(PrototypeDatabase(driver))
        val values = Channel<List<String>>(Channel.UNLIMITED)
        val job = launch { prices.observeAll().collect { values.send(it.map { row -> row.title }) } }
        try {
            assertEquals(emptyList(), withTimeout(20_000) { values.receive() })
            val id = prices.upsertReturningId(code = "LIVE", title = "Initial", category = "material",
                unit = "m2", price = 10.0, updatedAt = 1)
            assertEquals(listOf("Initial"), withTimeout(20_000) { values.receive() })
            prices.updateIfUnchanged(id, "LIVE", 1, "Updated", "material", "m2", 20.0, 2)
            assertEquals(listOf("Updated"), withTimeout(20_000) { values.receive() })
            prices.deleteIfUnchanged(id, "LIVE", 2)
            assertEquals(emptyList(), withTimeout(20_000) { values.receive() })
        } finally {
            job.cancelAndJoin()
            values.close()
        }
    } }

    @Test
    fun concurrentCreatesKeepReturnedIdentityAndOwnership() = withEncryptedDatabase { driver ->
        PrototypeDatabase.Schema.create(driver)
        assertEquals(listOf(5000L), returningIds(driver, "PRAGMA busy_timeout"))
        val db = PrototypeDatabase(driver)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val writes = (1..4).map { worker -> executor.submit<List<Pair<Long, String>>> {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                (1..2).map { index ->
                    val name = "writer-$worker-$index"
                    // One create outside a transaction and one with nested related writes.
                    if (index == 1) {
                        ClientRepository(db).createReturningId(name, createdAt = 1) to name
                    } else db.transactionWithResult {
                        val client = ClientRepository(db).createReturningId(name, createdAt = 2)
                        db.transaction {
                            val project = ProjectRepository(db).createReturningId(client, name, createdAt = 2)
                            val measurement = MeasurementRepository(db).createReturningId(project, name, createdAt = 2)
                            assertEquals(project, MeasurementRepository(db).find(measurement)?.projectId)
                            assertEquals(client, ProjectRepository(db).find(project)?.clientId)
                        }
                        client to name
                    }
                }
            } }
            start.countDown()
            val results = writes.flatMap { it.get(60, TimeUnit.SECONDS) }
            assertEquals(8, results.map { it.first }.toSet().size)
            results.forEach { (id, name) -> assertEquals(name, ClientRepository(db).find(id)?.name) }
            assertEquals(8, ClientRepository(db).all().size)
            assertEquals(4, ProjectRepository(db).all().size)
            assertEquals(4, MeasurementRepository(db).all().size)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))
        }
    }

    @Test
    fun numberingReturningAndConstraintFailureRemainAtomic() = withEncryptedDatabase { driver ->
        PrototypeDatabase.Schema.create(driver)
        val db = PrototypeDatabase(driver)
        val project = ProjectRepository(db).createReturningId(null, "Numbered", createdAt = 1)
        val history = ImmutableEstimateRepository(db)
        val draft = ImmutableEstimateDraft("{}", "{}", 0.001, "kmp-v1", 10.0,
            listOf(ImmutableEstimateLine("canvas", "Canvas", "m2", 1.0, 10.0, 10.0)))
        val first = history.createSeries(project, 2026, 1, draft)
        assertEquals(1L, first.localSequence)
        // Collision returns an empty rowset repeatedly, then rolls back the sequence.
        assertFailsWith<IllegalStateException> {
            history.createSeries(project, 2026, 2, draft, newSeriesUuid = { first.seriesUuid })
        }
        // Fail after INSERT RETURNING with a duplicate immutable revision identity.
        assertFails {
            history.createSeries(project, 2026, 3, draft, newRevisionUuid = { first.initialRevisionUuid })
        }
        val second = history.createSeries(project, 2026, 4, draft)
        assertEquals(2L, second.localSequence)
        assertEquals(2, history.seriesForProject(project).size)
        assertEquals(1, history.revisions(first.seriesUuid).size)
    }

    @Test
    fun binderMapperAndSqlFailuresReleaseResourcesAndAllowRollback() = withEncryptedDatabase { driver ->
        driver.execute(null, "CREATE TABLE probe(id INTEGER PRIMARY KEY, value TEXT UNIQUE)", 0)
        repeat(3) {
            assertFailsWith<IllegalArgumentException> {
                driver.executeQuery(null, "INSERT INTO probe(value) VALUES (?) RETURNING id", { QueryResult.Unit }, 1) {
                    bindString(0, "never executed")
                    throw IllegalArgumentException("binder failure")
                }
            }
        }
        assertEquals(emptyList(), returningIds(driver, "SELECT id FROM probe"))
        val db = PrototypeDatabase(driver)
        assertFailsWith<IllegalArgumentException> {
            db.transaction {
                driver.executeQuery<Unit>(null, "INSERT INTO probe(value) VALUES ('mapped') RETURNING id", { cursor ->
                    assertTrue(cursor.next().value)
                    throw IllegalArgumentException("mapper failure after the mutation")
                }, 0)
            }
        }
        assertEquals(emptyList(), returningIds(driver, "SELECT id FROM probe"))
        assertFailsWith<SQLException> { returningIds(driver, "INSERT INTO missing_table VALUES (1) RETURNING id") }
        assertEquals(listOf(1L), returningIds(driver, "INSERT INTO probe(value) VALUES ('valid') RETURNING id"))
        assertFailsWith<SQLException> { returningIds(driver, "INSERT INTO probe(value) VALUES ('valid') RETURNING id") }
        assertEquals(listOf(1L), returningIds(driver, "SELECT id FROM probe"))
        // Closing a partially consumed rowset must finish/reset the write and free its lock.
        driver.executeQuery(null, "UPDATE probe SET value = 'partial' RETURNING id", { cursor ->
            assertTrue(cursor.next().value)
            QueryResult.Unit
        }, 0)
        assertEquals(listOf(1L), returningIds(driver, "DELETE FROM probe RETURNING id"))
    }

    private fun returningIds(driver: SqlDriver, sql: String): List<Long> =
        driver.executeQuery(null, sql, { cursor ->
            QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getLong(0)!!) })
        }, 0).value

    private fun withEncryptedDatabase(block: (SqlDriver) -> Unit) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("windows-returning-").toFile()
        val key = ByteArray(32) { 71 }
        try {
            SqlCipherWindowsDriverFactory.open(directory.resolve("encrypted.db"), key).use(block)
        } finally {
            key.fill(0)
            assertTrue(directory.deleteRecursively(), "All native database handles must be closed")
        }
    }
}
