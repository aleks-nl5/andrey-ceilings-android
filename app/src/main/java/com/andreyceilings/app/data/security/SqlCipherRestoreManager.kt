package com.andreyceilings.app.data.security

import android.content.Context
import com.andreyceilings.app.data.db.CeilingsDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Staged SQLCipher restore with a durable encrypted rollback snapshot.
 * Keeps prior passphrase only as a Keystore-wrapped recovery marker.
 *
 * The live DB and its WAL/SHM are removed ONLY after closing the sole driver.
 * An interrupted restore is rolled back before any subsequent DB open.
 */
internal object SqlCipherRestoreManager {
    private const val ROLLBACK_NAME = ".ceilings_restore_rollback.db"
    @Volatile internal var installing = false
        private set

    private fun rollbackFile(context: Context) =
        File(context.getDatabasePath(CeilingsDatabase.DATABASE_NAME).parentFile, ROLLBACK_NAME)

    /** Fail closed rather than create an empty DB if crash recovery fails. */
    @Synchronized
    fun recoverIfNeeded(context: Context) {
        val app = context.applicationContext
        val rollback = rollbackFile(app)
        val priorKey = DatabaseKeyManager.pendingRestoreOriginalPassphrase(app)
        if (priorKey == null) {
            // A crash before the durable marker cannot have changed the live DB.
            if (rollback.exists()) rollback.delete()
            return
        }
        try {
            check(rollback.isFile) {
                "Немає резервного стану для перерваного імпорту."
            }
            PortableBackupManager.verifyEncryptedSnapshot(app, rollback, priorKey)
            val live = app.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
            val staged = File.createTempFile("restore_recovery_", ".db", live.parentFile)
            try {
                copyAndSync(rollback, staged)
                // Only called before an initial DB open, or after closeAndReset.
                removeClosedDatabaseFiles(live)
                check(staged.renameTo(live)) {
                    "Не вдалося повернути початкову базу після збою."
                }
                DatabaseKeyManager.rollbackPendingRestoreKey(app)
                rollback.delete()
            } finally {
                staged.delete()
            }
        } finally {
            priorKey.fill(0)
        }
    }

    /** Fault points are opt-in and supplied only by instrumentation tests. */
    internal enum class RestoreCheckpoint {
        AFTER_MARKER,
        AFTER_OLD_FILES_REMOVED,
        AFTER_IMPORTED_FILE_INSTALLED,
        AFTER_KEY_REPLACED
    }

    fun restore(
        context: Context,
        contents: PortableBackupPayload.Contents,
        onCheckpoint: ((RestoreCheckpoint) -> Unit)? = null
    ): CeilingsDatabase =
        CeilingsDatabase.withExclusiveRestoreLock {
            synchronized(this) {
                restoreLocked(context.applicationContext, contents, onCheckpoint)
            }
        }

    private fun restoreLocked(
        context: Context,
        contents: PortableBackupPayload.Contents,
        onCheckpoint: ((RestoreCheckpoint) -> Unit)?
    ): CeilingsDatabase {
        recoverIfNeeded(context)
        val live = context.getDatabasePath(CeilingsDatabase.DATABASE_NAME)
        val parent = checkNotNull(live.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Каталог бази даних недоступний." }
        val rollback = rollbackFile(context)
        var incoming: File? = null
        var originalSnapshot: File? = null
        var rollbackDraft: File? = null
        var pending = false
        try {
            // Validate new file and key BEFORE closing any production DB handle.
            val stage = File.createTempFile("restore_incoming_", ".db", parent)
            incoming = stage
            FileOutputStream(stage).use { out ->
                out.write(contents.database)
                out.fd.sync()
            }
            PortableBackupManager.verifyEncryptedSnapshot(context, stage, contents.passphrase)

            val current = CeilingsDatabase.get(context)
            val snapshot = PortableBackupManager.createVerifiedSnapshot(context, current)
            originalSnapshot = snapshot
            check(!rollback.exists()) { "Залишився невиконаний резервний відкат." }
            val draft = File.createTempFile("restore_prepared_", ".db", parent)
            rollbackDraft = draft
            copyAndSync(snapshot, draft)
            check(draft.renameTo(rollback)) { "Не вдалося зберегти резерв для відкату." }
            rollbackDraft = null

            DatabaseKeyManager.beginPendingRestore(context)
            pending = true
            onCheckpoint?.invoke(RestoreCheckpoint.AFTER_MARKER)
            CeilingsDatabase.closeAndReset()
            // The rollback snapshot already includes every committed WAL frame.
            // After closure, old sidecars must not accompany the imported DB.
            removeClosedDatabaseFiles(live)
            onCheckpoint?.invoke(RestoreCheckpoint.AFTER_OLD_FILES_REMOVED)
            check(stage.renameTo(live)) { "Не вдалося встановити імпортовану БД." }
            incoming = null
            onCheckpoint?.invoke(RestoreCheckpoint.AFTER_IMPORTED_FILE_INSTALLED)
            DatabaseKeyManager.replacePassphrase(context, contents.passphrase)
            onCheckpoint?.invoke(RestoreCheckpoint.AFTER_KEY_REPLACED)
            installing = true
            val restored = CeilingsDatabase.get(context)
            check(restored.checkIntegrity()) { "Імпортована БД не пройшла integrity_check." }
            DatabaseKeyManager.finishPendingRestore(context)
            pending = false
            rollback.delete()
            return restored
        } catch (failure: Throwable) {
            if (pending) {
                try {
                    CeilingsDatabase.closeAndReset()
                    recoverIfNeeded(context)
                    pending = false
                } catch (recoverError: Throwable) {
                    // Leave rollback + key marker intact for next cold start.
                    failure.addSuppressed(recoverError)
                }
            }
            throw failure
        } finally {
            installing = false
            incoming?.delete()
            originalSnapshot?.delete()
            rollbackDraft?.delete()
        }
    }

    private fun copyAndSync(source: File, destination: File) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    /** MUST only be called when no live driver is open. */
    private fun removeClosedDatabaseFiles(live: File) {
        listOf(File(live.absolutePath + "-wal"), File(live.absolutePath + "-shm"), live)
            .forEach { file ->
                if (file.exists()) check(file.delete()) {
                    "Не вдалося прибрати закритий файл БД: ${file.name}"
                }
            }
    }
}
