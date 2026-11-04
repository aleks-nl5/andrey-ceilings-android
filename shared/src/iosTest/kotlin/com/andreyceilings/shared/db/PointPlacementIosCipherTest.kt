package com.andreyceilings.shared.db

import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class PointPlacementIosCipherTest {
    @Test fun v7UpgradeRollbackReopenAndFreshDomainProof(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        for (scenario in listOf("fresh", "empty-v7", "populated-v7")) {
            val name = "points-$scenario-${Random.nextLong()}.db"
            val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 75 } }, recovery = SqlCipherRecovery.Fail)
            try {
                if (scenario == "fresh") {
                    val driver = factory.create(IosPriceCatalogSchema, config)
                    try {
                        PointPlacementProof.run(driver)
                        PointPlacementProof.geometryAndDeletionGuards(driver)
                        PointPlacementProof.eligibilityAndIsolation(driver)
                    } finally { driver.close() }
                } else PointPlacementMigrationProof.lifecycle(IosPriceCatalogSchema, scenario == "populated-v7") {
                    factory.create(it, config)
                }
            } finally { factory.deleteDatabase(name) }
        }
    }
}
