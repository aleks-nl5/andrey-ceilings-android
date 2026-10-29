package com.andreyceilings.app.data.security

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.LegacyRoomDatabaseImporter
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import java.io.File

/**
 * Portable .stpb backups for the encrypted SQLDelight database.
 * Legacy Room-v2 .stpb backups can be converted without Room runtime.
 */
internal object PortableBackupManager {
    fun create(context: Context, database: CeilingsDatabase, password: CharArray): ByteArray {
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        var snapshot: File? = null
        try {
            snapshot = createVerifiedSnapshot(context, database, key)
            val payload = PortableBackupPayload.encode(snapshot.readBytes(), key)
            try {
                return PortableBackupEnvelope.seal(payload, password)
            } finally {
                payload.fill(0)
            }
        } finally {
            snapshot?.delete()
            key.fill(0)
        }
    }

    /** Manual encrypted export uses the same consistent image as portable backups. */
    fun createVerifiedSnapshot(context: Context, database: CeilingsDatabase): File {
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        try {
            return createVerifiedSnapshot(context, database, key)
        } finally {
            key.fill(0)
        }
    }

    private fun createVerifiedSnapshot(
        context: Context, database: CeilingsDatabase, key: ByteArray
    ): File {
        val snapshot = File.createTempFile("sqldelight_backup_", ".db", context.cacheDir)
        try {
            // SQLite VACUUM INTO requires a destination that does not yet exist.
            check(snapshot.delete()) { "Не вдалося підготувати тимчасовий файл знімка." }
            database.writeConsistentEncryptedSnapshot(snapshot)
            verifyEncryptedSnapshot(context, snapshot, key)
            return snapshot
        } catch (failure: Throwable) {
            snapshot.delete()
            throw failure
        }
    }

    /**
     * Validate without replacing the live DB or key. Legacy Room schema v2
     * snapshots are re-encrypted in the current SQLDelight format before restore.
     * A corrupt file or incorrect password never triggers an empty fallback.
     */
    fun validate(context: Context, envelope: ByteArray, password: CharArray): PortableBackupPayload.Contents {
        val payload = PortableBackupEnvelope.open(envelope, password)
        try {
            val contents = PortableBackupPayload.decode(payload)
            val legacyName = "legacy_restore_${System.nanoTime()}.db"
            val convertedName = "converted_restore_${System.nanoTime()}.db"
            val legacy = context.getDatabasePath(legacyName)
            val converted = context.getDatabasePath(convertedName)
            try {
                legacy.parentFile?.mkdirs()
                legacy.writeBytes(contents.database)
                val alreadySqlDelight = runCatching {
                    verifyEncryptedSnapshot(context, legacy, contents.passphrase)
                }.isSuccess

                if (alreadySqlDelight) return contents

                // Only a valid encrypted legacy Room-v2 schema is accepted here.
                LegacyRoomDatabaseImporter.migrateToNewDatabase(
                    context, legacy, converted, contents.passphrase
                )
                verifyEncryptedSnapshot(context, converted, contents.passphrase)
                val bytes = converted.readBytes()
                contents.database.fill(0)
                return PortableBackupPayload.Contents(bytes, contents.passphrase)
            } catch (failure: Throwable) {
                contents.database.fill(0)
                contents.passphrase.fill(0)
                throw failure
            } finally {
                context.deleteDatabase(legacyName)
                context.deleteDatabase(convertedName)
            }
        } finally {
            payload.fill(0)
        }
    }

    /**
     * Copies the SQLCipher file into a separate test database and opens it with
     * the real SQLDelight schema and supplied key. Does not touch the live DB.
     */
    fun verifyEncryptedSnapshot(context: Context, source: File, key: ByteArray) {
        check(source.isFile && source.length() > 16L) { "Резервна копія відсутня або порожня." }
        val header = ByteArray(16)
        java.io.DataInputStream(source.inputStream()).use { it.readFully(header) }
        check(!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) {
            "Резервна копія не зашифрована SQLCipher."
        }
        val testName = "sqlcipher_verify_${System.nanoTime()}.db"
        val staged = context.getDatabasePath(testName)
        try {
            staged.parentFile?.mkdirs()
            source.copyTo(staged, overwrite = false)
            createEncryptedPrototypeDriver(context, key, testName).use { driver ->
                val integrity = driver.executeQuery(null, "PRAGMA integrity_check",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    0).value
                check(integrity.equals("ok", ignoreCase = true)) {
                    "Перевірка цілісності SQLCipher не пройдена."
                }
                val count = driver.executeQuery(null,
                    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('clients','projects','room_measurements','price_items','estimates')",
                    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                    0).value
                check(count == 5L) { "Резервна копія не містить повної схеми SQLDelight." }
                // A pre-UUID test backup must not overwrite a database using the
                // new sync-ready schema. A table-name check alone is insufficient.
                listOf("clients", "projects", "room_measurements", "price_items", "estimates")
                    .forEach { table ->
                        driver.executeQuery(null,
                            "SELECT uuid, updatedAt, deletedAt FROM $table LIMIT 0",
                            { cursor -> QueryResult.Value(cursor.next().value) }, 0
                        ).value
                    }
            }
        } finally {
            context.deleteDatabase(testName)
        }
    }
}
