package com.andreyceilings.shared.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.yet.sqlcipher.NativeSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherKey
import com.yet.sqlcipher.SqlCipherRecovery
import kotlin.random.Random
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.get
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Isolated iOS Simulator proof only. Uses disposable names and synthetic keys;
 * it does not touch Room or any production database/key manager.
 */
@OptIn(ExperimentalForeignApi::class)
class SqlCipherIosProofTest {
    private val factory = NativeSqlCipherDriverFactory()

    private object TestSchema : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE IF NOT EXISTS proof(k TEXT PRIMARY KEY, v TEXT NOT NULL);", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    private fun config(name: String, byte: Byte) = SqlCipherConfig(
        name = name,
        key = SqlCipherKey { ByteArray(32) { byte } },
        recovery = SqlCipherRecovery.Fail,
    )

    private fun freshName() = "andrey-ios-cipher-proof-${Random.nextLong().toString(16)}.db"

    @Test
    fun cipherIsLinkedAndDataReopensWithCorrectKey() = runBlocking {
        val name = freshName()
        var databasePath: String? = null
        try {
            factory.create(TestSchema, config(name, 11)).use { driver ->
                val cipherVersion = driver.executeQuery(
                    null,
                    "PRAGMA cipher_version;",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    0,
                ).value
                assertTrue(!cipherVersion.isNullOrEmpty(), "SQLCipher must be linked; plain SQLite is not acceptable")
                databasePath = databaseFilePath(driver)
                driver.execute(null, "INSERT INTO proof(k, v) VALUES ('probe', 'persisted');", 0)
            }

            val encrypted = readDatabaseBytes(requireNotNull(databasePath))
            assertEncryptedHeader(encrypted)

            factory.create(TestSchema, config(name, 11)).use { driver ->
                val value = driver.executeQuery(
                    null,
                    "SELECT v FROM proof WHERE k = 'probe';",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    0,
                ).value
                assertEquals("persisted", value)
            }
        } finally {
            factory.deleteDatabase(name)
        }
    }

    @Test
    fun wrongKeyFailsClosed(): Unit = runBlocking {
        val name = freshName()
        try {
            var databasePath: String? = null
            factory.create(TestSchema, config(name, 21)).use { driver ->
                databasePath = databaseFilePath(driver)
                driver.execute(null, "INSERT INTO proof(k, v) VALUES ('wrong-key', 'must-survive');", 0)
            }
            val path = requireNotNull(databasePath)
            val before = readDatabaseBytes(path)
            assertEncryptedHeader(before)
            assertFails {
                runBlocking { factory.create(TestSchema, config(name, 22)).close() }
            }
            assertTrue(before.contentEquals(readDatabaseBytes(path)), "Wrong-key attempt changed encrypted database bytes")
            factory.create(TestSchema, config(name, 21)).use { driver ->
                val value = driver.executeQuery(null, "SELECT v FROM proof WHERE k = 'wrong-key';",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0).value
                assertEquals("must-survive", value)
            }
        } finally {
            factory.deleteDatabase(name)
        }
        Unit
    }

    private fun databaseFilePath(driver: SqlDriver): String =
        driver.executeQuery(null, "PRAGMA database_list;",
            { cursor ->
                var path: String? = null
                while (cursor.next().value) {
                    if (cursor.getString(1) == "main") {
                        path = cursor.getString(2)
                        break
                    }
                }
                QueryResult.Value(path)
            }, 0).value?.takeIf { it.isNotBlank() }
            ?: error("SQLCipher did not report an on-disk main database path")

    private fun readDatabaseBytes(path: String): ByteArray {
        val data = requireNotNull(NSData.dataWithContentsOfFile(path)) {
            "Cannot read iOS SQLCipher database file: $path"
        }
        val pointer = requireNotNull(data.bytes) { "Database file has no bytes" }.reinterpret<kotlinx.cinterop.ByteVar>()
        return ByteArray(data.length.toInt()) { index -> pointer[index] }
    }

    private fun assertEncryptedHeader(bytes: ByteArray) {
        val header = "SQLite format 3\\u0000".encodeToByteArray()
        assertTrue(bytes.size > header.size, "Database file is too small")
        assertTrue(!bytes.copyOfRange(0, header.size).contentEquals(header),
            "Database has a plaintext SQLite header")
    }

    private inline fun SqlDriver.use(block: (SqlDriver) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
