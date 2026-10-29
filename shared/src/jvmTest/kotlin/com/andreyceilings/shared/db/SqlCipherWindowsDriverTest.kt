package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlCipherWindowsDriverTest {
    @Test
    fun encryptedDatabasePersistsAndRejectsWrongKeyWithoutModification() {
        // Windows native DLL is intentionally packaged only for the Windows JVM target.
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val dir = Files.createTempDirectory("andrey-windows-cipher-").toFile()
        val db = File(dir, "proof.db")
        val key = ByteArray(32) { 11 }
        try {
            SqlCipherWindowsDriverFactory.open(db, key).use { driver ->
                val version = driver.executeQuery(null, "PRAGMA cipher_version;",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0).value
                assertFalse(version.isNullOrBlank(), "SQLCipher must be linked")
                driver.execute(null, "CREATE TABLE proof(value TEXT NOT NULL);", 0)
                driver.execute(null, "INSERT INTO proof(value) VALUES ('persisted');", 0)
            }

            val before = db.readBytes()
            assertTrue(before.size > 16)
            val plainHeader = "SQLite format 3\u0000".encodeToByteArray()
            assertFalse(before.copyOfRange(0, plainHeader.size).contentEquals(plainHeader),
                "Database must not have a plaintext SQLite header")

            assertFails {
                SqlCipherWindowsDriverFactory.open(db, ByteArray(32) { 22 }).use { driver ->
                    driver.executeQuery(null, "SELECT value FROM proof;",
                        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0).value
                }
            }
            assertContentEquals(before, db.readBytes(), "Wrong-key attempt must not modify database")

            SqlCipherWindowsDriverFactory.open(db, key).use { driver ->
                val value = driver.executeQuery(null, "SELECT value FROM proof;",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0).value
                assertEquals("persisted", value)
            }
        } finally {
            dir.deleteRecursively()
            key.fill(0)
        }
    }

    @Test
    fun everyConnectionEnforcesForeignKeysAndTransactionsCommitOrRollback() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val directory = Files.createTempDirectory("andrey-windows-transactions-").toFile()
        val file = File(directory, "transactions.db")
        val key = ByteArray(32) { 53 }
        try {
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                PrototypeDatabase.Schema.create(driver)
                val database = PrototypeDatabase(driver)
                val clients = ClientRepository(database)

                // Each call is a separate physical connection outside a transaction.
                repeat(3) {
                    val foreignKeys = driver.executeQuery(null, "PRAGMA foreign_keys",
                        { cursor -> app.cash.sqldelight.db.QueryResult.Value(
                            if (cursor.next().value) cursor.getLong(0) else null
                        ) }, 0).value
                    assertEquals(1L, foreignKeys, "Connection ${it + 1} must enforce foreign keys")
                }

                // A generated SQLDelight transaction must share its connection.
                assertFails {
                    database.transaction {
                        clients.create("Rolled back", createdAt = 1)
                        assertEquals(1, clients.all().size)
                        error("force rollback")
                    }
                }
                assertTrue(clients.all().isEmpty(), "Rollback must remove uncommitted inserts")

                database.transaction {
                    clients.create("Committed", createdAt = 2)
                    assertEquals("Committed", clients.all().single().name)
                }
                assertEquals("Committed", clients.all().single().name)
            }
            // A new driver also needs to initialize foreign_keys for every connection.
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                val clients = ClientRepository(PrototypeDatabase(driver))
                assertEquals("Committed", clients.all().single().name)
                val foreignKeys = driver.executeQuery(null, "PRAGMA foreign_keys",
                    { cursor -> app.cash.sqldelight.db.QueryResult.Value(
                        if (cursor.next().value) cursor.getLong(0) else null
                    ) }, 0).value
                assertEquals(1L, foreignKeys)
            }
        } finally {
            key.fill(0)
            directory.deleteRecursively()
        }
    }
}
