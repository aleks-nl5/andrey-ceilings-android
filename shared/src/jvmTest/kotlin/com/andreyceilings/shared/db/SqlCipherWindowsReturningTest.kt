package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.nio.file.Files
import java.sql.SQLException
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
