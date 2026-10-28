package com.andreyceilings.poc

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherJdbcProperties
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Disposable integration proof. This module is intentionally not included in the
 * root Gradle build and must never open app/production database files.
 */
fun main() {
    val dbFile = File("build/sqlcipher-sqldelight-proof.db").absoluteFile
    dbFile.parentFile.mkdirs()
    dbFile.delete()
    val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    try {
        val props = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, key.copyOf())
        }

        JdbcSqliteDriver("jdbc:sqlcipher:${dbFile.absolutePath}", props).use { driver ->
            val cipherVersion = driver.executeQuery(
                null,
                "PRAGMA cipher_version;",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                0,
            ).value
            check(!cipherVersion.isNullOrBlank()) { "SQLCipher probe returned no version" }

            driver.execute(null, "CREATE TABLE proof(id INTEGER PRIMARY KEY, value TEXT NOT NULL);", 0)
            driver.execute(null, "INSERT INTO proof(id, value) VALUES (1, 'encrypted-sqldelight-ok');", 0)
            val stored = driver.executeQuery(
                null,
                "SELECT value FROM proof WHERE id = 1;",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                0,
            ).value
            check(stored == "encrypted-sqldelight-ok") { "SQLDelight JDBC CRUD failed" }
        }

        val bytes = dbFile.readBytes()
        check(bytes.size > 16) { "Encrypted database file is unexpectedly short" }
        val plainHeader = "SQLite format 3\u0000".encodeToByteArray()
        check(!bytes.copyOfRange(0, plainHeader.size).contentEquals(plainHeader)) {
            "Database has a plaintext SQLite header"
        }
        val beforeWrongKey = sha256(dbFile)

        val wrongKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        check(!wrongKey.contentEquals(key)) { "Test keys unexpectedly match" }
        var wrongKeyRejected = false
        try {
            val wrongProps = Properties().apply {
                put(SqlCipherJdbcProperties.KEY_BYTES, wrongKey)
            }
            JdbcSqliteDriver("jdbc:sqlcipher:${dbFile.absolutePath}", wrongProps).use { driver ->
                driver.executeQuery(
                    null,
                    "SELECT value FROM proof WHERE id = 1;",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    0,
                ).value
            }
        } catch (_: Exception) {
            wrongKeyRejected = true
        } finally {
            wrongKey.fill(0)
        }
        check(wrongKeyRejected) { "Database opened successfully with an incorrect key" }
        check(beforeWrongKey == sha256(dbFile)) { "Wrong-key attempt modified the database file" }

        JdbcSqliteDriver(
            "jdbc:sqlcipher:${dbFile.absolutePath}",
            Properties().apply { put(SqlCipherJdbcProperties.KEY_BYTES, key.copyOf()) },
        ).use { driver ->
            val stored = driver.executeQuery(
                null,
                "SELECT value FROM proof WHERE id = 1;",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                0,
            ).value
            check(stored == "encrypted-sqldelight-ok") { "Correct-key reopen did not preserve data" }
        }

        println("PASS: SQLDelight 2.4.1 + SQLCipher JDBC 0.2.13")
        println("PASS: cipher_version=$cipherVersion, encrypted header, CRUD, reopen, wrong-key rejection, unchanged file")
    } finally {
        key.fill(0)
        dbFile.delete()
    }
}

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
