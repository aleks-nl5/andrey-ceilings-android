package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.*

class InitialV10RegressionTest {
    @Test fun originalP3C3V10UpgradesWithoutLosingExistingRecords() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            InitialV10MigrationProof.schema.create(driver)
            val f=LegacyWallSeedProof.populate(driver,includeUnsupported=false)
            LegacyWallSeedProof.decorateV10(driver,f)
            val before=LegacyWallSeedProof.snapshot(driver,true)
            PriceCatalogSchema.migrate(driver,10,11)
            assertEquals(before,LegacyWallSeedProof.snapshot(driver,true))
            verifyPointPlacementStructure(driver,11)
        }
    }
    @Test fun hybridV10FailsBeforeCorrectiveDdlInsteadOfRelaxingFingerprint() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            InitialV10MigrationProof.schema.create(driver)
            LegacyWallSeedProof.populate(driver,includeUnsupported=false)
            driver.execute(null,"DROP TRIGGER point_operation_insert_guard",0)
            driver.execute(null,pointV10SchemaDefinitions.getValue("point_operation_insert_guard"),0)
            val before=LegacyWallSeedProof.snapshot(driver,true);val schema=PriceNameMigrationProof.schema(driver)
            assertFailsWith<SchemaFingerprintMismatch> { PriceCatalogSchema.migrate(driver,10,11) }
            assertEquals(before,LegacyWallSeedProof.snapshot(driver,true))
            assertEquals(schema,PriceNameMigrationProof.schema(driver))
        }
    }
}
