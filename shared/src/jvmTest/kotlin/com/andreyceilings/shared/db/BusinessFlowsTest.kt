package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room replacement gate: a Compose screen collecting Flow must receive a new
 * snapshot when SQLDelight changes the observed table, not just on first load.
 */
class BusinessFlowsTest {
    @Test
    fun clientFlowPublishesInitialAndUpdatedLists() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val clients = ClientRepository(PrototypeDatabase(driver))
            val values = Channel<Int>(Channel.UNLIMITED)
            val job = launch {
                clients.observeAll().collect { values.send(it.size) }
            }
            try {
                assertEquals(0, withTimeout(10_000) { values.receive() })
                clients.create("Live update", createdAt = 1)
                assertEquals(1, withTimeout(10_000) { values.receive() })
            } finally {
                job.cancelAndJoin()
                values.close()
            }
        }
    }
}
