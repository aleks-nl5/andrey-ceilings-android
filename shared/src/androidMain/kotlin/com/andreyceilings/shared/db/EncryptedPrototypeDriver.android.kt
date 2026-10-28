package com.andreyceilings.shared.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Creates an encrypted SQLDelight prototype database on Android.
 *
 * The caller owns key generation/storage and must pass the existing app-managed
 * SQLCipher passphrase. This factory deliberately does not create a plaintext fallback.
 * iOS and Windows/JVM factories are withheld until their encryption paths are verified.
 */
fun createEncryptedPrototypeDriver(
    context: Context,
    passphrase: ByteArray
): SqlDriver {
    require(passphrase.size == 32) { "Expected a 256-bit database passphrase" }
    System.loadLibrary("sqlcipher")
    val helperFactory = SupportOpenHelperFactory(passphrase.copyOf())
    return AndroidSqliteDriver(
        schema = PrototypeDatabase.Schema,
        factory = helperFactory,
        context = context.applicationContext,
        name = "andrey_ceilings_sqldelight_prototype.db"
    )
}
