package com.andreyceilings.shared.db

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLDelight + SQLCipher driver shared by production Android (currently DELETE)
 * and isolated Android instrumentation databases.
 *
 * The caller supplies the passphrase; never fall back to plaintext or invent
 * a replacement key on a wrong passphrase. WAL opt-in remains test-only
 * until all restore and low-storage acceptance checks are complete.
 */
fun createEncryptedPrototypeDriver(
    context: Context,
    passphrase: ByteArray,
    databaseName: String = "andrey_ceilings_sqldelight_prototype.db",
    enableWalForTesting: Boolean = false
): SqlDriver {
    require(passphrase.size == 32) { "Expected a 256-bit database passphrase" }
    require(databaseName.isNotBlank() && '/' !in databaseName && '\\' !in databaseName) {
        "Expected a database filename, not a path"
    }
    System.loadLibrary("sqlcipher")
    val driver = AndroidSqliteDriver(
        schema = PrototypeDatabase.Schema,
        // Keep the production default (DELETE) unchanged. Only disposable
        // instrumentation databases opt into SQLCipher's open-helper WAL
        // connection management; PRAGMA alone does not enable its pool.
        factory = SupportOpenHelperFactory(passphrase.copyOf(), null, enableWalForTesting),
        context = context.applicationContext,
        name = databaseName
    )
    try {
        // SQLDelight does not automatically inherit Room's onConfigure() hook.
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val enabled = driver.executeQuery(null, "PRAGMA foreign_keys",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0
        ).value
        check(enabled == 1L) { "Encrypted Android SQLDelight database must enforce foreign keys" }
        // Fail closed on an existing file with the wrong key, before app repositories start.
        driver.executeQuery(null, "SELECT count(*) FROM sqlite_master",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0
        ).value
        if (enableWalForTesting) {
            // A manual PRAGMA journal_mode=WAL would not enable SQLCipher's
            // WAL-aware open-helper connection management. Observe the mode
            // already selected on open and fail closed if it is not WAL.
            val journalMode = driver.executeQuery(null, "PRAGMA journal_mode",
                { cursor -> QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) else null
                ) },
                0
            ).value?.lowercase()
            check(journalMode == "wal") {
                "SQLCipher WAL helper failed to enable WAL (journal_mode=$journalMode)"
            }
        }
        return driver
    } catch (failure: Throwable) {
        try {
            driver.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}
