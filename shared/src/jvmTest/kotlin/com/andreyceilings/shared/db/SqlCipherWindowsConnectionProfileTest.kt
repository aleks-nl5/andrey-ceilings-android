package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherJdbcProperties
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Diagnostic-only benchmark on a REAL encrypted Windows SQLCipher database.
 * No pass/fail time threshold, private SQL payload, database content, or key logging.
 * Not a replacement for wrong-key, rollback, migration, or concurrent writer tests.
 */
class SqlCipherWindowsConnectionProfileTest {
    @Test fun profilePhysicalConnectionAndTransactionCost() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        val dir = Files.createTempDirectory("windows-cipher-profile-").toFile()
        val file = File(dir, "probe.db")
        val key = ByteArray(32) { 63 } // Disposable test fixture; never logged.
        val samples = 8
        val labels = listOf(
            "DriverManager physical connect", "PRAGMA busy_timeout", "PRAGMA foreign_keys ON",
            "Verify foreign_keys", "Verify cipher_version", "Verify encrypted schema",
            "Physical close", "SQLDelight outside transaction", "SQLDelight inside transaction"
        )
        val elapsed = List(labels.size) { mutableListOf<Long>() }

        try {
            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                driver.execute(null, "CREATE TABLE profile_fixture (id INTEGER PRIMARY KEY)", 0)
            }

            // Match production getConnection() setup/checks on every physical
            // JDBC open while measuring each phase independently.
            repeat(samples) {
                val connectionKey = key.copyOf()
                val props = Properties().apply {
                    put(SqlCipherJdbcProperties.KEY_BYTES, connectionKey)
                    put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
                }
                try {
                    val connection = time(elapsed[0]) {
                        DriverManager.getConnection("jdbc:sqlcipher:${file.absolutePath}", props)
                    }
                    try {
                        time(elapsed[1]) {
                            connection.prepareStatement("PRAGMA busy_timeout = 5000").use { it.execute() }
                        }
                        time(elapsed[2]) {
                            connection.prepareStatement("PRAGMA foreign_keys = ON").use { it.execute() }
                        }
                        val fk = time(elapsed[3]) {
                            connection.prepareStatement("PRAGMA foreign_keys").use { stmt ->
                                stmt.executeQuery().use { if (it.next()) it.getInt(1) else null }
                            }
                        }
                        assertTrue(fk == 1, "Each physical connection must enforce foreign keys")
                        val version = time(elapsed[4]) {
                            connection.prepareStatement("PRAGMA cipher_version").use { stmt ->
                                stmt.executeQuery().use { if (it.next()) it.getString(1) else null }
                            }
                        }
                        assertFalse(version.isNullOrBlank(), "SQLCipher must be active on each connection")
                        val n = time(elapsed[5]) {
                            connection.prepareStatement("SELECT count(*) FROM sqlite_master").use { stmt ->
                                stmt.executeQuery().use { if (it.next()) it.getLong(1) else null }
                            }
                        }
                        assertTrue(n != null && n > 0, "Encrypted schema guard must succeed")
                    } finally {
                        time(elapsed[6]) { connection.close() }
                    }
                } finally {
                    connectionKey.fill(0)
                }
            }

            SqlCipherWindowsDriverFactory.open(file, key).use { driver ->
                repeat(samples) {
                    assertTrue(time(elapsed[7]) { tableCount(driver) } > 0L)
                }
                // SQLDelight pins one connection for the entire transaction.
                PrototypeDatabase(driver).transaction {
                    repeat(samples) {
                        assertTrue(time(elapsed[8]) { tableCount(driver) } > 0L)
                    }
                }
            }

            val summary = buildString {
                appendLine("## Real Windows SQLCipher JDBC timings (diagnostic only)")
                appendLine()
                appendLine("Encrypted DB; $samples samples/phase; milliseconds; no time-based pass/fail.")
                appendLine()
                appendLine("| Stage | N | Total ms | Median ms | Max ms |")
                appendLine("| --- | ---: | ---: | ---: | ---: |")
                labels.forEachIndexed { i, label ->
                    val sorted = elapsed[i].sorted()
                    val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0 / 1_000_000.0
                    appendLine("| $label | ${sorted.size} | ${format(sorted.sum() / 1_000_000.0)} | " +
                        "${format(median)} | ${format(sorted.last() / 1_000_000.0)} |")
                }
            }
            // Captured in JUnit XML even if Gradle test stdout is hidden.
            println(summary)
            System.getenv("RUNNER_TEMP")?.let { temp ->
                runCatching { File(temp, "windows-sqlcipher-jdbc-profile.md").writeText(summary) }
            }
        } finally {
            key.fill(0)
            dir.deleteRecursively()
        }
    }

    private fun tableCount(driver: SqlDriver): Long = assertNotNull(
        driver.executeQuery(null, "SELECT count(*) FROM sqlite_master",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0).value
    )

    private fun format(ms: Double): String = String.format(Locale.ROOT, "%.1f", ms)

    private inline fun <T> time(bucket: MutableList<Long>, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            bucket.add(System.nanoTime() - start)
        }
    }
}
