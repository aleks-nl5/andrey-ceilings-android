package com.andreyceilings.shared.db

import app.cash.sqldelight.db.use
import com.yet.sqlcipher.*
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test

class BusinessRoleIosCipherTest {
    @Test fun physicalV14ToV15RollbackReopenRolesAndPricing(): Unit = runBlocking {
        val factory = NativeSqlCipherDriverFactory()
        val name = "business-roles-v15-${Random.nextLong()}.db"
        val config = SqlCipherConfig(name = name, key = SqlCipherKey { ByteArray(32) { 42 } }, recovery = SqlCipherRecovery.Fail)
        val copy = "$name.copy.db"
        try {
            BusinessRoleMigrationProof.lifecycle(IosPriceCatalogSchema) { factory.create(it, config) }
            val evidence = factory.create(IosPriceCatalogSchema, config).use { driver ->
                val path = driver.executeQuery(null, "PRAGMA database_list", { cursor ->
                    cursor.next(); app.cash.sqldelight.db.QueryResult.Value(checkNotNull(cursor.getString(2)))
                }, 0).value
                BusinessRolePortableProof.snapshot(driver, "$path.copy.db")
            }
            val restored = SqlCipherConfig(name = copy, key = SqlCipherKey { ByteArray(32) { 42 } }, recovery = SqlCipherRecovery.Fail)
            factory.create(IosPriceCatalogSchema, restored).use { driver -> BusinessRolePortableProof.verify(driver, evidence) }
        } finally { factory.deleteDatabase(name); factory.deleteDatabase(copy) }
    }
}
