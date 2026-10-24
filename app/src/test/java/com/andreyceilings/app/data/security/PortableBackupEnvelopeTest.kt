package com.andreyceilings.app.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupEnvelopeTest {
    private val password = "correct horse battery".toCharArray()
    private val payload = "СтеляПро portable backup test payload".toByteArray(Charsets.UTF_8)

    @Test
    fun roundTripRestoresOriginalPayload() {
        val sealed = PortableBackupEnvelope.seal(payload, password)
        assertArrayEquals(payload, PortableBackupEnvelope.open(sealed, password))
    }

    @Test
    fun eachBackupUsesFreshRandomSaltAndNonce() {
        val first = PortableBackupEnvelope.seal(payload, password)
        val second = PortableBackupEnvelope.seal(payload, password)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun wrongPasswordIsRejected() {
        val sealed = PortableBackupEnvelope.seal(payload, password)
        assertThrows(Exception::class.java) {
            PortableBackupEnvelope.open(sealed, "incorrect password".toCharArray())
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sealed = PortableBackupEnvelope.seal(payload, password)
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            PortableBackupEnvelope.open(sealed, password)
        }
    }

    @Test
    fun truncatedEnvelopeIsRejected() {
        val sealed = PortableBackupEnvelope.seal(payload, password)
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupEnvelope.open(sealed.copyOf(sealed.size - 1), password)
        }
    }
}
