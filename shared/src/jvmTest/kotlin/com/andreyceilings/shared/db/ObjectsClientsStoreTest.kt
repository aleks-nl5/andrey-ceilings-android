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
            val project = store.saveProject(null, ProjectDraft(" Київ, Хрещатик 1 ", client,
                "in_progress", "paid", "Тест"))
            val original = assertNotNull(projects.find(project))
            assertEquals("Київ, Хрещатик 1", original.title)
            assertEquals(original.title, original.address)
            assertEquals("in_progress", original.status)
            assertEquals("paid", original.paymentStatus)
            assertEquals(client, original.clientId)
            // A paused project can stay fully paid; independent statuses never overwrite each other.
            store.saveProject(project, ProjectDraft("Київ, Хрещатик 1", client,
                "paused", "paid", "Затримка постачання"))
            assertEquals("paused", projects.find(project)?.status)
            assertEquals("paid", projects.find(project)?.paymentStatus)
            val same = store.saveProject(project, ProjectDraft("Київ, Хрещатик 2", client,
                "rework", "partial", "Підпис"))
            assertEquals(project, same)
            val changed = assertNotNull(projects.find(project))
            assertEquals("rework", changed.status)
            assertEquals("Київ, Хрещатик 2", changed.address)
            assertEquals(changed.address, changed.title)
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
            val id = store.saveProject(null, ProjectDraft("Львів, Центральна 1"))
            assertFailsWith<IllegalArgumentException> {
                store.saveProject(id, ProjectDraft(" ", workStatus = "in_progress"))
            }
            assertFailsWith<IllegalArgumentException> {
                store.saveProject(id, ProjectDraft("Львів, Центральна 1", workStatus = "paid"))
            }
            assertFailsWith<IllegalStateException> {
                store.saveProject(id, ProjectDraft("Львів, Центральна 1", clientId = 9999))
            }
            assertEquals("Львів, Центральна 1", ProjectRepository(db).find(id)?.title)
            assertEquals("measurement", ProjectRepository(db).find(id)?.status)
        }
    }

    @Test
    fun legacyProjectsRetainOriginalTitleUntilAnAddressEdit() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val repository = ProjectRepository(db)
            val store = ObjectsClientsStore(db) { 200L }
            val id = repository.createReturningId(null, "Історична назва", createdAt = 100L)
            assertEquals("Історична назва", repository.find(id)?.title)
            assertEquals("", repository.find(id)?.address)
            store.saveProject(id, ProjectDraft("Вінниця, Соборна 14"))
            assertEquals("Вінниця, Соборна 14", repository.find(id)?.address)
            assertEquals("Вінниця, Соборна 14", repository.find(id)?.title)
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
            val project = store.saveProject(null, ProjectDraft("Чернівці, Головна 12", clientId = client))
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

    @Test
    fun projectWithMeasurementsCannotBeDeletedUntilTheyAreHandled() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            PrototypeDatabase.Schema.create(driver)
            val db = PrototypeDatabase(driver)
            val store = ObjectsClientsStore(db) { 300L }
            val projects = ProjectRepository(db)
            val measurements = MeasurementRepository(db)
            val project = store.saveProject(null, ProjectDraft("Харків, Сумська 12"))
            val measurement = measurements.createReturningId(
                projectId = project,
                name = "Кухня",
                createdAt = 301L
            )

            val error = assertFailsWith<IllegalStateException> {
                store.deleteProject(project)
            }
            assertEquals("Спочатку перенесіть або видаліть заміри цього об'єкта.", error.message)
            assertNotNull(projects.find(project))
            assertEquals(1, measurements.forProject(project).size)
            assertEquals(0, projects.tombstones().size)

            measurements.delete(measurement)
            store.deleteProject(project)
            assertNull(projects.find(project))
            assertEquals(1, projects.tombstones().size)
        }
    }

}
