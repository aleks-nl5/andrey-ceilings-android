package com.andreyceilings.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.dashboard.DashboardStore
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.SqlCipherWindowsDriverFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Opt-in desktop prototype: no hard-coded key, plaintext fallback, or key written to disk.
 * A user-entered passphrase derives a SQLCipher key using a per-installation random salt.
 *
 * This is NOT automatic Windows credential-vault integration; the native OS-key-store
 * migration and installer/recovery workflow remain tracked by issue #42.
 */
internal class DesktopPriceDatabase private constructor(
    val prices: PriceRepository,
    val dashboard: DashboardStore,
    val objects: ObjectsClientsStore,
    private val driver: SqlDriver
) : AutoCloseable {
    override fun close() = driver.close()

    companion object {
        private const val ITERATIONS = 310_000

        fun open(password: CharArray): DesktopPriceDatabase {
            require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "Windows SQLCipher driver is only installed on Windows"
            }
            require(password.size >= 10) { "Для локального шифрування вкажіть пароль щонайменше з 10 символів." }

            val folder = File(System.getProperty("user.home"), ".andrey-ceilings").absoluteFile
            check(folder.isDirectory || folder.mkdirs()) { "Не вдалося створити приватну папку." }
            val file = File(folder, "catalog.db")
            val saltFile = File(folder, "catalog.salt")
            val previouslyExisting = file.exists()

            // Never create a new salt alongside an existing encrypted database:
            // doing so would silently make that database unrecoverable.
            check(!previouslyExisting || saltFile.isFile) {
                "Відсутня сіль ключа існуючої БД. Створення нової БД заблоковане."
            }
            val salt = if (saltFile.isFile) {
                saltFile.readBytes().also { check(it.size == 32) { "Пошкоджені дані derivation salt." } }
            } else {
                ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                    // CREATE_NEW ensures that concurrent first-launch attempts don't replace salt.
                    Files.write(saltFile.toPath(), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
            }
            var derivedKey = ByteArray(0)
            try {
                val specification = PBEKeySpec(password, salt, ITERATIONS, 256)
                try {
                    derivedKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(specification).encoded
                } finally {
                    specification.clearPassword()
                }

                val driver = SqlCipherWindowsDriverFactory.open(file, derivedKey)
                return try {
                    if (!previouslyExisting) {
                        PrototypeDatabase.Schema.create(driver)
                    } else {
                        // Older Windows installations were built with schema v3.
                        // The prototype didn't persist SQLDelight's user_version;
                        // determine whether the one additive v4 migration is needed
                        // by examining the encrypted schema itself. No plaintext
                        // fallback and no empty database is created over old data.
                        val columns = driver.executeQuery(null, "PRAGMA table_info(projects)",
                            { cursor ->
                                val names = mutableSetOf<String>()
                                while (cursor.next().value) {
                                    cursor.getString(1)?.let(names::add)
                                }
                                QueryResult.Value(names)
                            }, 0).value
                        check("status" in columns) { "Існуюча SQLCipher БД має непідтримувану схему." }
                        if ("paymentStatus" !in columns) {
                            // Fail closed on pre-v3 estimate schemas; the
                            // v4 migration is intentionally narrow.
                            driver.executeQuery(null,
                                "SELECT seriesUuid FROM estimates LIMIT 0",
                                { cursor -> QueryResult.Value(cursor.next().value) }, 0)
                            PrototypeDatabase.Schema.migrate(driver, 3L, 4L)
                        }
                    }
                    val database = PrototypeDatabase(driver)
                    // Verify real encrypted tables before accepting password.
                    PriceRepository(database).all()
                    val objects = ObjectsClientsStore(database, System::currentTimeMillis)
                    com.andreyceilings.shared.db.ProjectRepository(database).all()
                    DesktopPriceDatabase(PriceRepository(database), DashboardStore(database),
                        objects, driver)
                } catch (error: Throwable) {
                    driver.close()
                    throw error
                }
            } finally {
                password.fill('\u0000')
                salt.fill(0)
                derivedKey.fill(0)
            }
        }
    }
}
