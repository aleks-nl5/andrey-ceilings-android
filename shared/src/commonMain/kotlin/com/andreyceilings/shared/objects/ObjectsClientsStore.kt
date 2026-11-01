package com.andreyceilings.shared.objects

import com.andreyceilings.shared.db.ClientRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ObjectsClientsRows(
    val projects: List<ProjectListEntry>,
    val clients: List<ClientListEntry>
)

data class ClientDraft(
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = ""
)

data class ProjectDraft(
    val address: String,
    val clientId: Long? = null,
    val workStatus: String = ProjectWorkStage.MEASUREMENT.wireId,
    val paymentStatus: String = ProjectPaymentStatus.UNPAID.wireId,
    val notes: String = ""
)

/**
 * One SQLDelight + SQLCipher-backed model for Android and Windows CMP hosts.
 * Never cache decrypted client data beyond an unlocked database session.
 * Reuse existing shared repositories, UUIDs, tombstones and estimate history.
 */
class ObjectsClientsStore(
    private val database: PrototypeDatabase,
    private val nowMillis: () -> Long
) {
    private val clients = ClientRepository(database)
    private val projects = ProjectRepository(database)
    private val measurements = MeasurementRepository(database)

    val rows: Flow<ObjectsClientsRows> = combine(
        clients.observeAll(), projects.observeAll()
    ) { clientRecords, projectRecords ->
        val activeClients = clientRecords.associateBy { it.id }
        val counts = projectRecords.groupingBy { it.clientId }.eachCount()
        ObjectsClientsRows(
            projects = projectRecords.map { row ->
                ProjectListEntry(
                    id = row.id,
                    title = row.title,
                    address = row.address,
                    clientId = row.clientId,
                    clientName = row.clientId?.let { activeClients[it]?.name }.orEmpty(),
                    workStatus = row.status,
                    paymentStatus = row.paymentStatus,
                    notes = row.notes
                )
            },
            clients = clientRecords.map { row ->
                ClientListEntry(
                    id = row.id,
                    name = row.name,
                    phone = row.phone,
                    email = row.email,
                    notes = row.notes,
                    activeProjectCount = counts[row.id] ?: 0
                )
            }
        )
    }

    fun saveClient(id: Long?, draft: ClientDraft): Long {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "Введіть ім'я клієнта." }
        return if (id == null) {
            clients.createReturningId(name, draft.phone.trim(), draft.email.trim(),
                draft.notes.trim(), nowMillis())
        } else {
            checkNotNull(clients.find(id)) { "Клієнта не знайдено або його видалено." }
            clients.update(id, name, draft.phone.trim(), draft.email.trim(), draft.notes.trim())
            id
        }
    }

    fun saveProject(id: Long?, draft: ProjectDraft): Long {
        val address = draft.address.trim()
        require(address.isNotBlank()) { "Введіть адресу об'єкта." }
        // Keep the existing SQLDelight 'title' column for estimates, backups,
        // cross-platform compatibility and old revisions. New/edited CMP objects
        // derive it from their address; there is no user-editable title anymore.
        val title = address
        require(ProjectStatusRules.isKnownWorkStage(draft.workStatus)) {
            "Оберіть актуальний етап робіт."
        }
        require(ProjectStatusRules.isKnownPaymentStatus(draft.paymentStatus)) {
            "Невідомий стан оплати."
        }
        return database.transactionWithResult {
            val clientId = draft.clientId?.also {
                checkNotNull(clients.find(it)) { "Вибраного клієнта не знайдено." }
            }
            val time = nowMillis()
            val projectId = if (id == null) {
                projects.createReturningId(clientId, title, address,
                    draft.workStatus, draft.notes.trim(), createdAt = time)
            } else {
                checkNotNull(projects.find(id)) { "Об'єкт не знайдено або його видалено." }
                projects.update(id, clientId, title, address,
                    draft.workStatus, draft.notes.trim(), time)
                id
            }
            // Same transaction: never leave a half-updated work/payment pair.
            projects.updatePaymentStatus(projectId, draft.paymentStatus, time)
            projectId
        }
    }

    fun deleteClient(id: Long) {
        checkNotNull(clients.find(id)) { "Клієнта вже немає." }
        check(projects.byClient(id).isEmpty()) {
            "Спершу перенесіть або видаліть об'єкти цього клієнта."
        }
        clients.delete(id)
    }

    fun deleteProject(id: Long) {
        checkNotNull(projects.find(id)) { "Об'єкт уже видалено." }
        // Removing a project currently hides estimates, but does NOT cascade
        // room measurements. Reject the operation rather than leaving orphan
        // measurements behind; explicit reassignment is planned in #60.
        check(measurements.forProject(id).isEmpty()) {
            "Спочатку перенесіть або видаліть заміри цього об'єкта."
        }
        projects.delete(id)
    }
}
