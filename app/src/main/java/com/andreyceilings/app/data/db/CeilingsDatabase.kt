package com.andreyceilings.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import com.andreyceilings.app.data.security.DatabaseKeyManager

@Database(
    entities = [ClientEntity::class, ProjectEntity::class, RoomMeasurementEntity::class, PriceItemEntity::class, EstimateEntity::class],
    version = 2,
    exportSchema = true
)
abstract class CeilingsDatabase : RoomDatabase() {
    abstract fun clients(): ClientDao
    abstract fun projects(): ProjectDao
    abstract fun measurements(): RoomMeasurementDao
    abstract fun priceItems(): PriceItemDao
    abstract fun estimates(): EstimateDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS estimates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    projectId INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    material TEXT NOT NULL,
                    profileType TEXT NOT NULL,
                    complexity TEXT NOT NULL,
                    lengthMeters REAL NOT NULL,
                    widthMeters REAL NOT NULL,
                    areaSquareMeters REAL NOT NULL,
                    perimeterMeters REAL NOT NULL,
                    canvasCost REAL NOT NULL,
                    profileCost REAL NOT NULL,
                    complexityCost REAL NOT NULL,
                    optionsCost REAL NOT NULL,
                    totalCost REAL NOT NULL,
                    detailsJson TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(projectId) REFERENCES projects(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_estimates_projectId ON estimates(projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_estimates_createdAt ON estimates(createdAt)")
            }
        }

        @Volatile private var INSTANCE: CeilingsDatabase? = null

        fun get(context: Context): CeilingsDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
        }

        /** Close the current Room instance before replacing the encrypted database file. */
        fun closeAndReset() = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }

        private fun build(context: Context): CeilingsDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(context, CeilingsDatabase::class.java, "andrey_ceilings_secure.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                // No destructive migration fallback: schema changes require explicit migrations.
                .build()
        }
    }
}
