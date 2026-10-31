package com.andreyceilings.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPriceDatabaseTest {
    @Test fun encryptedPriceCatalogRoundTripAndWrongPasswordFailClosed() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

        val previousHome = System.getProperty("user.home")
        val home = Files.createTempDirectory("ceilings-cmp-price-windows-").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            DesktopPriceDatabase.open("strong-demo-test-password".toCharArray()).use { db ->
                db.prices.create("custom.example", "Демонстраційний профіль",
                    "Профілі", "м.п.", 123.0, 123L)
                assertEquals(1, db.prices.all().size)
            }

            val file = File(home, ".andrey-ceilings/catalog.db")
            assertTrue(file.isFile && file.length() > 16)
            val before = file.readBytes()
            assertFalse(before.copyOfRange(0, 16)
                .contentEquals("SQLite format 3\u0000".encodeToByteArray()))

            assertFails {
                DesktopPriceDatabase.open("incorrect-test-password".toCharArray()).use { it.prices.all() }
            }
            assertTrue(before.contentEquals(file.readBytes()),
                "Wrong password must never replace or mutate the encrypted database")

            DesktopPriceDatabase.open("strong-demo-test-password".toCharArray()).use { db ->
                assertEquals("custom.example", db.prices.all().single().code)
                assertEquals(123.0, db.prices.all().single().price)
            }
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }
}
