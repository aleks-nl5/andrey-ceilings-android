package com.andreyceilings.shared.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherJdbcProperties
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Windows/JVM SQLCipher-backed SQLDelight driver (migration foundation, not yet production).
 *
 * JdbcSqliteDriver is intentionally NOT used for jdbc:sqlcipher:. For on-disk URLs its
 * connection manager opens/closes physical JDBC connections between statements, so a PRAGMA
 * applied once in the factory is lost. Every connection must be configured before use.
 *
 * The caller owns [key]; this driver retains an independent copy until [SqlDriver.close].
 */
object SqlCipherWindowsDriverFactory {
    fun open(databaseFile: File, key: ByteArray): SqlDriver {
        require(key.size == 32) { "SQLCipher key must be 32 bytes" }
        databaseFile.absoluteFile.parentFile?.mkdirs()
        return ConfiguredSqlCipherJdbcDriver(
            url = "jdbc:sqlcipher:${databaseFile.absolutePath}",
            key = key
        ).also { it.verifyConnection() }
    }
}

/**
 * A fresh configured JDBC connection is used for each statement outside a transaction.
 * JdbcDriver pins one connection to the current thread for the duration of a transaction.
 *
 * This intentionally favors correctness and isolation over connection-pool performance.
 * Any future pooling implementation MUST initialize every newly created physical connection
 * (including foreign_keys) before it is made available to SQLDelight.
 */
private class ConfiguredSqlCipherJdbcDriver(
    private val url: String,
    key: ByteArray
) : JdbcDriver() {
    private val keyLock = Any()
    private val ownedKey = key.copyOf()
    @Volatile private var isClosed = false
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    fun verifyConnection() {
        val connection = getConnection()
        closeConnection(connection)
    }

    override fun getConnection(): Connection {
        val connectionKey = synchronized(keyLock) {
            check(!isClosed) { "Windows SQLCipher driver is closed" }
            ownedKey.copyOf()
        }
        val properties = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, connectionKey)
            // Required by SQLCipherMultiplatform JDBC 0.2.13.
            put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
        }
        try {
            val connection = DriverManager.getConnection(url, properties)
            try {
                // All PRAGMAs and schema/authentication checks run on THIS physical connection.
                connection.prepareStatement("PRAGMA foreign_keys = ON").use { it.execute() }
                connection.prepareStatement("PRAGMA foreign_keys").use { statement ->
                    statement.executeQuery().use { result ->
                        check(result.next() && result.getInt(1) == 1) {
                            "SQLCipher JDBC connection did not enable foreign keys"
                        }
                    }
                }
                connection.prepareStatement("PRAGMA cipher_version").use { statement ->
                    statement.executeQuery().use { result ->
                        check(result.next() && !result.getString(1).isNullOrBlank()) {
                            "SQLCipher is not active on this JDBC connection"
                        }
                    }
                }
                // Force reading the encrypted schema: reject a wrong key before returning.
                connection.prepareStatement("SELECT count(*) FROM sqlite_master").use { statement ->
                    statement.executeQuery().use { result ->
                        check(result.next()) { "SQLCipher schema validation returned no row" }
                    }
                }
                return connection
            } catch (failure: Throwable) {
                try {
                    connection.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        } finally {
            connectionKey.fill(0)
        }
    }

    override fun closeConnection(connection: Connection) {
        connection.close()
    }

    // Match the SQLite-specific transaction semantics used by JdbcSqliteDriver.
    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        try {
            prepareStatement("COMMIT").use { it.execute() }
        } finally {
            closeConnection(this)
        }
    }

    override fun Connection.rollbackTransaction() {
        try {
            prepareStatement("ROLLBACK TRANSACTION").use { it.execute() }
        } finally {
            closeConnection(this)
        }
    }

    override fun close() {
        synchronized(keyLock) {
            if (!isClosed) {
                isClosed = true
                ownedKey.fill(0)
            }
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = synchronized(listeners) {
            linkedSetOf<Query.Listener>().also { result ->
                queryKeys.forEach { listeners[it]?.let(result::addAll) }
            }
        }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }
}
