package com.andreyceilings.desktop

import app.cash.sqldelight.db.SqlDriver
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
                    if (!previouslyExisting) PrototypeDatabase.Schema.create(driver)
                    val database = PrototypeDatabase(driver)
                    // Query the *actual* encrypted schema before considering login successful.
                    PriceRepository(database).all()
                    DesktopPriceDatabase(PriceRepository(database), driver)
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
