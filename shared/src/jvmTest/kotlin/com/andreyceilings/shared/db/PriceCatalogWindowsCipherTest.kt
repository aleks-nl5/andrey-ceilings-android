package com.andreyceilings.shared.db

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TestName
import kotlin.test.*

class PriceCatalogWindowsCipherTest {
    // Count real opens on the expensive representative suite without logging key, SQL,
    // database names or paths. Every JUnit test instance owns an independent counter.
    private val nativeMetrics = SqlCipherWindowsConnectionMetrics()

    @get:Rule
    val testName = TestName()

    @AfterTest fun printConnectionCounts() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val opens = nativeMetrics.physicalOpens.get()
        val closes = nativeMetrics.physicalCloses.get()
        val guardsPassed = nativeMetrics.encryptedSchemaGuardsPassed.get()
        val guardAttempts = nativeMetrics.encryptedSchemaGuardAttempts.get()
        println(
            "PriceCatalogWindowsCipherTest native JDBC metrics: " +
                "testCase=${testName.methodName}, " +
                "physicalOpens=$opens, physicalCloses=$closes, " +
                "schemaGuardsPassed=$guardsPassed, schemaGuardAttempts=$guardAttempts, " +
                "guardTotalMs=${nativeMetrics.encryptedSchemaGuardNanos.get() / 1_000_000}, " +
                "logicalStatements=${nativeMetrics.logicalStatements.get()}, " +
                "pinnedStatements=${nativeMetrics.statementsOnPinnedTransaction.get()}, " +
                "outerBegins=${nativeMetrics.outerTransactionsBegun.get()}"
        )
        assertTrue(opens > 0, "The Windows test must open a physical encrypted connection")
        assertEquals(opens, closes, "Each physical connection must be closed")
        assertEquals(opens, guardAttempts, "Each physical connection must attempt the encrypted schema guard")
        assertEquals(opens, guardsPassed, "Each physical connection must pass the encrypted schema guard")
    }
    @Test fun encryptedPointPlacementsV8UpgradeRollbackReopenRacesAndDomainProof(): Unit = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val key = ByteArray(32) { 76 }
        try {
            LinePlacementFileProof.concurrent {SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics)}
            LinePlacementFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            PointPlacementFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            PointPositionFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            WallAnchorFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            LegacyWallSeedFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            val file = Files.createTempFile("points-cipher-", ".db").toFile()
            try {
                SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                    openDesktopPriceCatalog(driver, false)
                    PointPlacementProof.run(driver)
                    PointPlacementProof.geometryAndDeletionGuards(driver)
                    PointPlacementProof.eligibilityAndIsolation(driver)
                    PointPositionProof.run(driver)
                    WallAnchorProof.run(driver)
                }
            } finally { file.delete() }
        } finally { key.fill(0) }
    }
    @Test fun encryptedMeasurementElementsV7MigrationRacesAndSharedContracts() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val key = ByteArray(32) { 64 }
        try {
            MeasurementElementFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            val file = Files.createTempFile("elements-fresh-cipher-", ".db").toFile()
            try {
                SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                    openDesktopPriceCatalog(driver, false)
                    LivePricingProof.run(driver)
                    MeasurementElementsPresentationProof.run(PrototypeDatabase(driver))
                    MeasurementElementProof.run(driver)
                    MeasurementElementProof.atomicParentDelete(driver)
                    MeasurementElementMigrationProof.sqlGuards(driver)
                }
            } finally { file.delete() }
        } finally { key.fill(0) }
    }

    @Test fun encryptedV3UpgradeAndV6Reopen() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val file = Files.createTempFile("price-v3-cipher-", ".db").toFile()
        val key = ByteArray(32) { 54 }
        try {
            val before: List<List<String?>>
            SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                val database = PrototypeDatabase(driver)
                database.transaction {
                    PriceCatalogMigrationProof.legacySchema.create(driver)
                    PriceCatalogMigrationProof.populateLegacy(driver)
                    // v3 never tracked payment; unchanged 3.sqm adds 'unknown'
                    // for this active legacy project. Build the expected v4+ row.
                    driver.execute(null, "UPDATE projects SET paymentStatus='unknown'", 0)
                }
                before = PriceCatalogMigrationProof.untouchedRows(driver)
                database.transaction {
                    driver.execute(null, "DROP INDEX index_projects_paymentStatus", 0)
                    driver.execute(null, "ALTER TABLE projects DROP COLUMN paymentStatus", 0)
                    driver.execute(null, "PRAGMA user_version=3", 0)
                }
            }
            SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                openDesktopPriceCatalog(driver, true)
                PriceCatalogMigrationProof.assertReplaced(PrototypeDatabase(driver))
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
                PriceNameUniquenessProof.assertKeys(driver)
            }
            SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                val schema = PriceNameMigrationProof.schema(driver)
                openDesktopPriceCatalog(driver, true)
                assertEquals(schema, PriceNameMigrationProof.schema(driver))
                assertEquals(PriceCatalogSchema.version, PriceNameUniquenessProof.scalar(driver, "PRAGMA user_version"))
            }
        } finally { key.fill(0); file.delete() }
    }

    @Test fun encryptedV5NamesRollbackAndConcurrentConnections() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val key = ByteArray(32) { 57 }
        try {
            PriceNameFileProof.run { SqlCipherWindowsDriverFactory.openProfiled(it, key, nativeMetrics) }
            val file = Files.createTempFile("price-names-crud-", ".db").toFile()
            try {
                SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                    PriceCatalogSchema.create(driver)
                    PriceNameUniquenessProof.crud(driver)
                }
            } finally { file.delete() }
        } finally { key.fill(0) }
    }

    @Test fun encryptedMigrationRollbackCrudAndReopen() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@runBlocking
        val file = Files.createTempFile("price-p1-cipher-", ".db").toFile()
        val key = ByteArray(32) { 58 }
        try {
            var before = emptyList<List<String?>>()
            SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                PrototypeDatabase(driver).transaction {
                    PriceCatalogMigrationProof.legacySchema.create(driver)
                    PriceCatalogMigrationProof.populateLegacy(driver)
                }
                before = PriceCatalogMigrationProof.untouchedRows(driver)
                assertFails { PriceCatalogSchema.migrate(PriceCatalogMigrationProof.failing(driver), 4, 6) }
                PriceCatalogMigrationProof.assertOldPrice(driver)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
            SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                openDesktopPriceCatalog(driver, true)
                val db = PrototypeDatabase(driver)
                PriceCatalogMigrationProof.assertReplaced(db)
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
                PriceCatalogPersistenceProof.groupsAndParameters(db)
                PriceCatalogP2Proof.run(db)
                PriceCatalogPersistenceProof.removeEverything(db)
            }
            SqlCipherWindowsDriverFactory.openProfiled(file, key, nativeMetrics).use { driver ->
                openDesktopPriceCatalog(driver, true)
                assertEquals(0L, PriceRepository(PrototypeDatabase(driver)).count())
                assertTrue(PriceGroupRepository(PrototypeDatabase(driver)).all().isEmpty())
                assertEquals(before, PriceCatalogMigrationProof.untouchedRows(driver))
            }
        } finally { key.fill(0); file.delete() }
    }
}
