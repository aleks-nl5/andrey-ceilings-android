package com.andreyceilings.shared.db

import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class AreaFeatureIosCipherTest {
    @Test fun currentEncryptedAreaContract(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "current-area-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 84 } }, recovery = SqlCipherRecovery.Fail)
        try {
            val driver = factory.create(IosPriceCatalogSchema, config)
            try {
                AreaFeatureProof.run(driver)
                PriceCatalogSchema.verifyStructure(driver)
            } finally { driver.close() }
        } finally { factory.deleteDatabase(name) }
    }

    // Archived pre-v21 upgrade proof; current checks run above.
    @kotlin.test.Ignore
    @Test fun physicalV12MigrationRollbackReopenAndAtomicAreas():Unit=runBlocking {
        val factory=NativeSqlCipherDriverFactory()
        val name="areas-v13-${Random.nextLong()}.db"
        val config=SqlCipherConfig(name=name,key=SqlCipherKey {ByteArray(32){84}},recovery=SqlCipherRecovery.Fail)
        try {AreaFeatureMigrationProof.lifecycle(IosPriceCatalogSchema) {factory.create(it,config)}}
        finally {factory.deleteDatabase(name)}
    }
}
