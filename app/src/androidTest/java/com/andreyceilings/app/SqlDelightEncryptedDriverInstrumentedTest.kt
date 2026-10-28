package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.createEncryptedPrototypeDriver
import java.io.File
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlDelightEncryptedDriverInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "andrey_ceilings_sqldelight_prototype.db"
    private val passphrase = ByteArray(32).also { Random.nextBytes(it) }

    @Before
    fun cleanBeforeTest() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun cleanAfterTest() {
        context.deleteDatabase(databaseName)
        passphrase.fill(0)
    }

    @Test
    fun databaseIsEncryptedAndDataSurvivesReopen() {
        val driver = createEncryptedPrototypeDriver(context, passphrase)
        try {
            val queries = PrototypeDatabase(driver).prototypeClientQueries
            queries.insertClient(id = 42L, name = "Encrypted test", created_at_epoch_millis = 123L)
            assertEquals("Encrypted test", queries.selectClientById(42L).executeAsOne().name)
        } finally {
            driver.close()
        }

        val databaseFile = File(context.getDatabasePath(databaseName).path)
        check(databaseFile.exists()) { "SQLCipher database file was not created" }
        val header = databaseFile.inputStream().use { input ->
            ByteArray(16).also { bytes ->
                val count = input.read(bytes)
                check(count == bytes.size) { "Database file is unexpectedly short" }
            }
        }
        assertNotEquals(
            "SQLite format 3\u0000",
            header.toString(Charsets.ISO_8859_1)
        )

        val reopened = createEncryptedPrototypeDriver(context, passphrase)
        try {
            val queries = PrototypeDatabase(reopened).prototypeClientQueries
            assertEquals("Encrypted test", queries.selectClientById(42L).executeAsOne().name)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun wrongPassphraseCannotReadDatabase() {
        val writer = createEncryptedPrototypeDriver(context, passphrase)
        try {
            PrototypeDatabase(writer).prototypeClientQueries.insertClient(
                id = 99L,
                name = "Secret",
                created_at_epoch_millis = 456L
            )
        } finally {
            writer.close()
        }

        val wrongKey = ByteArray(32).also { Random.nextBytes(it) }
        assertNotEquals(passphrase.toList(), wrongKey.toList())
        try {
            val reader = createEncryptedPrototypeDriver(context, wrongKey)
            try {
                PrototypeDatabase(reader).prototypeClientQueries.selectAllClients().executeAsList()
                throw AssertionError("Database opened with an incorrect passphrase")
            } finally {
                reader.close()
            }
        } catch (_: Exception) {
            // SQLCipher is expected to reject access with the wrong passphrase.
        } finally {
            wrongKey.fill(0)
        }
    }
}
