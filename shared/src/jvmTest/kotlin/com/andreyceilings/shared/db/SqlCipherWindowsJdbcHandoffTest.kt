package com.andreyceilings.shared.db

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherJdbcProperties
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SqlCipherWindowsJdbcHandoffTest {
    @Test
    fun sequentialHandoffPreservesCommitRollbackAndRecoversFromSqlErrors() = onWindows { file, key ->
        val firstOwner = Thread.currentThread()
        Executors.newSingleThreadExecutor().use { worker ->
            open(file, key).use { connection ->
                connection.exec("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                connection.exec(
                    """
                    CREATE TABLE child (
                        id INTEGER PRIMARY KEY,
                        parent_id INTEGER REFERENCES parent(id) DEFERRABLE INITIALLY DEFERRED
                    )
                    """.trimIndent()
                )
                connection.exec("BEGIN TRANSACTION")
                connection.exec("INSERT INTO parent VALUES (1)")
                connection.exec("COMMIT")

                // Future completion publishes the state before the test thread uses it again.
                worker.runAndAwait {
                    assertNotSame(firstOwner, Thread.currentThread())
                    assertEquals(1L, connection.scalar("SELECT count(*) FROM parent"))
                    assertEquals(1L, connection.scalar("PRAGMA foreign_keys"))
                    connection.exec("BEGIN TRANSACTION")
                    assertFailsWith<SQLException> { connection.exec("BEGIN TRANSACTION") }
                    connection.exec("INSERT INTO child VALUES (1, 99)")
                    // The FK is deferred. The failing COMMIT must leave a transaction to roll back.
                    assertFailsWith<SQLException> { connection.exec("COMMIT") }
                    connection.exec("ROLLBACK")
                    assertFailsWith<SQLException> { connection.exec("ROLLBACK") }
                }

                assertEquals(0L, connection.scalar("SELECT count(*) FROM child"))
                // Preparation succeeds. The overflow occurs at sqlite3_step with an open rowset.
                connection.prepareStatement("SELECT abs(-9223372036854775808)").use { statement ->
                    val rows = statement.executeQuery()
                    assertFailsWith<SQLException> {
                        rows.use {
                            it.next()
                            error("Expected sqlite3_step to report integer overflow")
                        }
                    }
                }
                connection.exec("BEGIN TRANSACTION")
                connection.exec("INSERT INTO child VALUES (2, 1)")
                connection.exec("COMMIT")

                worker.runAndAwait {
                    assertEquals(2L, connection.scalar("SELECT id FROM child"))
                    connection.exec("BEGIN TRANSACTION")
                    connection.exec("DELETE FROM child")
                    connection.exec("ROLLBACK")
                }
                assertEquals(2L, connection.scalar("SELECT id FROM child"))
            }
        }
    }

    @Test
    fun repeatedHandoffsPreserveConnectionLocalStateAndCloseQueryResources() = onWindows { file, key ->
        val firstOwner = Thread.currentThread()
        Executors.newSingleThreadExecutor().use { worker ->
            open(file, key).use { connection ->
                // A second physical connection could not read this connection-local table.
                connection.exec("CREATE TEMP TABLE handoff_counter (value INTEGER NOT NULL)")
                connection.exec("INSERT INTO handoff_counter VALUES (0)")

                repeat(32) { round ->
                    val expected = round.toLong()
                    worker.runAndAwait {
                        assertNotSame(firstOwner, Thread.currentThread())
                        connection.exec("BEGIN TRANSACTION")
                        val statement = connection.prepareStatement("SELECT value FROM handoff_counter")
                        statement.use {
                            val rows = it.executeQuery()
                            rows.use {
                                assertTrue(it.next())
                                assertEquals(expected, it.getLong(1))
                                assertFalse(it.next())
                            }
                            assertTrue(rows.isClosed)
                        }
                        assertTrue(statement.isClosed)
                        connection.exec("UPDATE handoff_counter SET value = -1")
                        connection.exec("ROLLBACK")
                    }
                    connection.exec("BEGIN TRANSACTION")
                    assertEquals(expected, connection.scalar("SELECT value FROM handoff_counter"))
                    connection.exec("UPDATE handoff_counter SET value = value + 1")
                    connection.exec("COMMIT")
                }
                assertEquals(32L, connection.scalar("SELECT value FROM handoff_counter"))
            }
        }
        Files.delete(file)
    }

    @Test
    fun profiledDriverClosesPhysicalConnectionsAfterSqlErrorAndRollback() = onWindows { file, key ->
        val metrics = SqlCipherWindowsConnectionMetrics()
        val driver = SqlCipherWindowsDriverFactory.openProfiled(file.toFile(), key, metrics)
        val database = object : TransacterImpl(driver) {}
        val countRows = {
            driver.executeQuery(null, "SELECT count(*) FROM proof", { cursor ->
                assertTrue(cursor.next().value)
                QueryResult.Value(cursor.getLong(0))
            }, 0).value
        }

        driver.use {
            it.execute(null, "CREATE TABLE proof (id INTEGER PRIMARY KEY)", 0)
            it.execute(null, "INSERT INTO proof VALUES (1)", 0)
            assertFailsWith<SQLException> {
                database.transaction {
                    it.execute(null, "INSERT INTO proof VALUES (2)", 0)
                    it.execute(null, "INSERT INTO proof VALUES (1)", 0)
                }
            }
            assertEquals(null, it.currentTransaction())
            assertEquals(1L, countRows())
        }

        assertTrue(metrics.physicalOpens.get() > 0)
        assertEquals(metrics.physicalOpens.get(), metrics.physicalCloses.get())
        assertEquals(metrics.physicalOpens.get(), metrics.encryptedSchemaGuardAttempts.get())
        assertEquals(metrics.physicalOpens.get(), metrics.encryptedSchemaGuardsPassed.get())
        assertEquals(2L, metrics.statementsOnPinnedTransaction.get())
        val opensAfterClose = metrics.physicalOpens.get()
        assertFailsWith<IllegalStateException> { countRows() }
        assertEquals(opensAfterClose, metrics.physicalOpens.get())
        Files.delete(file)
    }

    @Test
    fun closeAfterHandoffRollsBackUncommittedWritesAndReleasesWalFiles() = onWindows { file, key ->
        val firstOwner = Thread.currentThread()
        Executors.newSingleThreadExecutor().use { worker ->
            open(file, key).use { connection ->
                assertEquals("wal", connection.read("PRAGMA journal_mode=WAL") { getString(1) })
                connection.exec("CREATE TABLE proof (id INTEGER PRIMARY KEY)")
                connection.exec("INSERT INTO proof VALUES (1)")

                worker.runAndAwait {
                    assertNotSame(firstOwner, Thread.currentThread())
                    connection.exec("BEGIN TRANSACTION")
                    connection.exec("INSERT INTO proof VALUES (2)")
                    assertEquals(2L, connection.scalar("SELECT count(*) FROM proof"))
                    connection.close()
                    assertTrue(connection.isClosed)
                    assertFailsWith<SQLException> { connection.scalar("SELECT count(*) FROM proof") }
                }
            }
        }

        assertFalse(Files.exists(file.resolveSibling("${file.fileName}-wal")))
        assertFalse(Files.exists(file.resolveSibling("${file.fileName}-shm")))
        val moved = Files.move(file, file.resolveSibling("reopened.db"))
        Files.newInputStream(moved).use {
            val header = it.readNBytes(16)
            assertEquals(16, header.size)
            assertFalse(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
        }
        open(moved, key).use { reopened ->
            assertEquals(1L, reopened.scalar("SELECT id FROM proof"))
            assertEquals(1L, reopened.scalar("SELECT count(*) FROM proof"))
        }
        Files.delete(moved)
    }

    private fun onWindows(block: (Path, ByteArray) -> Unit) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("windows-cipher-handoff-")
        val key = ByteArray(32) { 67 }
        AutoCloseable {
            key.fill(0)
            check(directory.toFile().deleteRecursively()) { "Could not remove SQLCipher test files" }
        }.use {
            block(directory.resolve("handoff.db"), key)
        }
    }

    private fun open(file: Path, key: ByteArray): Connection {
        val connectionKey = key.copyOf()
        val properties = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, connectionKey)
            put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
        }
        val connection = try {
            DriverManager.getConnection("jdbc:sqlcipher:${file.toAbsolutePath()}", properties)
        } finally {
            connectionKey.fill(0)
        }
        try {
            connection.exec("PRAGMA busy_timeout = 5000")
            connection.exec("PRAGMA foreign_keys = ON")
            assertEquals(1L, connection.scalar("PRAGMA foreign_keys"))
            val cipherVersion = connection.read("PRAGMA cipher_version") { getString(1) }
            assertFalse(cipherVersion.isNullOrBlank())
            assertTrue(connection.scalar("SELECT count(*) FROM sqlite_master") >= 0)
            return connection
        } catch (failure: Throwable) {
            try {
                connection.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private fun Connection.exec(sql: String) {
        prepareStatement(sql).use { it.execute() }
    }

    private fun Connection.scalar(sql: String): Long = read(sql) { getLong(1) }

    private fun <T> Connection.read(sql: String, value: ResultSet.() -> T): T =
        prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.value()
            }
        }

    private fun Executor.runAndAwait(block: () -> Unit) {
        try {
            CompletableFuture.runAsync({ block() }, this).join()
        } catch (failure: CompletionException) {
            throw failure.cause ?: failure
        }
    }
}
