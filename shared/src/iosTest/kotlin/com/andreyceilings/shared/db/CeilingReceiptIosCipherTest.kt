package com.andreyceilings.shared.db
import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
class CeilingReceiptIosCipherTest {
    @Test fun currentEncryptedReceiptGuards(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "current-receipt-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 84 } }, recovery = SqlCipherRecovery.Fail)
        try {
            val driver = factory.create(IosPriceCatalogSchema, config)
            try {
                CeilingReceiptMigrationProof.populate(driver); CeilingReceiptMigrationProof.rejectReplace(driver)
                PriceCatalogSchema.verifyStructure(driver)
            } finally { driver.close() }
        } finally { factory.deleteDatabase(name) }
    }

    // Archived pre-v21 upgrade proof; current checks run above.
    @kotlin.test.Ignore
    @Test fun physicalV13ToV14RollbackReopenAndReplaceGuards():Unit=runBlocking {
        val factory=NativeSqlCipherDriverFactory();val name="receipts-v14-${Random.nextLong()}.db"
        val config=SqlCipherConfig(name=name,key=SqlCipherKey {ByteArray(32){91}},recovery=SqlCipherRecovery.Fail)
        try {CeilingReceiptMigrationProof.lifecycle(IosPriceCatalogSchema) {factory.create(it,config)}}
        finally {factory.deleteDatabase(name)}
    }
}
