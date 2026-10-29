package com.andreyceilings.shared.db

import kotlin.random.Random

/**
 * Stable version-4 UUID for future cross-device record identity.
 * Local integer IDs remain database/UI-local foreign keys.
 * UUIDs are identifiers, never secrets or authentication credentials.
 */
fun newRecordUuid(): String {
    val bytes = Random.Default.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = "0123456789abcdef"
    return buildString(36) {
        bytes.forEachIndexed { i, value ->
            if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
            val n = value.toInt() and 255
            append(hex[n ushr 4]); append(hex[n and 15])
        }
    }
}
