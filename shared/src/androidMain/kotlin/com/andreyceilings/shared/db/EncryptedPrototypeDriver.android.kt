package com.andreyceilings.shared.db

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

enum class EncryptedDatabaseOpenStage { CREATE, MIGRATE, OPEN, SCHEMA, WAL, CORRUPTION }
class EncryptedDatabaseOpenFailure(val stage: EncryptedDatabaseOpenStage, cause: Throwable,
    val fromVersion: Long? = null, val toVersion: Long? = null) : IllegalStateException("Encrypted database open failed: ${stage.name}", cause)

private inline fun <T> openStep(stage: EncryptedDatabaseOpenStage, block: () -> T): T = try { block() }
catch (failure: Throwable) { if (failure is EncryptedDatabaseOpenFailure) throw failure else throw EncryptedDatabaseOpenFailure(stage,failure) }

/**
 * SQLDelight + SQLCipher driver shared by Android production and isolated
 * instrumentation databases. Production deliberately enables WAL at its
 * single opening boundary; old DELETE-mode databases upgrade on next open.
 *
 * The caller supplies the passphrase; never fall back to plaintext or invent
 * a replacement key on a wrong passphrase. WAL is always verified at open.
 */
fun createEncryptedPrototypeDriver(
    context: Context,
    passphrase: ByteArray,
    databaseName: String = "andrey_ceilings_sqldelight_prototype.db",
    enableWalForTesting: Boolean = false,
    initializePriceCatalog: Boolean = false
): SqlDriver {
    require(passphrase.size == 32) { "Expected a 256-bit database passphrase" }
    require(databaseName.isNotBlank() && '/' !in databaseName && '\\' !in databaseName) {
        "Expected a database filename, not a path"
    }
    System.loadLibrary("sqlcipher")
    val schema = if (initializePriceCatalog) PriceCatalogSchema else PrototypeDatabase.Schema
    val observedSchema = object : SqlSchema<QueryResult.Value<Unit>> by schema {
        override fun create(driver: SqlDriver) = openStep(EncryptedDatabaseOpenStage.CREATE) { schema.create(driver) }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
            try {
                if(oldVersion in 8L..10L)verifyPointPlacementStructure(driver,oldVersion)
                schema.migrate(driver,oldVersion,newVersion,*callbacks)
            }
            catch (failure: Throwable) { throw EncryptedDatabaseOpenFailure(EncryptedDatabaseOpenStage.MIGRATE,failure,oldVersion,newVersion) }
    }
    val driver = AndroidSqliteDriver(
        schema = observedSchema,
        callback = object : AndroidSqliteDriver.Callback(observedSchema) {
            override fun onCorruption(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Android's default handler deletes corrupt DB/WAL files. Preserve them for
                // explicit, staged recovery; an open failure must never become an auto-reset.
                throw EncryptedDatabaseOpenFailure(EncryptedDatabaseOpenStage.CORRUPTION,
                    IllegalStateException("Encrypted database corruption reported; original files preserved"))
            }
        },
        // The fourth argument enables SQLCipher's WAL-aware OPEN HELPER,
        // including the correct connection-pool behavior. PRAGMA alone
        // does not activate that pool. Production explicitly opts into WAL
        // at CeilingsDatabase.build; some disposable tests still use DELETE.
        factory = SupportOpenHelperFactory(passphrase.copyOf(), null, enableWalForTesting),
        context = context.applicationContext,
        name = databaseName
    )
    try {
        // SQLDelight does not automatically inherit Room's onConfigure() hook.
        openStep(EncryptedDatabaseOpenStage.OPEN) { driver.execute(null, "PRAGMA foreign_keys = ON", 0) }
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
        openStep(EncryptedDatabaseOpenStage.SCHEMA) {
            verifyMeasurementElementStructure(driver)
            verifyPointPlacementStructure(driver)
        }
        if (enableWalForTesting) {
            // A manual PRAGMA journal_mode=WAL does not enable SQLCipher's
            // WAL-aware open-helper pool. The mode MUST already be WAL on
            // every open, including production, or fail closed without
            // returning a DB handle to the app.
            val journalMode = driver.executeQuery(null, "PRAGMA journal_mode",
                { cursor -> QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) else null
                ) },
                0
            ).value?.lowercase()
            openStep(EncryptedDatabaseOpenStage.WAL) { check(journalMode == "wal") {
                "SQLCipher WAL helper failed to enable WAL (journal_mode=$journalMode)"
            } }
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
