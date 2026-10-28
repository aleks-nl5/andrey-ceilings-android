package com.andreyceilings.poc

import io.github.s0d3s.sqlcipher.multiplatform.api.SqlCipherDatabaseFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

private const val SQLITE_PLAINTEXT_HEADER = "SQLite format 3\u0000"

fun main() {
    val directory = Files.createTempDirectory("andrey-ceilings-sqlcipher-poc")
    val databasePath = directory.resolve("encrypted-test.db")
    val key = "poc-only-key-${UUID.randomUUID()}"
    val wrongKey = "$key-wrong"

    try {
        SqlCipherDatabaseFactory.initialize()

        val database = SqlCipherDatabaseFactory.open(databasePath.toString(), key)
        try {
            database.execute(
                "CREATE TABLE poc_records (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
            )
            database.execute("INSERT INTO poc_records(value) VALUES ('encrypted-persistence-check')")

            val cipherVersion = database.querySingleColumn("PRAGMA cipher_version")
            check(cipherVersion.firstOrNull()?.isNotBlank() == true) {
                "SQLCipher probe failed: PRAGMA cipher_version returned no version."
            }

            val rows = database.querySingleColumn(
                "SELECT value FROM poc_records WHERE id = 1"
            )
            check(rows == listOf("encrypted-persistence-check")) {
                "Could not read back the inserted test row."
            }
        } finally {
            database.close()
        }

        check(Files.exists(databasePath)) { "Database file was not created." }
        val encryptedBytes = Files.readAllBytes(databasePath)
        check(!startsWith(encryptedBytes, SQLITE_PLAINTEXT_HEADER.toByteArray(Charsets.US_ASCII))) {
            "Database file has the plaintext SQLite header; encryption was not verified."
        }
        val encryptedDigest = sha256(encryptedBytes)

        val reopenedDatabase = SqlCipherDatabaseFactory.open(databasePath.toString(), key)
        try {
            val rows = reopenedDatabase.querySingleColumn(
                "SELECT value FROM poc_records WHERE id = 1"
            )
            check(rows == listOf("encrypted-persistence-check")) {
                "The row did not persist after closing and reopening the database."
            }
        } finally {
            reopenedDatabase.close()
        }

        var wrongKeyRejected = false
        try {
            val wrongKeyDatabase = SqlCipherDatabaseFactory.open(databasePath.toString(), wrongKey)
            try {
                wrongKeyDatabase.querySingleColumn("SELECT count(*) FROM sqlite_master")
            } finally {
                wrongKeyDatabase.close()
            }
        } catch (_: Exception) {
            wrongKeyRejected = true
        }
        check(wrongKeyRejected) { "Wrong key was not rejected." }

        val bytesAfterWrongKey = Files.readAllBytes(databasePath)
        check(encryptedDigest.contentEquals(sha256(bytesAfterWrongKey))) {
            "Database file changed after wrong-key attempt; fail-closed behavior is not verified."
        }

        println("PASS: SQLCipher probe returned a version.")
        println("PASS: database file does not have the plaintext SQLite header.")
        println("PASS: CRUD and close/reopen persistence succeeded.")
        println("PASS: wrong key was rejected and the encrypted file remained unchanged.")
    } finally {
        deleteIfExists(databasePath)
        deleteIfExists(directory)
    }
}

private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
    bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun deleteIfExists(path: Path) {
    Files.deleteIfExists(path)
}
