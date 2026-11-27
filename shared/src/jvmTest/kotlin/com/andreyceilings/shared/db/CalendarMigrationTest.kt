package com.andreyceilings.shared.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.calendar.*
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CalendarMigrationTest {
    @Test fun populatedV24RollbackMigrationAndReopen() = upgrade {
        JdbcSqliteDriver("jdbc:sqlite:${it.absolutePath}")
    }

    @Test fun encryptedWindowsMigrationAndCalendarReopen() {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return
        val key = ByteArray(32) { 25 }
        try { upgrade { SqlCipherWindowsDriverFactory.open(it, key) } }
        finally { key.fill(0) }
    }

    private fun upgrade(open: (File) -> SqlDriver) {
        val directory = Files.createTempDirectory("work-calendar-migration").toFile()
        val file = directory.resolve("work.db")
        try {
            val (project, revision, bytes) = open(file).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                PriceCatalogSchema.create(driver)
                val db = PrototypeDatabase(driver)
                val source = MeasurementPricingFixture.seed(db)
                val quotes = ImmutableEstimateRepository(db)
                val quote = quotes.saveIndividual(FrozenIndividualEstimateProof.command(db, source), 2026, 830)
                val payload = assertNotNull(quotes.revision(quote.initialRevisionUuid)).detailsJson
                removeEmptyCalendarSchema(driver)
                driver.execute(null, "PRAGMA user_version = 24", 0)
                PriceCatalogSchema.verifyStructure(driver, 24)
                Triple(source.projectId, quote.initialRevisionUuid, payload)
            }
            val date = LocalDate(2026, 12, 1)
            val workKey = open(file).use { driver ->
                driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                assertFailsWith<IllegalStateException> {
                    PriceCatalogSchema.migrate(driver, 24, 25, AfterVersion(24) { error("Injected migration failure") })
                }
                PriceCatalogSchema.verifyStructure(driver, 24)
                assertEquals(24L, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                openDesktopPriceCatalog(driver, true)
                assertEquals(25L, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                val db = PrototypeDatabase(driver)
                val calendar = CalendarRepository(db)
                assertEquals(4, calendar.types().size)
                assertTrue(calendar.period(WorkDateRange(date, date), TimeZone.UTC).isEmpty())
                assertEquals(bytes, assertNotNull(ImmutableEstimateRepository(db).revision(revision)).detailsJson)
                val uuid = assertNotNull(ProjectRepository(db).find(project)).uuid
                calendar.saveSettings(CalendarSettings(TimeZone.of("Europe/Kyiv"), null, emptySet()))
                assertIs<CalendarSaveResult.Saved>(calendar.save(CalendarSave(
                    CalendarWorkDraft(date, WorkSchedule(WorkTimeMode.DATE_ONLY, TimeZone.UTC, listOf(WorkDay(0))),
                        "Calendar-only content", "MEASURE", projectUuid = uuid)
                ))).key
            }
            open(file).use { driver ->
                openDesktopPriceCatalog(driver, true)
                val calendar = CalendarRepository(PrototypeDatabase(driver))
                assertEquals("Calendar-only content", calendar.exact(workKey)?.draft?.title)
                assertEquals("Europe/Kyiv", calendar.settings().zone.id)
                driver.execute(null, "DROP INDEX calendar_single_range", 0)
                assertFails { verifyCalendarStructure(driver, 25) }
            }
        } finally { directory.deleteRecursively() }
    }
}
