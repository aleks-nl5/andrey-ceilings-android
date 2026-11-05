package com.andreyceilings.shared.db

import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class PointPlacementIosCipherTest {
    @Test fun v7UpgradeRollbackReopenAndFreshDomainProof(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        for (scenario in listOf("fresh", "empty-v7", "populated-v7", "empty-v8", "populated-v8", "empty-v9", "populated-v9", "legacy-v9", "deployed-v10", "initial-v10")) {
            val name = "points-$scenario-${Random.nextLong()}.db"
            val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 75 } }, recovery = SqlCipherRecovery.Fail)
            try {
                if (scenario == "fresh") {
                    val driver = factory.create(IosPriceCatalogSchema, config)
                    try {
                        PointPlacementProof.run(driver)
                        PointPlacementProof.geometryAndDeletionGuards(driver)
                        PointPlacementProof.eligibilityAndIsolation(driver)
                        PointPositionProof.run(driver)
                        WallAnchorProof.run(driver)
                    } finally { driver.close() }
                } else if(scenario=="initial-v10")LegacyWallSeedProof.initialV10Lifecycle(IosPriceCatalogSchema) {factory.create(it,config)}
                else if(scenario=="legacy-v9" || scenario=="deployed-v10") LegacyWallSeedProof.lifecycle(IosPriceCatalogSchema,if(scenario=="legacy-v9")9L else 10L) { factory.create(it,config) }
                else if (scenario.endsWith("v9")) WallAnchorMigrationProof.lifecycle(IosPriceCatalogSchema, scenario == "populated-v9") { factory.create(it,config) }
                else if (scenario.endsWith("v8")) PointPositionMigrationProof.lifecycle(IosPriceCatalogSchema, scenario == "populated-v8") {
                    factory.create(it, config)
                } else PointPlacementMigrationProof.lifecycle(IosPriceCatalogSchema, scenario == "populated-v7") {
                    factory.create(it, config)
                }
            } finally { factory.deleteDatabase(name) }
        }
    }
}
