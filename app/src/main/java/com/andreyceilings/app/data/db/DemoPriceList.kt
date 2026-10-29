package com.andreyceilings.app.data.db

/** Illustrative starter prices, inserted only into an empty price list. */
internal object DemoPriceList {
    val items = listOf(
        PriceItemEntity(code = "canvas.pvc_matte", title = "ПВХ полотно матове", category = "Полотна", unit = "м²", price = 290.0),
        PriceItemEntity(code = "canvas.pvc_glossy", title = "ПВХ полотно глянцеве", category = "Полотна", unit = "м²", price = 310.0),
        PriceItemEntity(code = "canvas.pvc_satin", title = "ПВХ полотно сатинове", category = "Полотна", unit = "м²", price = 310.0),
        PriceItemEntity(code = "canvas.fabric", title = "Тканинне полотно", category = "Полотна", unit = "м²", price = 890.0),
        PriceItemEntity(code = "canvas.fireproof_km2", title = "Негорюче полотно KM2", category = "Полотна", unit = "м²", price = 480.0),
        PriceItemEntity(code = "canvas.acoustic", title = "Акустичне полотно", category = "Полотна", unit = "м²", price = 980.0),
        PriceItemEntity(code = "profile.standard_alu", title = "Стандартний алюмінієвий профіль", category = "Профілі", unit = "м.п.", price = 130.0),
        PriceItemEntity(code = "profile.pvc", title = "ПВХ профіль", category = "Профілі", unit = "м.п.", price = 90.0),
        PriceItemEntity(code = "profile.shadow_kraab", title = "Тіньовий профіль KRAAB", category = "Профілі", unit = "м.п.", price = 380.0),
        PriceItemEntity(code = "profile.floating_led", title = "Профіль для парящої стелі LED", category = "Профілі", unit = "м.п.", price = 420.0),
        PriceItemEntity(code = "profile.slott_tracks", title = "Слот / трекова система", category = "Профілі", unit = "м.п.", price = 550.0),
        PriceItemEntity(code = "work.multilevel", title = "Багаторівнева конструкція", category = "Роботи", unit = "м.п.", price = 650.0),
        PriceItemEntity(code = "work.curved", title = "Криволінійна конструкція", category = "Роботи", unit = "м.п.", price = 350.0),
        PriceItemEntity(code = "light.line", title = "Світлова лінія", category = "Освітлення", unit = "м.п.", price = 850.0),
        PriceItemEntity(code = "light.chandelier", title = "Монтаж люстри", category = "Освітлення", unit = "шт.", price = 350.0),
        PriceItemEntity(code = "light.spot_gx53", title = "Точковий світильник GX53", category = "Освітлення", unit = "шт.", price = 220.0),
        PriceItemEntity(code = "work.pipe_pass", title = "Обхід труби", category = "Додаткові роботи", unit = "шт.", price = 180.0),
        PriceItemEntity(code = "work.curtain_niche", title = "Ніша для штор", category = "Додаткові роботи", unit = "м.п.", price = 450.0),
        PriceItemEntity(code = "work.shadow_profile", title = "Додатковий тіньовий профіль", category = "Додаткові роботи", unit = "м.п.", price = 380.0),
        PriceItemEntity(code = "work.floating_ceiling", title = "Додатковий парящий профіль", category = "Додаткові роботи", unit = "м.п.", price = 420.0),
        PriceItemEntity(code = "work.extra_corner", title = "Додатковий кут", category = "Додаткові роботи", unit = "шт.", price = 90.0),
        PriceItemEntity(code = "work.hard_wall", title = "Складна стіна", category = "Додаткові роботи", unit = "м.п.", price = 220.0)
    )

    suspend fun seedIfEmpty(dao: PriceItemDao) {
        if (dao.count() == 0) items.forEach { dao.upsert(it) }
    }
}
