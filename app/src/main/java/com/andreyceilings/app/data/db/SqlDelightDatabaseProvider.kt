package com.andreyceilings.app.data.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.andreyceilings.app.data.security.DatabaseKeyManager
import com.andreyceilings.shared.db.ClientRepository
import com.andreyceilings.shared.db.EstimateRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver

/**
 * Non-production Android SQLDelight session for the Room migration.
 *
 * All business repositories are from :shared, with no Room dependencies.
 * Keep sessions short-lived and close them before backup, restore or file replacement.
 */
internal class SqlDelightDatabaseSession internal constructor(
    private val driver: SqlDriver
) : AutoCloseable {
    val database = PrototypeDatabase(driver)
    val clients = ClientRepository(database)
    val projects = ProjectRepository(database)
    val measurements = MeasurementRepository(database)
    val prices = PriceRepository(database)
    val estimates = EstimateRepository(database)

    override fun close() {
        driver.close()
    }
}

/**
 * Opt-in test stage, NOT a production switch.
 *
 * Takes the real app-managed SQLCipher passphrase from Android Keystore but uses
 * a different file name. We must not attach SQLDelight schema version 1 to the
 * current Room schema version 2 file without a separately tested migration.
 */
internal object SqlDelightDatabaseProvider {
    private const val LIVE_ROOM_DATABASE = "andrey_ceilings_secure.db"
    private const val STAGING_DATABASE = "andrey_ceilings_sqldelight_stage.db"

    fun openStaging(context: Context): SqlDelightDatabaseSession {
        check(STAGING_DATABASE != LIVE_ROOM_DATABASE) {
            "Cannot open the live Room database before a versioned migration"
        }
        val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context.applicationContext)
        try {
            val driver = createEncryptedPrototypeDriver(
                context.applicationContext, passphrase, STAGING_DATABASE
            )
            return SqlDelightDatabaseSession(driver)
        } finally {
            passphrase.fill(0)
        }
    }
}
