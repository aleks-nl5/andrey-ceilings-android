package com.andreyceilings.app.data.security

import android.content.Context
import com.andreyceilings.app.data.db.CeilingsDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import androidx.room.Room
import java.io.File

/**
 * Builds and validates portable backup bytes. Call from Dispatchers.IO.
 * The caller is responsible for writing the returned bytes to a user-selected URI.
 */
internal object PortableBackupManager {
    private const val DATABASE_NAME = "andrey_ceilings_secure.db"

    fun create(context: Context, database: CeilingsDatabase, password: CharArray): ByteArray {
        val sqlDb = database.openHelper.writableDatabase
        sqlDb.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            check(cursor.moveToFirst()) { "Не вдалося завершити запис бази перед резервним копіюванням." }
            check(cursor.getLong(0) == 0L) { "База даних зайнята. Спробуйте ще раз." }
        }
        val sourcePath = sqlDb.path ?: error("Шлях до бази даних недоступний.")
        val source = File(sourcePath)
        check(source.isFile && source.length() > 0) { "Файл бази даних відсутній або порожній." }

        val snapshot = File.createTempFile("portable_db_", ".tmp", context.cacheDir)
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
        try {
            source.inputStream().use { input -> snapshot.outputStream().use { output -> input.copyTo(output) } }
            validateDatabase(context, snapshot, passphrase)
            val payload = PortableBackupPayload.encode(snapshot.readBytes(), passphrase)
            return PortableBackupEnvelope.seal(payload, password)
        } finally {
            passphrase.fill(0)
            snapshot.delete()
        }
    }

    /** Decrypts, parses, and verifies a portable backup without replacing the live database. */
    fun validate(context: Context, envelope: ByteArray, password: CharArray): PortableBackupPayload.Contents {
        val payload = PortableBackupEnvelope.open(envelope, password)
        val contents = PortableBackupPayload.decode(payload)
        val staged = File.createTempFile("portable_restore_check_", ".db", context.cacheDir)
        try {
            staged.outputStream().use { it.write(contents.database) }
            validateDatabase(context, staged, contents.passphrase)
            return contents
        } catch (error: Exception) {
            contents.passphrase.fill(0)
            contents.database.fill(0)
            throw error
        } finally {
            staged.delete()
            payload.fill(0)
        }
    }

    private fun validateDatabase(context: Context, file: File, passphrase: ByteArray) {
        SQLiteDatabase.loadLibs(context)
        val verifier = Room.databaseBuilder(context, CeilingsDatabase::class.java, file.absolutePath)
            .openHelperFactory(SupportFactory(passphrase.copyOf()))
            .build()
        try {
            val db = verifier.openHelper.readableDatabase
            db.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Перевірка цілісності бази даних не пройдена."
                }
            }
            db.query("SELECT count(*) FROM sqlite_master").use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) > 0) {
                    "У файлі не знайдено схему бази даних."
                }
            }
        } finally {
            verifier.close()
        }
    }
}
