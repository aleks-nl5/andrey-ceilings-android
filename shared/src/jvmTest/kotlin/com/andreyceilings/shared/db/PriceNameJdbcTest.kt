package com.andreyceilings.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.prices.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PriceNameJdbcTest {
    @Test fun unicodeNamesAndDatabaseGuards() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PriceCatalogSchema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            PriceCatalogPersistenceProof.starter(PrototypeDatabase(driver))
            PriceNameUniquenessProof.crud(driver)
        }
    }

    @Test fun populatedV5UpgradeRollbackInterruptionReopenAndConcurrentWriters() =
        PriceNameFileProof.run { file -> JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}") }
}

/** Reused without skips inside the Windows-only native SQLCipher test. */
object PriceNameFileProof {
    fun run(open: (File) -> SqlDriver) {
        for (scenario in listOf("ok", "group", "item", "empty", "interrupted")) {
            val file = Files.createTempFile("price-names-", ".db").toFile()
            try {
                val before: List<List<String?>>
                val oldSchema: List<List<String?>>
                open(file).use { driver ->
                    PriceNameMigrationProof.v5Schema.create(driver)
                    driver.execute(null, "PRAGMA user_version = 5", 0)
                    PriceNameMigrationProof.populate(driver, when (scenario) {
                        "group" -> CatalogNameKind.GROUP
                        "item" -> CatalogNameKind.ITEM
                        else -> null
                    }, scenario == "empty")
                    before = PriceNameMigrationProof.snapshot(driver)
                    oldSchema = PriceNameMigrationProof.schema(driver)
                }
                if (scenario != "ok") {
                    open(file).use { driver ->
                        val failure = assertFails {
                            openDesktopPriceCatalog(if (scenario == "interrupted") PriceNameMigrationProof.failingBackfill(driver) else driver, true)
                        }
                        when (scenario) {
                            "group", "item", "empty" -> {
                                val conflict = assertIs<CatalogNameMigrationConflict>(failure)
                                assertEquals(if (scenario == "group") CatalogNameKind.GROUP else CatalogNameKind.ITEM, conflict.kind)
                                assertEquals(scenario == "empty", conflict.emptyName)
                                assertTrue(conflict.recordUuids.isNotEmpty())
                            }
                        }
                        assertEquals(oldSchema, PriceNameMigrationProof.schema(driver))
                        assertEquals(before, PriceNameMigrationProof.snapshot(driver))
                    }
                    // Reopening the actual v5 file must retain its exact schema/data/version.
                    open(file).use { driver ->
                        assertEquals(oldSchema, PriceNameMigrationProof.schema(driver))
                        assertEquals(before, PriceNameMigrationProof.snapshot(driver))
                    }
                    if (scenario != "interrupted") continue
                }
                open(file).use { driver ->
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0)
                    openDesktopPriceCatalog(driver, true)
                    assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
                    PriceNameMigrationProof.assertMigrated(driver, before)
                }
                open(file).use { driver ->
                    val v6Schema = PriceNameMigrationProof.schema(driver)
                    openDesktopPriceCatalog(driver, true)
                    PriceNameMigrationProof.assertMigrated(driver, before)
                    assertEquals(v6Schema, PriceNameMigrationProof.schema(driver))
                }
            } finally { file.delete() }
        }
        competingWrites(open)
    }

    private fun competingWrites(open: (File) -> SqlDriver) {
        val file = Files.createTempFile("price-names-writers-", ".db").toFile()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val group: String
            open(file).use { driver ->
                PriceCatalogSchema.create(driver)
                group = PriceGroupRepository(PrototypeDatabase(driver)).create("Concurrency", 200, "шт.")
            }
            for (scenario in listOf("group", "item", "ensure")) {
                val start = CountDownLatch(1)
                val ready = CountDownLatch(2)
                val tasks = (1..2).map { index -> pool.submit<Result<String>> {
                    open(file).use { driver ->
                        driver.execute(null, "PRAGMA busy_timeout = 15000", 0)
                        val db = PrototypeDatabase(driver)
                        val groups = PriceGroupRepository(db)
                        ready.countDown()
                        check(start.await(20, TimeUnit.SECONDS))
                        val name = if (index == 1) " ҐЄІЇ $scenario " else "ґєії $scenario"
                        runCatching { when (scenario) {
                            "group" -> groups.create(name, 201)
                            "item" -> PriceRepository(db).createInGroup("code.$index", name, group, 1.0, 201).toString()
                            else -> groups.ensureByName(name, 201)
                        } }
                    }
                } }
                assertTrue(ready.await(20, TimeUnit.SECONDS))
                start.countDown()
                val results = tasks.map { it.get(30, TimeUnit.SECONDS) }
                if (scenario == "ensure") {
                    assertTrue(results.all { it.isSuccess })
                    assertEquals(1, results.map { it.getOrThrow() }.distinct().size)
                } else {
                    assertEquals(1, results.count { it.isSuccess })
                    val failure = results.single { it.isFailure }.exceptionOrNull()
                    if (scenario == "group") assertIs<DuplicateGroupName>(failure) else assertIs<DuplicateItemTitle>(failure)
                }
                open(file).use { driver ->
                    val table = if (scenario == "item") "price_items" else "price_groups"
                    val key = if (scenario == "item") "titleKey" else "nameKey"
                    assertEquals(1L, PriceNameUniquenessProof.scalar(driver, "SELECT count(*) FROM $table WHERE $key='ґєії $scenario' AND deletedAt IS NULL"))
                }
            }
        } finally { pool.shutdownNow(); pool.awaitTermination(30, TimeUnit.SECONDS); file.delete() }
    }
}
