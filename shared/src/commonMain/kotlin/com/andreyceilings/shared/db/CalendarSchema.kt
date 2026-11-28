package com.andreyceilings.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.TimeZone
import org.kotlincrypto.hash.sha2.SHA256

private fun calendarDefinitions(driver: SqlDriver): List<String> = driver.executeQuery(
    null,
    "SELECT sql FROM sqlite_master WHERE name GLOB 'calendar_*' ORDER BY name",
    { cursor ->
        val definitions = mutableListOf<String>()
        while (cursor.next().value) {
            definitions += checkNotNull(cursor.getString(0)).replace(Regex("\\s+"), " ").trim().removeSuffix(";")
        }
        QueryResult.Value(definitions)
    },
    0,
).value

internal fun calendarSchemaExists(driver: SqlDriver): Boolean = calendarDefinitions(driver).isNotEmpty()

fun verifyCalendarStructure(driver: SqlDriver, version: Long) {
    val definitions = calendarDefinitions(driver)
    if (version < 25) {
        check(definitions.isEmpty()) { "Calendar schema requires version 25" }
        return
    }
    check(definitions.size == if (version >= 26) 24 else 19)
    val signature = SHA256().digest(definitions.joinToString("\n").encodeToByteArray()).toHexString()
    check(signature == (if (version >= 26) CALENDAR_V26_SIGNATURE else CALENDAR_V25_SIGNATURE)) { "Invalid calendar schema" }
}

fun verifyCalendarContents(driver: SqlDriver, version: Long = PriceCatalogSchema.version) {
    fun count(sql: String): Long = driver.executeQuery(null, sql, { cursor ->
        check(cursor.next().value)
        QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }, 0).value

    if (version >= 26) {
        check(count("SELECT count(*) FROM calendar_integration_settings") == 1L)
        check(count("""
            SELECT count(*) FROM calendar_type_reminders r JOIN calendar_type_integrations t USING(typeCode)
            WHERE t.customReminders = 0
        """.trimIndent()) == 0L)
        check(count("""
            SELECT count(*) FROM calendar_work_reminders r JOIN calendar_work_integrations w
                USING(rootUuid, originalDay, following)
            WHERE w.reminderMode IS NULL OR w.reminderMode NOT IN ('ADD', 'REPLACE')
        """.trimIndent()) == 0L)
    }
    check(count("SELECT count(*) FROM calendar_settings") == 1L)
    check(count("SELECT count(*) FROM calendar_types WHERE code IN ('MEASURE','INSTALL','DELIVER','OTHER')") == 4L)
    check(count("""
        SELECT count(*) FROM calendar_works w LEFT JOIN calendar_rules r ON r.workUuid = w.uuid
        WHERE (w.kind = 'SEGMENT') != (r.workUuid IS NOT NULL)
            OR NOT EXISTS (SELECT 1 FROM calendar_days d WHERE d.workUuid = w.uuid AND d.dayOffset = 0)
            OR lastDay != dateDay + (SELECT max(dayOffset) FROM calendar_days d WHERE d.workUuid = w.uuid)
            OR EXISTS (SELECT 1 FROM calendar_days d WHERE d.workUuid = w.uuid
                AND ((w.timeMode = 'TIMED') != (d.startSecond IS NOT NULL)
                    OR (d.overrideFields != 0 AND (w.kind != 'OVERRIDE' OR w.timeMode != 'TIMED'))))
            OR (r.untilDay IS NOT NULL AND r.untilDay < w.dateDay)
            OR (w.explicitCeilings = 0 AND EXISTS (SELECT 1 FROM calendar_ceilings c WHERE c.workUuid = w.uuid))
    """.trimIndent()) == 0L) { "Invalid calendar schedule" }
    check(count("""
        SELECT count(*) FROM calendar_exceptions e
        JOIN calendar_works s ON s.uuid = e.segmentUuid
        LEFT JOIN calendar_works o ON o.uuid = e.overrideUuid
        WHERE s.kind != 'SEGMENT' OR (o.uuid IS NOT NULL AND o.kind NOT IN ('OVERRIDE','SINGLE'))
    """.trimIndent()) == 0L) { "Invalid calendar exception" }
    driver.executeQuery(null, """
        SELECT zoneId FROM calendar_settings WHERE zoneId IS NOT NULL
        UNION SELECT zoneId FROM calendar_works
    """.trimIndent(), { cursor ->
        while (cursor.next().value) TimeZone.of(checkNotNull(cursor.getString(0)))
        QueryResult.Unit
    }, 0).value
}

private const val CALENDAR_V25_SIGNATURE = "cae9557977546a56ad7f53399ac68c0e8296b7d6244eeb1bb619d1cc91d667fa"

private const val CALENDAR_V26_SIGNATURE = "5c4c0e288607af703de07c931ae843ac6e6e6c57012a2f4c3b1177c1ecf9a171"
