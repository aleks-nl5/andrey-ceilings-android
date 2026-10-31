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

    /**
     * Consistent SQLCipher snapshot across concurrent commits, including WAL pages.
     * Never copy the live .db file or delete its -wal/-shm files to make a backup.
     * Caller must verify the resulting SQLCipher file and remove it when finished.
     */
    internal fun writeConsistentEncryptedSnapshot(destination: File) {
        val source = File(path)
        check(source.isFile && source.length() > 16L) {
            "Зашифрована БД відсутня або порожня."
        }
        check(source.canonicalFile != destination.canonicalFile) {
            "Не можна перезаписати робочу БД резервною копією."
        }
        check(destination.parentFile?.isDirectory == true && !destination.exists()) {
            "Для знімка потрібен новий файл у наявній папці."
        }
        try {
            // VACUUM INTO uses SQLCipher's live connection and creates a coherent
            // database image. Never run it inside a write transaction.
            val escaped = destination.absolutePath.replace("'", "''")
            execute("VACUUM INTO '$escaped'")
            check(destination.isFile && destination.length() > 16L) {
                "Не вдалося створити консистентний знімок БД."
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

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

    /**
     * Read-only diagnostics of this connection's effective SQLite/SQLCipher
     * settings and on-disk files. Do not change any PRAGMA here: Android's
     * WAL-aware helper owns connection initialization and checkpoint policy.
     */
    data class WalDiagnostics(
        val journalMode: String,
        val busyTimeoutMs: Long,
        val autoCheckpointPages: Long,
        val synchronousMode: Long,
        val pageSizeBytes: Long,
        val databaseBytes: Long,
        val walBytes: Long,
        val shmBytes: Long
    ) {
        val autoCheckpointApproxBytes: Long?
            get() = if (autoCheckpointPages > 0L && pageSizeBytes > 0L &&
                autoCheckpointPages <= Long.MAX_VALUE / pageSizeBytes) {
                autoCheckpointPages * pageSizeBytes
            } else null
    }

    fun walDiagnostics(): WalDiagnostics {
        val file = File(path)
        fun physicalBytes(path: File): Long =
            if (path.isFile) path.length().coerceAtLeast(0L) else 0L
        return WalDiagnostics(
            journalMode = queryText("PRAGMA journal_mode") ?: "невідомо",
            busyTimeoutMs = queryLong("PRAGMA busy_timeout") ?: -1L,
            autoCheckpointPages = queryLong("PRAGMA wal_autocheckpoint") ?: -1L,
            synchronousMode = queryLong("PRAGMA synchronous") ?: -1L,
            pageSizeBytes = queryLong("PRAGMA page_size") ?: -1L,
            databaseBytes = physicalBytes(file),
            walBytes = physicalBytes(File("$path-wal")),
            shmBytes = physicalBytes(File("$path-shm"))
        )
    }

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

        fun get(context: Context): CeilingsDatabase = ProductionDatabaseGate.read {
            synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Quiesce the production driver before replacing DB/WAL/SHM files.
         * Acquire the gate BEFORE the singleton monitor in every path, so
         * readers cannot deadlock an import while opening the database.
         */
        internal fun <T> withExclusiveRestoreLock(action: () -> T): T =
            ProductionDatabaseGate.restore { synchronized(this) { action() } }

        /**
         * Only for instrumentation tests. Does not open the user's live database.
         * The caller must delete the isolated file after the session is closed.
         */
        internal fun openIsolatedForTesting(
            context: Context, filename: String, key: ByteArray,
            enableWalForTesting: Boolean = false
        ): CeilingsDatabase {
            require(filename.startsWith("sqldelight_test_") && filename.endsWith(".db"))
            val driver = createEncryptedPrototypeDriver(context, key, filename, enableWalForTesting)
            return CeilingsDatabase(driver, context.getDatabasePath(filename).absolutePath)
        }

        fun closeAndReset() = ProductionDatabaseGate.restore {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun build(context: Context): CeilingsDatabase {
            // On an interrupted restore, never open or create an empty live DB.
            if (!com.andreyceilings.app.data.security.SqlCipherRestoreManager.installing) {
                com.andreyceilings.app.data.security.SqlCipherRestoreManager.recoverIfNeeded(context)
            }
            val live = context.getDatabasePath(DATABASE_NAME)
            val key = DatabaseKeyManager.getOrCreatePassphrase(context)
            try {
                // PR #29: switch only the Android production SQLCipher
                // opening boundary from DELETE to WAL; the helper configures
                // WAL-aware connections and verifies journal_mode=wal before
                // returning. Existing encrypted DELETE databases switch in
                // place without copying, rekeying or changing SQLDelight schema.
                // The 'ForTesting' option name is historical; the same verified
                // helper now powers WAL in this production entry point.
                val driver = ProductionDatabaseGate.wrap(
                    createEncryptedPrototypeDriver(
                        context, key, DATABASE_NAME, enableWalForTesting = true
                    )
                )
                return try {
                    val result = CeilingsDatabase(driver, live.absolutePath)
                    // Never silently fall back to DELETE after a restore or
                    // cold start. The shared open helper performs the same
                    // check before this production wrapper is constructed.
                    check(result.queryText("PRAGMA journal_mode")?.lowercase() == "wal") {
                        "Робоча SQLCipher БД не перейшла у WAL."
                    }
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
