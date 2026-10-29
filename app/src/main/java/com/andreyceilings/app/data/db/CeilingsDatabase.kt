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
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import java.io.Closeable
import java.io.File

/**
 * Android SQLDelight database entry point. The name is kept for existing UI
 * call sites, but this type no longer extends RoomDatabase and contains no Room.
 *
 * A new filename prevents downgrading Room's schema v2 to SQLDelight v1.
 * The old encrypted file is retained until a verified, ID-preserving import
 * has finished; a failed conversion NEVER starts with an empty replacement.
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

    fun clients() = clientDao
    fun projects() = projectDao
    fun measurements() = measurementDao
    fun priceItems() = priceDao
    fun estimates() = estimateDao

    /** Minimal diagnostic API used by existing Settings and backup UI. */
    val openHelper = DatabaseHelper(this)
    val invalidationTracker = BusinessChangeTracker(database)

    override fun close() {
        invalidationTracker.close()
        driver.close()
    }

    internal fun execute(sql: String) { driver.execute(null, sql, 0) }

    internal fun queryLong(sql: String): Long? = driver.executeQuery(
        null, sql, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        }, 0
    ).value

    internal fun rawQuery(sql: String): DatabaseCursor {
        val columns = when {
            "wal_checkpoint" in sql.lowercase() -> 3
            // Settings' combined summary of five subqueries.
            "SELECT COUNT(*) FROM clients" in sql && "SELECT COUNT(*) FROM estimates" in sql -> 5
            else -> 1
        }
        val rows = driver.executeQuery(null, sql, { cursor ->
            val values = mutableListOf<List<String?>>()
            while (cursor.next().value) {
                values.add((0 until columns).map { cursor.getString(it) })
            }
            QueryResult.Value(values)
        }, 0).value
        return DatabaseCursor(rows)
    }

    companion object {
        const val DATABASE_NAME = "andrey_ceilings_sqldelight.db"
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
            val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
            val key = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                // No silent destructive fallback. Existing legacy databases are imported
                // offline and only published after an integrity-checked transaction.
                if (!live.exists() && legacy.exists()) {
                    LegacyRoomDatabaseImporter.migrateToNewDatabase(context, legacy, live, key)
                }
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

/** A tiny read-only cursor used by SQLCipher diagnostics; it is not Room/SQLiteOpenHelper. */
internal class DatabaseCursor(private val rows: List<List<String?>>) : Closeable {
    private var position = -1
    fun moveToFirst(): Boolean { position = if (rows.isNotEmpty()) 0 else -1; return position == 0 }
    fun getString(index: Int): String? = rows[position][index]
    fun getLong(index: Int): Long = getString(index)?.toLongOrNull() ?: 0L
    override fun close() = Unit
}

internal class DatabaseHelper(private val owner: CeilingsDatabase) {
    val writableDatabase get() = DatabaseConnection(owner)
    val readableDatabase get() = DatabaseConnection(owner)
}

internal class DatabaseConnection(private val owner: CeilingsDatabase) {
    val path: String get() = owner.path
    fun query(sql: String): DatabaseCursor = owner.rawQuery(sql)
}

internal class BusinessChangeTracker(private val database: PrototypeDatabase) : Closeable {
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
