package com.andreyceilings.shared.prices

import com.andreyceilings.shared.db.*

data class StarterPriceItem(
    val code: String, val title: String, val category: String, val unit: String,
    val price: Double, val quantityMode: QuantityMode,
    val placementKind: PlacementKind = PlacementKind.NONE
)

/** Illustrative prices copied verbatim from DemoPriceList; not market prices. */
object StarterPriceCatalog {
    val groupNames = listOf("Полотна", "Профілі", "Освітлення", "Роботи", "Додаткові роботи")
    val items = listOf(
        StarterPriceItem(code = "canvas.pvc_matte", title = "ПВХ полотно матове", category = "Полотна", unit = "м²", price = 290.0, quantityMode = QuantityMode.CEILING_AREA),
        StarterPriceItem(code = "canvas.pvc_glossy", title = "ПВХ полотно глянцеве", category = "Полотна", unit = "м²", price = 310.0, quantityMode = QuantityMode.CEILING_AREA),
        StarterPriceItem(code = "canvas.pvc_satin", title = "ПВХ полотно сатинове", category = "Полотна", unit = "м²", price = 310.0, quantityMode = QuantityMode.CEILING_AREA),
        StarterPriceItem(code = "canvas.fabric", title = "Тканинне полотно", category = "Полотна", unit = "м²", price = 890.0, quantityMode = QuantityMode.CEILING_AREA),
        StarterPriceItem(code = "canvas.fireproof_km2", title = "Негорюче полотно KM2", category = "Полотна", unit = "м²", price = 480.0, quantityMode = QuantityMode.CEILING_AREA),
        StarterPriceItem(code = "canvas.acoustic", title = "Акустичне полотно", category = "Полотна", unit = "м²", price = 980.0, quantityMode = QuantityMode.CEILING_AREA),
        StarterPriceItem(code = "profile.standard_alu", title = "Стандартний алюмінієвий профіль", category = "Профілі", unit = "м.п.", price = 130.0, quantityMode = QuantityMode.CEILING_PERIMETER),
        StarterPriceItem(code = "profile.pvc", title = "ПВХ профіль", category = "Профілі", unit = "м.п.", price = 90.0, quantityMode = QuantityMode.CEILING_PERIMETER),
        StarterPriceItem(code = "profile.shadow_kraab", title = "Тіньовий профіль KRAAB", category = "Профілі", unit = "м.п.", price = 380.0, quantityMode = QuantityMode.CEILING_PERIMETER),
        StarterPriceItem(code = "profile.floating_led", title = "Профіль для парящої стелі LED", category = "Профілі", unit = "м.п.", price = 420.0, quantityMode = QuantityMode.CEILING_PERIMETER),
        StarterPriceItem(code = "profile.slott_tracks", title = "Слот / трекова система", category = "Профілі", unit = "м.п.", price = 550.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "work.multilevel", title = "Багаторівнева конструкція", category = "Роботи", unit = "м.п.", price = 650.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "work.curved", title = "Криволінійна конструкція", category = "Роботи", unit = "м.п.", price = 350.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "light.line", title = "Світлова лінія", category = "Освітлення", unit = "м.п.", price = 850.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "light.chandelier", title = "Монтаж люстри", category = "Освітлення", unit = "шт.", price = 350.0, quantityMode = QuantityMode.MANUAL, placementKind = PlacementKind.POINT),
        StarterPriceItem(code = "light.spot", title = "Точковий світильник", category = "Освітлення", unit = "шт.", price = 220.0, quantityMode = QuantityMode.MANUAL, placementKind = PlacementKind.POINT),
        StarterPriceItem(code = "work.pipe_pass", title = "Обхід труби", category = "Додаткові роботи", unit = "шт.", price = 180.0, quantityMode = QuantityMode.MANUAL),
        StarterPriceItem(code = "work.curtain_niche", title = "Ніша для штор", category = "Додаткові роботи", unit = "м.п.", price = 450.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "work.shadow_profile", title = "Додатковий тіньовий профіль", category = "Додаткові роботи", unit = "м.п.", price = 380.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "work.floating_ceiling", title = "Додатковий парящий профіль", category = "Додаткові роботи", unit = "м.п.", price = 420.0, quantityMode = QuantityMode.ENTERED_LENGTH),
        StarterPriceItem(code = "work.extra_corner", title = "Додатковий кут", category = "Додаткові роботи", unit = "шт.", price = 90.0, quantityMode = QuantityMode.MANUAL),
        StarterPriceItem(code = "work.hard_wall", title = "Складна стіна", category = "Додаткові роботи", unit = "м.п.", price = 220.0, quantityMode = QuantityMode.ENTERED_LENGTH)
    )


    /** A persisted marker, never row count, controls this single shared bootstrap. */
    fun initialize(database: PrototypeDatabase) = database.transaction {
        initializeInTransaction(database)
    }

    /** Caller must own the physical transaction (including SQLiter schema callbacks). */
    internal fun initializeInTransaction(database: PrototypeDatabase) {
        val queries = database.priceGroupsQueries
        // Acquire SQLite's writer lock BEFORE reading the marker, including concurrent opens.
        queries.claimCatalogInitialization()
        when (queries.selectSeedVersion().executeAsOne()) {
            1L -> return
            0L -> Unit
            else -> error("Непідтримувана версія початкового каталогу.")
        }
        val groups = PriceGroupRepository(database)
        val ids = groupNames.associateWith { name ->
            when (name) {
                "Полотна" -> groups.create(name, 1L, "м²", QuantityMode.CEILING_AREA)
                // Tracks and perimeter profiles share this group: conservative defaults.
                "Профілі" -> groups.create(name, 1L, "м.п.")
                else -> groups.create(name, 1L)
            }
        }
        items.forEach { item ->
            PriceParameters.validate(item.unit, item.quantityMode)
            queries.insertConfiguredItem(item.code, item.title, normalizedNameKey(item.title), ids.getValue(item.category), item.unit,
                item.quantityMode.value, item.placementKind.value, item.price, 1L, newRecordUuid()).executeAsOne()
        }
        queries.finishCatalogInitialization()
    }
}
