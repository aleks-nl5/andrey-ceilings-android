package com.andreyceilings.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.andreyceilings.shared.db.*
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test

class AreaFeatureSqlCipherTest {
    @Test fun physicalV12MigrationRollbackReopenAndAtomicAreas():Unit=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val name="areas-v13-native.db";val key=ByteArray(32){84}
        context.deleteDatabase(name)
        try {
            AreaFeatureMigrationProof.lifecycle(PriceCatalogSchema) {schema ->
                val driver=AndroidSqliteDriver(schema,context,name,factory=SupportOpenHelperFactory(key.copyOf(),null,true))
                try {
                    driver.executeQuery(null,"SELECT count(*) FROM sqlite_master",{c->c.next();app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))},0).value
                    driver
                } catch(t:Throwable){driver.close();throw t}
            }
        } finally {key.fill(0);context.deleteDatabase(name)}
    }
}
