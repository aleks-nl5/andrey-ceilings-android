package com.andreyceilings.app.data.security

import android.content.Context
import android.os.StatFs
import com.andreyceilings.app.data.db.CeilingsDatabase
import java.io.File

/**
 * Internal-storage preflight. A startup warning never prevents reading the
 * existing database. Actual writes still handle I/O errors: available space
 * can change immediately after StatFs has been sampled.
 *
 * These estimates cover transient SQLCipher snapshot / verification / staged
 * restore files and the current DB/WAL/SHM footprint, NOT space on an arbitrary
 * SAF export destination. They are conservative estimates, not quotas.
 */
internal object StorageSpaceGuard {
    private const val MIB = 1024L * 1024L
    private const val STARTUP_WARNING_FLOOR = 128L * MIB
    private const val OPERATION_MARGIN = 64L * MIB

    private fun boundedAdd(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun boundedMultiply(value: Long, factor: Long): Long {
        val positive = value.coerceAtLeast(0L)
        return if (positive > Long.MAX_VALUE / factor) Long.MAX_VALUE
        else positive * factor
    }

    /**
     * A WAL-mode database also occupies -wal and -shm files. The main .db may
     * lag committed WAL frames by many pages, so sizing only .db understates
     * the transient headroom needed for an encrypted VACUUM INTO snapshot.
     *
     * Compute a conservative physical upper bound, not a precise VACUUM size:
     * repeated WAL page versions can make this larger than a compact snapshot.
     * A single file's File.length() is never sufficient for WAL preflight.
     */
    internal fun databaseFootprintWithSidecars(databaseFile: File): Long =
        listOf(
            databaseFile,
            File(databaseFile.absolutePath + "-wal"),
            File(databaseFile.absolutePath + "-shm")
        ).fold(0L) { size, file ->
            boundedAdd(size, file.length().coerceAtLeast(0L))
        }

    /** Snapshot in cache + independent integrity-check copy in databases/. */
    internal fun bytesForBackup(databaseBytes: Long): Long =
        boundedAdd(boundedMultiply(databaseBytes, 2L), OPERATION_MARGIN)

    /** Incoming stage + validation copy + old snapshot + rollback draft. */
    internal fun bytesForRestore(importedBytes: Long, existingBytes: Long): Long =
        boundedAdd(
            boundedAdd(boundedMultiply(importedBytes, 2L), boundedMultiply(existingBytes, 3L)),
            OPERATION_MARGIN
        )

    /** Only used for an imported archive before its source DB is validated. */
    internal fun bytesForImportValidation(importedBytes: Long): Long =
        boundedAdd(boundedMultiply(importedBytes, 3L), OPERATION_MARGIN)

    internal fun sufficient(availableBytes: Long, requiredBytes: Long): Boolean =
        availableBytes >= requiredBytes && availableBytes >= 0L

    private fun internalAvailableBytes(context: Context): Long =
        StatFs(context.filesDir.absolutePath).availableBytes

    /** Non-blocking warning shown once at startup, only if space is low. */
    fun startupWarning(context: Context): String? {
        val available = runCatching { internalAvailableBytes(context) }.getOrNull()
            ?: return null // A failed measurement must not block database open.
        val databaseBytes = databaseFootprintWithSidecars(
            context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        )
        val recommended = maxOf(STARTUP_WARNING_FLOOR, bytesForBackup(databaseBytes))
        if (sufficient(available, recommended)) return null
        return "У внутрішній пам'яті доступно приблизно ${available.coerceAtLeast(0L) / MIB} МіБ. " +
            "Для безпечного резервування та відновлення бажано мати не менше " +
            "${(recommended + MIB - 1) / MIB} МіБ вільного місця. " +
            "Звільніть пам'ять перед імпортом або створенням резервної копії. " +
            "Доступ до наявних записів залишається відкритим."
    }

    private fun requireInternalSpace(context: Context, bytes: Long, purpose: String) {
        val available = internalAvailableBytes(context)
        check(sufficient(available, bytes)) {
            "Недостатньо вільного місця для $purpose: потрібно приблизно " +
                "${(bytes + MIB - 1) / MIB} МіБ, доступно " +
                "${available.coerceAtLeast(0L) / MIB} МіБ. " +
                "Дані БД не змінено. Звільніть місце й повторіть операцію."
        }
    }

    fun requireForBackup(context: Context, databaseFile: File) {
        requireInternalSpace(
            context, bytesForBackup(databaseFootprintWithSidecars(databaseFile)),
            "резервування БД"
        )
    }

    fun requireForImportValidation(context: Context, importedSize: Long) {
        requireInternalSpace(context, bytesForImportValidation(importedSize), "перевірки імпортованої копії")
    }

    fun requireForRestore(context: Context, incomingSize: Long, currentFile: File) {
        requireInternalSpace(context,
            bytesForRestore(incomingSize, databaseFootprintWithSidecars(currentFile)),
            "відновлення БД")
    }
}
