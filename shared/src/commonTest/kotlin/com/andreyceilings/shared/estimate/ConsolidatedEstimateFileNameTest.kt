package com.andreyceilings.shared.estimate

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsolidatedEstimateFileNameTest {
    @Test fun usesTheProjectTitleAsABoundedSafeSlug() {
        fun name(title: String) = ConsolidatedEstimateDocument(title, null, emptyList(), 0).fileName

        assertEquals("zvedenyi-koshtorys-Квартира-Центр.pdf", name(" Квартира / Центр "))
        assertEquals("zvedenyi-koshtorys-Будинок-Вулиця.pdf", name("../Будинок\\Вулиця"))
        assertEquals("zvedenyi-koshtorys-${"Ж".repeat(56)}.pdf", name("Ж".repeat(200)))
        assertEquals("zvedenyi-koshtorys.pdf", name(" / .. _ \\ "))
        assertEquals("zvedenyi-koshtorys.pdf", name("12345678-1234-4ABC-8DEF-0123456789AB"))
        assertEquals("zvedenyi-koshtorys-Квартира.pdf", name("Квартира / 12345678-1234-4abc-8def-0123456789ab"))
    }
}
