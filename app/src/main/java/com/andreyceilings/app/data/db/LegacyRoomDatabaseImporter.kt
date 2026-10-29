package com.andreyceilings.app.data.db

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.cash.sqldelight.db.QueryResult
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Reads old encrypted Room-v2 files with the SQLCipher AndroidX SQLite helper,
 * NOT with Room runtime or Room-generated code. Originals are never overwritten.
 * This bridge is required for existing installs and legacy .stpb backup imports.
 */
internal object LegacyRoomDatabaseImporter {
    private fun readSnapshot(context: Context, source: File, key: ByteArray): LegacyRoomSnapshot {
        check(source.isFile && source.length() > 16L) { "Legacy database file is missing or empty" }
        check(source.parentFile?.canonicalPath == context.getDatabasePath("x.db").parentFile?.canonicalPath) {
            "Legacy snapshot must be staged inside the app's databases directory"
        }
        val helper = SupportOpenHelperFactory(key.copyOf()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(source.name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        error("Refusing to initialize an empty legacy database")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Unsupported legacy schema version: $oldVersion")
                    }
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Unsupported legacy schema version: $oldVersion")
                    }
                }).build()
        )
        try {
            val db = helper.readableDatabase
            val schemaVersion = db.query("PRAGMA user_version").use { cursor ->
                check(cursor.moveToFirst()) { "Legacy DB has no schema version" }
                cursor.getInt(0)
            }
            check(schemaVersion == 2) { "Expected Room schema v2, got $schemaVersion" }
            db.beginTransaction()
            try {
                val snapshot = LegacyRoomSnapshot(
                    clients = db.readRows("SELECT * FROM clients") { c ->
                        ClientEntity(
                            id = c.long("id"), name = c.string("name"),
                            phone = c.string("phone"), email = c.string("email"),
                            notes = c.string("notes"), createdAt = c.long("createdAt")
                        )
                    },
                    projects = db.readRows("SELECT * FROM projects") { c ->
                        ProjectEntity(
                            id = c.long("id"),
                            clientId = if (c.isNull(c.getColumnIndexOrThrow("clientId"))) null else c.long("clientId"),
                            title = c.string("title"), address = c.string("address"),
                            status = c.string("status"), notes = c.string("notes"),
                            createdAt = c.long("createdAt"), updatedAt = c.long("updatedAt")
                        )
                    },
                    measurements = db.readRows("SELECT * FROM room_measurements") { c ->
                        RoomMeasurementEntity(
                            id = c.long("id"), projectId = c.long("projectId"),
                            name = c.string("name"), shape = c.string("shape"),
                            lengthMeters = c.double("lengthMeters"), widthMeters = c.double("widthMeters"),
                            areaSquareMeters = c.double("areaSquareMeters"),
                            perimeterMeters = c.double("perimeterMeters"),
                            drawingJson = c.string("drawingJson"), notes = c.string("notes"),
                            createdAt = c.long("createdAt")
                        )
                    },
                    priceItems = db.readRows("SELECT * FROM price_items") { c ->
                        PriceItemEntity(
                            id = c.long("id"), code = c.string("code"), title = c.string("title"),
                            category = c.string("category"), unit = c.string("unit"),
                            price = c.double("price"), updatedAt = c.long("updatedAt")
                        )
                    },
                    estimates = db.readRows("SELECT * FROM estimates") { c ->
                        EstimateEntity(
                            id = c.long("id"), projectId = c.long("projectId"),
                            name = c.string("name"), material = c.string("material"),
                            profileType = c.string("profileType"), complexity = c.string("complexity"),
                            lengthMeters = c.double("lengthMeters"), widthMeters = c.double("widthMeters"),
                            areaSquareMeters = c.double("areaSquareMeters"),
                            perimeterMeters = c.double("perimeterMeters"), canvasCost = c.double("canvasCost"),
                            profileCost = c.double("profileCost"), complexityCost = c.double("complexityCost"),
                            optionsCost = c.double("optionsCost"), totalCost = c.double("totalCost"),
                            detailsJson = c.string("detailsJson"), createdAt = c.long("createdAt")
                        )
                    }
                )
                db.setTransactionSuccessful()
                return snapshot
            } finally {
                db.endTransaction()
            }
        } finally {
            helper.close()
        }
    }

    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.double(column: String) = getDouble(getColumnIndexOrThrow(column))
    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)) ?: ""

    private fun <T> SupportSQLiteDatabase.readRows(sql: String, mapper: (Cursor) -> T): List<T> =
        query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(mapper(cursor)) }
        }

    /**
     * Convert to a fresh encrypted file in the same directory. The live legacy
     * file remains untouched; a failed or interrupted attempt only removes the
     * reserved temporary SQLDelight database.
     */
    fun migrateToNewDatabase(context: Context, legacy: File, destination: File, key: ByteArray) {
        check(!destination.exists()) { "Refusing to overwrite an existing SQLDelight database" }
        val source = readSnapshot(context, legacy, key)
        val tempName = "andrey_ceilings_sqldelight_import.tmp.db"
        val temp = context.getDatabasePath(tempName)
        // This filename is reserved solely for import and never used for live app data.
        context.deleteDatabase(tempName)
        try {
            createEncryptedPrototypeDriver(context, key, tempName).use { driver ->
                val stage = SqlDelightDatabaseSession(driver)
                RoomToSqlDelightStagingImporter.importIntoEmptyStage(source, stage)
                val integrity = driver.executeQuery(null, "PRAGMA integrity_check",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0).value
                check(integrity.equals("ok", ignoreCase = true)) { "Imported SQLDelight database failed integrity check" }
                driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) }, 0
                ).value.let { check(it == 0L) { "Could not checkpoint imported database" } }
            }
            check(temp.isFile && temp.length() > 16L) { "Migrated database is empty" }
            check(temp.renameTo(destination)) { "Failed to publish imported SQLDelight database" }
        } finally {
            context.deleteDatabase(tempName)
        }
    }
}
