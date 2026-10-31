package com.andreyceilings.shared.prices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceCatalogRulesTest {
    private val catalog = listOf(
        PriceCatalogItem(1, "canvas.pvc_matte", "ПВХ полотно матове", "Полотна", "м²", 290.0),
        PriceCatalogItem(2, "profile.alu", "Алюмінієвий профіль", "Профілі", "м.п.", 130.0),
        PriceCatalogItem(3, "light.spot", "Точковий світильник", "Освітлення", "шт.", 220.0)
    )

    @Test fun salePriceIsTheOnlyRequiredFinancialField() {
        assertNull(PriceCatalogRules.validate("Полотно", "Полотна", "м²", "290,50"))
        assertEquals(290.5, PriceCatalogRules.parseSalePrice("290,50"))
        assertEquals(290.5, PriceCatalogRules.parseSalePrice("290.50"))
        assertEquals(0.0, PriceCatalogRules.parseSalePrice("0"))
    }

    @Test fun rejectInvalidSalePriceWithoutSilentRounding() {
        listOf("", " ", "-10", "10,999", "NaN", "Infinity", "1e6",
            "1000000000", "50abc", "1.2.3").forEach { value ->
            assertNull(PriceCatalogRules.parseSalePrice(value), "Invalid price: $value")
        }
    }

    @Test fun requiredTextFieldsHaveUserReadableValidation() {
        assertTrue(PriceCatalogRules.validate("", "Полотна", "м²", "290") != null)
        assertTrue(PriceCatalogRules.validate("Полотно", "", "м²", "290") != null)
        assertTrue(PriceCatalogRules.validate("Полотно", "Полотна", "", "290") != null)
        assertTrue(PriceCatalogRules.validate("Полотно", "Полотна", "м²", "") != null)
    }

    @Test fun filterByCategoryTitleAndStableCode() {
        assertEquals(3, PriceCatalogRules.filter(catalog, "", null).size)
        assertEquals(listOf(1L), PriceCatalogRules.filter(catalog, "пВх", null).map { it.id })
        assertEquals(listOf(1L), PriceCatalogRules.filter(catalog, "CANVAS.PVC", null).map { it.id })
        assertEquals(listOf(3L), PriceCatalogRules.filter(catalog, "", "Освітлення").map { it.id })
        assertTrue(PriceCatalogRules.filter(catalog, "профіль", "Полотна").isEmpty())
    }


    @Test fun categoryDropdownContainsStandardAndExistingCustomCategories() {
        val entries = catalog + PriceCatalogItem(
            4, "custom.4", "Тестова послуга", "Особливі роботи", "шт.", 75.0
        )
        val options = PriceCatalogRules.categoryOptions(entries)
        assertEquals(PriceCatalogRules.standardCategories,
            options.take(PriceCatalogRules.standardCategories.size))
        assertTrue("Особливі роботи" in options)
        assertEquals(options.size, options.distinct().size)
        // Merely reading the dropdown must not change a category or a price.
        assertEquals("Особливі роботи", entries.last().category)
        assertEquals(75.0, entries.last().price)
    }

    @Test fun unitDropdownIncludesStandardAndPersistedCustomUnits() {
        val units = PriceCatalogRules.unitOptions(catalog + PriceCatalogItem(
            4, "custom.4", "Послуга за годину", "Роботи", "год.", 180.0
        ))
        assertEquals(PriceCatalogRules.standardUnits, units.take(PriceCatalogRules.standardUnits.size))
        assertTrue("год." in units)
        assertEquals(units.size, units.distinct().size)
    }

    @Test fun categoryNamesAndDisplayDoNotChangePrices() {
        assertEquals(listOf("Освітлення", "Полотна", "Профілі"),
            PriceCatalogRules.categories(catalog))
        assertEquals("290,00", PriceCatalogRules.formatSalePrice(catalog.first().price))
        assertEquals("—", PriceCatalogRules.formatSalePrice(Double.NaN))
    }
}
