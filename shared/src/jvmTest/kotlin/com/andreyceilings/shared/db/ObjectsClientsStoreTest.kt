package com.andreyceilings.shared.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.andreyceilings.shared.objects.ClientDraft
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.objects.ProjectDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObjectsClientsStoreTest {
    @Test
    fun createEditAndPaymentRemainIndependent() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            var clock = 100L
            val store = ObjectsClientsStore(db) { ++clock }
            val clients = ClientRepository(db)
            val projects = ProjectRepository(db)
            val client = store.saveClient(null, ClientDraft(" Олена ", "+380671112233", "", "Примітка"))
            assertEquals("Олена", clients.find(client)?.name)
            val project = store.saveProject(null, ProjectDraft(" Квартира ", " Київ ", client,
                "in_progress", "paid", "Тест"))
            val original = assertNotNull(projects.find(project))
            assertEquals("Квартира", original.title)
            assertEquals("in_progress", original.status)
            assertEquals("paid", original.paymentStatus)
            assertEquals(client, original.clientId)
            val same = store.saveProject(project, ProjectDraft("Квартира 2", "Київ", client,
                "rework", "partial", "Підпис"))
            assertEquals(project, same)
            val changed = assertNotNull(projects.find(project))
            assertEquals("rework", changed.status)
            assertEquals("partial", changed.paymentStatus)
            assertEquals(original.uuid, changed.uuid)
            assertEquals("Підпис", changed.notes)
            assertEquals(client, store.saveClient(client, ClientDraft("Олена 2")))
            assertEquals("Олена 2", clients.find(client)?.name)
        }
    }

    @Test
    fun rejectsInvalidWritesAndPreservesRecords() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val store = ObjectsClientsStore(db) { 100L }
            val id = store.saveProject(null, ProjectDraft("Зал"))
            assertFailsWith<IllegalArgumentException> {
                store.saveProject(id, ProjectDraft(" ", workStatus = "in_progress"))
            }
            assertFailsWith<IllegalArgumentException> {
                store.saveProject(id, ProjectDraft("Зал", workStatus = "paid"))
            }
            assertFailsWith<IllegalStateException> {
                store.saveProject(id, ProjectDraft("Зал", clientId = 9999))
            }
            assertEquals("Зал", ProjectRepository(db).find(id)?.title)
            assertEquals("measurement", ProjectRepository(db).find(id)?.status)
        }
    }

    @Test
    fun linkedClientCannotBeDeletedAndProjectDeletionKeepsTombstone() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val store = ObjectsClientsStore(db) { 100L }
            val clients = ClientRepository(db)
            val projects = ProjectRepository(db)
            val client = store.saveClient(null, ClientDraft("Андрій"))
            val project = store.saveProject(null, ProjectDraft("Об'єкт", clientId = client))
            assertFailsWith<IllegalStateException> { store.deleteClient(client) }
            assertNotNull(clients.find(client))
            store.deleteProject(project)
            assertNull(projects.find(project))
            assertEquals(1, projects.tombstones().size)
            store.deleteClient(client)
            assertNull(clients.find(client))
            assertEquals(1, clients.tombstones().size)
        }
    }
}
