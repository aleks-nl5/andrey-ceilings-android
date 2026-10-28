package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
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
        return JdbcSqliteDriver("jdbc:sqlcipher:${databaseFile.absolutePath}", properties)
    }
}
