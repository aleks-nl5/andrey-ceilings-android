package com.andreyceilings.app.data.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected envelope for portable backups.
 *
 * The caller must supply a payload that already contains all material required
 * to restore the SQLCipher database on another installation. This class does
 * not read the live database, persist passwords, or install database keys.
 */
internal object PortableBackupEnvelope {
    private const val MAGIC = "STELYAPRO"
    private const val FORMAT_VERSION = 1
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_PAYLOAD_BYTES = 512 * 1024 * 1024

    /** Encrypts a payload using a password-derived AES-256-GCM key. */
    fun seal(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.isNotEmpty()) { "Резервна копія порожня." }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Резервна копія завелика." }
        require(password.size >= 10) { "Пароль має містити щонайменше 10 символів." }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeInt(FORMAT_VERSION)
                out.writeInt(ITERATIONS)
                out.writeByte(SALT_BYTES)
                out.writeByte(NONCE_BYTES)
                out.writeInt(payload.size)
                out.write(salt)
                out.write(nonce)
            }
            bytes.toByteArray()
        }

        val key = deriveKey(password, salt, ITERATIONS)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(payload)
            header + encrypted
        } finally {
            key.fill(0)
        }
    }

    /** Authenticates and decrypts an envelope; wrong passwords fail closed. */
    fun open(envelope: ByteArray, password: CharArray): ByteArray {
        require(envelope.size >= MAGIC.length + 4 + 4 + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES + 16) {
            "Файл резервної копії пошкоджений або неповний."
        }
        val input = DataInputStream(ByteArrayInputStream(envelope))
        val magic = ByteArray(MAGIC.length).also(input::readFully)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Це не файл резервної копії СтеляПро." }
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "Непідтримувана версія резервної копії: $version." }
        val iterations = input.readInt()
        require(iterations in 200_000..1_000_000) { "Некоректні параметри захисту файлу." }
        val saltSize = input.readUnsignedByte()
        val nonceSize = input.readUnsignedByte()
        val payloadSize = input.readInt()
        require(saltSize == SALT_BYTES && nonceSize == NONCE_BYTES) { "Некоректний заголовок резервної копії." }
        require(payloadSize in 1..MAX_PAYLOAD_BYTES) { "Некоректний розмір резервної копії." }
        require(envelope.size.toLong() == (MAGIC.length + 4 + 4 + 1 + 1 + 4 + saltSize + nonceSize + payloadSize + 16).toLong()) {
            "Файл резервної копії обрізаний або містить зайві дані."
        }

        val salt = ByteArray(saltSize).also(input::readFully)
        val nonce = ByteArray(nonceSize).also(input::readFully)
        val headerSize = envelope.size - input.available()
        val header = envelope.copyOfRange(0, headerSize)
        val encrypted = envelope.copyOfRange(headerSize, envelope.size)
        val key = deriveKey(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(encrypted)
        } catch (_: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Неправильний пароль або файл резервної копії пошкоджений.")
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
