package com.andreyceilings.shared.db

import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class MeasurementElementIosCipherTest {
    @Test fun encryptedV6UpgradeRollbackRestartAndFreshContracts(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        for (scenario in listOf("migration", "fresh")) {
            val name = "elements-$scenario-${Random.nextLong()}.db"
            val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 63 } }, recovery = SqlCipherRecovery.Fail)
            try {
                if (scenario == "migration") MeasurementElementMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) }
                else {
                    val driver = factory.create(IosPriceCatalogSchema, config)
                    try {
                        com.andreyceilings.shared.measurement.MeasurementElementsPresentationProof.run(PrototypeDatabase(driver))
                        MeasurementElementProof.run(driver)
                        MeasurementElementProof.atomicParentDelete(driver)
                        MeasurementElementMigrationProof.sqlGuards(driver)
                    } finally { driver.close() }
                }
            } finally { factory.deleteDatabase(name) }
        }
    }
}
