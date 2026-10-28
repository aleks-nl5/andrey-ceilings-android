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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Isolated iOS Simulator proof only. Uses disposable names and synthetic keys;
 * it does not touch Room or any production database/key manager.
 */
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
        try {
            factory.create(TestSchema, config(name, 11)).use { driver ->
                val cipherVersion = driver.executeQuery(
                    null,
                    "PRAGMA cipher_version;",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    0,
                ).value
                assertTrue(!cipherVersion.isNullOrEmpty(), "SQLCipher must be linked; plain SQLite is not acceptable")
                driver.execute(null, "INSERT INTO proof(k, v) VALUES ('probe', 'persisted');", 0)
            }

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
    fun wrongKeyFailsClosed() = runBlocking {
        val name = freshName()
        try {
            factory.create(TestSchema, config(name, 21)).close()
            assertFails {
                runBlocking { factory.create(TestSchema, config(name, 22)).close() }
            }
        } finally {
            factory.deleteDatabase(name)
        }
    }

    private inline fun SqlDriver.use(block: (SqlDriver) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
