package com.andreyceilings.app.data.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary payload stored inside PortableBackupEnvelope.
 * Contains the SQLCipher database bytes and its passphrase so a restore can
 * re-wrap the passphrase with the destination installation's Android Keystore.
 */
internal object PortableBackupPayload {
    private const val MAGIC = "STELDB01"
    private const val VERSION = 1
    private const val MAX_DATABASE_BYTES = 512 * 1024 * 1024
    private const val PASSPHRASE_BYTES = 32

    data class Contents(val database: ByteArray, val passphrase: ByteArray)

    fun encode(database: ByteArray, passphrase: ByteArray): ByteArray {
        require(database.isNotEmpty() && database.size <= MAX_DATABASE_BYTES) {
            "Некоректний розмір файлу бази даних."
        }
        require(passphrase.size == PASSPHRASE_BYTES) {
            "Некоректний ключ бази даних."
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeInt(VERSION)
                out.writeInt(database.size)
                out.write(passphrase)
                out.write(database)
            }
            bytes.toByteArray()
        }
    }

    fun decode(payload: ByteArray): Contents {
        require(payload.size >= MAGIC.length + 4 + 4 + PASSPHRASE_BYTES + 1) {
            "Дані резервної копії неповні."
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        val magic = ByteArray(MAGIC.length).also(input::readFully)
        require(String(magic, Charsets.US_ASCII) == MAGIC) {
            "Невідомий формат даних резервної копії."
        }
        require(input.readInt() == VERSION) { "Непідтримувана версія даних резервної копії." }
        val dbSize = input.readInt()
        require(dbSize in 1..MAX_DATABASE_BYTES) { "Некоректний розмір бази даних у резервній копії." }
        require(input.available().toLong() == PASSPHRASE_BYTES.toLong() + dbSize) {
            "Файл резервної копії пошкоджений або містить зайві дані."
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(input::readFully)
        val database = ByteArray(dbSize).also(input::readFully)
        return Contents(database, passphrase)
    }
}
