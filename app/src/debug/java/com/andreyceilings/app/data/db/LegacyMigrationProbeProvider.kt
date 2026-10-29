package com.andreyceilings.app.data.db

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.andreyceilings.app.data.security.DatabaseKeyManager

/**
 * Debug-only integration probe, intentionally hosted in a clean Android process.
 *
 * Android instrumentation tests normally run after other tests have loaded the SQLCipher
 * native library. That masked the actual on-device first-launch crash. This provider
 * has no native-library initializer: LegacyRoomDatabaseImporter must load SQLCipher itself.
 *
 * Non-exported and restricted to disposable migration-test filenames.
 */
internal class LegacyMigrationProbeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        require(uri.path == "/migrate") { "Unsupported test operation" }
        val sourceName = requireNotNull(uri.getQueryParameter("source"))
        val destinationName = requireNotNull(uri.getQueryParameter("destination"))
        require(sourceName.startsWith("andrey_legacy_room_test") && sourceName.endsWith(".db")) {
            "Only disposable legacy test databases can be migrated by the probe"
        }
        require(destinationName.startsWith("andrey_import_result_test") && destinationName.endsWith(".db")) {
            "Only disposable SQLDelight test destinations are allowed"
        }
        val context = requireNotNull(context).applicationContext
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        try {
            LegacyRoomDatabaseImporter.migrateToNewDatabase(
                context,
                context.getDatabasePath(sourceName),
                context.getDatabasePath(destinationName),
                key
            )
        } finally {
            key.fill(0)
        }
        return MatrixCursor(arrayOf("migrated")).apply { addRow(arrayOf(1)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only test provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only test provider")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only test provider")
}
