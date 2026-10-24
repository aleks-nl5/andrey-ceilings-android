package com.andreyceilings.app.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupPayloadTest {
    @Test
    fun encodeDecodePreservesDatabaseAndPassphrase() {
        val database = byteArrayOf(1, 2, 3, 4, 5)
        val passphrase = ByteArray(32) { (it + 1).toByte() }
        val decoded = PortableBackupPayload.decode(PortableBackupPayload.encode(database, passphrase))
        assertArrayEquals(database, decoded.database)
        assertArrayEquals(passphrase, decoded.passphrase)
    }

    @Test
    fun rejectsInvalidPassphraseLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload.encode(byteArrayOf(1), byteArrayOf(1, 2))
        }
    }

    @Test
    fun rejectsTruncatedPayload() {
        val valid = PortableBackupPayload.encode(byteArrayOf(1, 2, 3), ByteArray(32) { 7 })
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload.decode(valid.copyOf(valid.size - 1))
        }
    }
}
