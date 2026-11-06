package com.andreyceilings.shared.db
import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
class CeilingReceiptIosCipherTest {
    @Test fun physicalV13ToV14RollbackReopenAndReplaceGuards():Unit=runBlocking {
        val factory=NativeSqlCipherDriverFactory();val name="receipts-v14-${Random.nextLong()}.db"
        val config=SqlCipherConfig(name=name,key=SqlCipherKey {ByteArray(32){91}},recovery=SqlCipherRecovery.Fail)
        try {CeilingReceiptMigrationProof.lifecycle(IosPriceCatalogSchema) {factory.create(it,config)}}
        finally {factory.deleteDatabase(name)}
    }
}
