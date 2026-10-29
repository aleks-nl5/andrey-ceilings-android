package com.andreyceilings.app.data.db

import android.content.Context
import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.shared.db.ClientRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import java.io.Closeable
import java.io.File

/**
 * Android SQLDelight entry point. No Room compatibility database/driver facade.
 * This test-cycle schema uses a new filename; old disposable files are left untouched.
 */
internal class CeilingsDatabase private constructor(
    internal val driver: SqlDriver,
    internal val path: String,
) : Closeable {
    internal val database = PrototypeDatabase(driver)
    private val clientDao = ClientDao(ClientRepository(database))
    private val projectDao = ProjectDao(ProjectRepository(database))
    private val measurementDao = RoomMeasurementDao(MeasurementRepository(database))
    private val priceDao = PriceItemDao(PriceRepository(database))
    private val estimateDao = EstimateDao(EstimateRepository(database))
    private val numberingSettingsDao = EstimateNumberSettingsDao(ImmutableEstimateRepository(database))

    fun clients() = clientDao
    fun projects() = projectDao
    fun measurements() = measurementDao
    fun priceItems() = priceDao
    fun estimates() = estimateDao
    fun estimateNumberSettings() = numberingSettingsDao

    val changeTracker = SqlDelightChangeTracker(database)

    override fun close() {
        changeTracker.close()
        driver.close()
    }

    internal fun execute(sql: String) { driver.execute(null, sql, 0) }

    fun queryLong(sql: String): Long? = driver.executeQuery(null, sql, { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
    }, 0).value

    fun queryText(sql: String): String? = driver.executeQuery(null, sql, { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
    }, 0).value

    data class WalCheckpoint(val busy: Long, val frames: Long, val checkpointed: Long) {
        val complete get() = busy == 0L && (frames < 0L || checkpointed >= frames)
    }

    enum class WalMode { PASSIVE, FULL }

    fun checkpoint(mode: WalMode): WalCheckpoint = driver.executeQuery(
        null, "PRAGMA wal_checkpoint(${mode.name})", { cursor ->
            check(cursor.next().value) { "SQLite did not return a WAL checkpoint result" }
            QueryResult.Value(WalCheckpoint(
                cursor.getLong(0) ?: -1L,
                cursor.getLong(1) ?: -1L,
                cursor.getLong(2) ?: -1L
            ))
        }, 0
    ).value

    fun checkIntegrity(): Boolean =
        queryText("PRAGMA integrity_check").equals("ok", ignoreCase = true)

    companion object {
        const val DATABASE_NAME = "andrey_ceilings_sqldelight_v2.db"
        const val LEGACY_DATABASE_NAME = "andrey_ceilings_secure.db"

        @Volatile private var INSTANCE: CeilingsDatabase? = null

        fun get(context: Context): CeilingsDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }

        /**
         * Only for instrumentation tests. Does not open the user's live database.
         * The caller must delete the isolated file after the session is closed.
         */
        internal fun openIsolatedForTesting(
            context: Context, filename: String, key: ByteArray
        ): CeilingsDatabase {
            require(filename.startsWith("sqldelight_test_") && filename.endsWith(".db"))
            val driver = createEncryptedPrototypeDriver(context, key, filename)
            return CeilingsDatabase(driver, context.getDatabasePath(filename).absolutePath)
        }

        fun closeAndReset() = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }

        private fun build(context: Context): CeilingsDatabase {
            val live = context.getDatabasePath(DATABASE_NAME)
            val key = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                // No migration of disposable test data in PR #22.
                // A new filename creates a clean encrypted database, leaving earlier files intact.
                val driver = createEncryptedPrototypeDriver(context, key, DATABASE_NAME)
                return try {
                    val result = CeilingsDatabase(driver, live.absolutePath)
                    // Force an encrypted-schema read before returning to the UI.
                    check((result.queryLong("SELECT count(*) FROM sqlite_master") ?: 0L) > 0L)
                    result
                } catch (failure: Throwable) {
                    driver.close()
                    throw failure
                }
            } finally {
                key.fill(0)
            }
        }
    }
}

internal class SqlDelightChangeTracker(private val database: PrototypeDatabase) : Closeable {
    abstract class Observer(vararg val tables: String) {
        abstract fun onInvalidated(tables: Set<String>)
    }

    private val observers = linkedMapOf<Observer, List<Pair<Query<*>, Query.Listener>>>()
    private val queries = mapOf(
        "clients" to database.businessSchemaQueries.selectClients(),
        "projects" to database.businessSchemaQueries.selectProjects(),
        "room_measurements" to database.businessSchemaQueries.selectAllMeasurements(),
        "price_items" to database.businessSchemaQueries.selectPriceItems(),
        "estimates" to database.businessSchemaQueries.selectAllEstimates()
    )

    @Synchronized fun addObserver(observer: Observer) {
        if (observers.containsKey(observer)) return
        observers[observer] = observer.tables.mapNotNull { table ->
            queries[table]?.let { query ->
                val listener = Query.Listener { observer.onInvalidated(setOf(table)) }
                query.addListener(listener)
                query to listener
            }
        }
    }

    @Synchronized fun removeObserver(observer: Observer) {
        observers.remove(observer)?.forEach { (query, listener) -> query.removeListener(listener) }
    }

    @Synchronized override fun close() {
        observers.keys.toList().forEach(::removeObserver)
    }
}
