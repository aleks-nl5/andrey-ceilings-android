package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.assertEquals

/** Turns a freshly seeded database into the exact v24 layout without touching business rows. */
fun removeEmptyCalendarSchema(driver: SqlDriver) {
    val present = driver.executeQuery(null,
        "SELECT 1 FROM sqlite_master WHERE name = 'calendar_roots'", { cursor ->
            QueryResult.Value(cursor.next().value)
        }, 0).value
    if (!present) return
    assertEquals(0L, driver.executeQuery(null, "SELECT count(*) FROM calendar_roots", { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0))
    }, 0).value)
    for (name in listOf("type_keep_standard", "project_link_guard", "client_link_guard", "ceiling_link_guard")) {
        driver.execute(null, "DROP TRIGGER calendar_$name", 0)
    }
    for (name in listOf("exceptions", "ceilings", "days", "rules", "works", "roots", "settings", "types")) {
        driver.execute(null, "DROP TABLE calendar_$name", 0)
    }
}
