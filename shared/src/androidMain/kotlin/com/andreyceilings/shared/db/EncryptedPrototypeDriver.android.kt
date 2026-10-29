package com.andreyceilings.shared.db

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Android SQLDelight + SQLCipher driver for disposable migration-stage databases.
 *
 * The caller supplies the passphrase. Never generates a replacement key or falls
 * back to plaintext on a wrong key. Production Room remains unchanged until the
 * backup/restore and key-manager integration gates are complete.
 */
fun createEncryptedPrototypeDriver(
    context: Context,
    passphrase: ByteArray,
    databaseName: String = "andrey_ceilings_sqldelight_prototype.db"
): SqlDriver {
    require(passphrase.size == 32) { "Expected a 256-bit database passphrase" }
    require(databaseName.isNotBlank() && '/' !in databaseName && '\\' !in databaseName) {
        "Expected a database filename, not a path"
    }
    System.loadLibrary("sqlcipher")
    val driver = AndroidSqliteDriver(
        schema = PrototypeDatabase.Schema,
        factory = SupportOpenHelperFactory(passphrase.copyOf()),
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
