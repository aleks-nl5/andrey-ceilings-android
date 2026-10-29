package com.andreyceilings.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.db.PrototypeDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDelightPrototypeTest {
    @Test
    fun clientCrudWorksAgainstGeneratedSql() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            PrototypeDatabase.Schema.create(driver)
            val queries = PrototypeDatabase(driver).prototypeClientQueries

            queries.insertClient(id = 7L, name = "Тестовий клієнт", created_at_epoch_millis = 1234L)
            assertEquals("Тестовий клієнт", queries.selectClientById(7L).executeAsOne().name)

            queries.updateClient(name = "Оновлений клієнт", created_at_epoch_millis = 5678L, id = 7L)
            val updated = queries.selectAllClients().executeAsList().single()
            assertEquals("Оновлений клієнт", updated.name)
            assertEquals(5678L, updated.created_at_epoch_millis)

            queries.deleteClient(7L)
            assertEquals(emptyList(), queries.selectAllClients().executeAsList())
            assertNull(queries.selectClientById(7L).executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }
}
