package com.andreyceilings.shared.objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactMessengerLinksTest {
    @Test fun explicitCountryCodeAndFormattedNumberProduceConsistentLinks() {
        val plus = "+380 (67) 123-45-67"
        val prefix = "00380 67 123 45 67"
        val expected = listOf("whatsapp://send?phone=380671234567", "https://wa.me/380671234567")
        assertEquals(expected, messengerLinks(ContactAction.WHATSAPP, plus))
        assertEquals(expected, messengerLinks(ContactAction.WHATSAPP, prefix))
        assertEquals(listOf("tg://resolve?phone=380671234567", "https://t.me/+380671234567"),
            messengerLinks(ContactAction.TELEGRAM, plus))
        assertEquals(listOf("viber://chat?number=%2B380671234567"), messengerLinks(ContactAction.VIBER, plus))
    }

    @Test fun invalidOrLocalNumbersNeverInventCountryCode() {
        for (number in listOf("0671234567", "+0123456789", "+380abc671234567", "+12", "")) {
            assertTrue(messengerLinks(ContactAction.WHATSAPP, number).isEmpty())
        }
    }

    @Test fun fallbackOnlyRunsAfterNativeFailure() {
        val links = messengerLinks(ContactAction.TELEGRAM, "+380671234567")
        val attempts = mutableListOf<String>()
        assertTrue(tryOpenContactLinks(links) { uri ->
            attempts += uri
            if (uri == links.first()) error("no native handler")
        })
        assertEquals(links, attempts)
        attempts.clear()
        assertTrue(tryOpenContactLinks(links) { attempts += it })
        assertEquals(listOf(links.first()), attempts)
        assertFalse(tryOpenContactLinks(links) { error("no handler") })
    }
}
