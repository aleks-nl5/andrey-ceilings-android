package com.andreyceilings.app.data.backup

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.security.PortableBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates one password-protected portable backup in the folder selected by the user. */
class AutomaticBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var password: CharArray? = null
        var backup: ByteArray? = null
        var partial: DocumentFile? = null
        try {
            val folderUri = BackupPasswordStore.folder(applicationContext)
                ?: return@withContext Result.failure()
            password = BackupPasswordStore.readPassword(applicationContext)
                ?: return@withContext Result.failure()
            val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
                ?: return@withContext Result.failure()
            if (!folder.isDirectory || !folder.canWrite()) return@withContext Result.failure()

            val database = CeilingsDatabase.get(applicationContext)
            backup = PortableBackupManager.create(applicationContext, database, password)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val tempName = ".stpb.partial"
            partial = folder.createFile("application/octet-stream", tempName)
                ?: return@withContext Result.retry()
            applicationContext.contentResolver.openOutputStream(partial.uri, "w").use { stream ->
                checkNotNull(stream) { "Не вдалося відкрити файл резервної копії." }
                stream.write(backup)
                stream.flush()
            }
            val finalName = "СтеляПро_$stamp.stpb"
            check(partial.renameTo(finalName)) { "Не вдалося завершити запис резервної копії." }
            partial = null

            // Keep the five newest backups created by this worker. Other files are untouched.
            folder.listFiles()
                .filter { it.name?.matches(Regex("СтеляПро_\\d{8}_\\d{6}\\.stpb")) == true }
                .sortedByDescending { it.name }
                .drop(5)
                .forEach { it.delete() }
            Result.success()
        } catch (e: Exception) {
            partial?.delete()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            password?.fill('\u0000')
            backup?.fill(0)
        }
    }
}
