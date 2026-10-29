package com.andreyceilings.app

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.LegacyRoomDatabaseImporter
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room-v2 encrypted schema fixture, created without Room runtime.
 * Real SQLCipher legacy file is read and converted into SQLDelight with its IDs intact.
 */
@RunWith(AndroidJUnit4::class)
class RoomToSqlDelightStagingMigrationTest {
    @Test
    fun encryptedLegacyDatabaseImportsAllFiveTablesAndPreservesOriginal() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val oldName = "andrey_legacy_room_test.db"
        val newName = "andrey_import_result_test.db"
        context.deleteDatabase(oldName)
        context.deleteDatabase(newName)
        val key = DatabaseKeyManager.getOrCreatePassphrase(context)
        try {
            System.loadLibrary("sqlcipher")
            val helper = SupportOpenHelperFactory(key.copyOf()).create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(oldName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE clients(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, email TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clientId INTEGER, title TEXT NOT NULL, address TEXT NOT NULL, status TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE room_measurements(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, projectId INTEGER NOT NULL, name TEXT NOT NULL, shape TEXT NOT NULL, lengthMeters REAL NOT NULL, widthMeters REAL NOT NULL, areaSquareMeters REAL NOT NULL, perimeterMeters REAL NOT NULL, drawingJson TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE price_items(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, code TEXT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, unit TEXT NOT NULL, price REAL NOT NULL, updatedAt INTEGER NOT NULL)")
                            db.execSQL("CREATE TABLE estimates(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, projectId INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE, name TEXT NOT NULL, material TEXT NOT NULL, profileType TEXT NOT NULL, complexity TEXT NOT NULL, lengthMeters REAL NOT NULL, widthMeters REAL NOT NULL, areaSquareMeters REAL NOT NULL, perimeterMeters REAL NOT NULL, canvasCost REAL NOT NULL, profileCost REAL NOT NULL, complexityCost REAL NOT NULL, optionsCost REAL NOT NULL, totalCost REAL NOT NULL, detailsJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        }
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            error("No fixture upgrade expected")
                        }
                    }).build()
            )
            try {
                val db = helper.writableDatabase
                db.execSQL("INSERT INTO clients VALUES(7, 'Legacy client', '', '', '', 100)")
                db.execSQL("INSERT INTO projects VALUES(19, 7, 'Legacy project', '', 'measurement', '', 101, 102)")
                db.execSQL("INSERT INTO room_measurements VALUES(23, 19, 'Kitchen', 'rectangle', 3, 4, 12, 14, '{}', '', 103)")
                db.execSQL("INSERT INTO price_items VALUES(41, 'L', 'Legacy film', 'material', 'm2', 24, 104)")
                db.execSQL("INSERT INTO estimates VALUES(59, 19, 'Quote', 'PVC', 'standard', 'simple', 3, 4, 12, 14, 100, 20, 0, 0, 120, '{}', 105)")
            } finally { helper.close() }
            val old = context.getDatabasePath(oldName)
            // The first SQLCipher native call in the *separate debug-only process*
            // must happen inside LegacyRoomDatabaseImporter. Previous tests loaded
            // SQLCipher in their own process and could not catch cold-start failures.
            val probeUri = Uri.Builder()
                .scheme("content")
                .authority("com.andreyceilings.app.migrationprobe")
                .appendPath("migrate")
                .appendQueryParameter("source", oldName)
                .appendQueryParameter("destination", newName)
                .build()
            context.contentResolver.query(probeUri, null, null, null, null).use { cursor ->
                assertTrue("Migration probe returned no result", cursor != null && cursor.moveToFirst())
                assertEquals(1, cursor!!.getInt(0))
            }
            createEncryptedPrototypeDriver(context, key, newName).use { driver ->
                val q = PrototypeDatabase(driver).businessSchemaQueries
                assertEquals(7L, q.selectClients().executeAsOne().id)
                assertEquals(19L, q.selectProjects().executeAsOne().id)
                assertEquals(23L, q.selectAllMeasurements().executeAsOne().id)
                assertEquals(41L, q.selectPriceItems().executeAsOne().id)
                assertEquals(59L, q.selectAllEstimates().executeAsOne().id)
                com.andreyceilings.shared.db.ProjectRepository(PrototypeDatabase(driver)).delete(19)
                assertTrue(q.selectAllEstimates().executeAsList().isEmpty())
                assertEquals(1, q.selectAllMeasurements().executeAsList().size)
            }
            assertTrue(old.isFile) // Source database is never deleted during conversion.
        } finally {
            context.deleteDatabase(oldName)
            context.deleteDatabase(newName)
            key.fill(0)
        }
    }
}
