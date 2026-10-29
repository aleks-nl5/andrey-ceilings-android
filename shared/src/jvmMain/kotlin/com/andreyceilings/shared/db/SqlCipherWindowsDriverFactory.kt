package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherJdbcProperties
import java.io.File
import java.util.Properties

/**
 * Windows/JVM SQLCipher-backed SQLDelight driver.
 *
 * Caller owns the key and must retain it to reopen the same database.
 * This is an opt-in migration foundation; production repositories still use Room.
 */
object SqlCipherWindowsDriverFactory {
    fun open(databaseFile: File, key: ByteArray): SqlDriver {
        require(key.size == 32) { "SQLCipher key must be 32 bytes" }
        databaseFile.absoluteFile.parentFile?.mkdirs()
        val properties = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, key.copyOf())
            // Required by the verified SQLCipherMultiplatform 0.2.13 JDBC integration.
            put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
        }
        return JdbcSqliteDriver("jdbc:sqlcipher:${databaseFile.absolutePath}", properties).also { driver ->
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val enabled = driver.executeQuery(null, "PRAGMA foreign_keys",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                0
            ).value
            check(enabled == 1L) { "SQLCipher foreign keys disabled: $enabled" }
        }
    }
}
