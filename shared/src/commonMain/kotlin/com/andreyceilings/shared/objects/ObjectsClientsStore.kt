package com.andreyceilings.shared.objects

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import com.andreyceilings.shared.db.ClientRepository
import com.andreyceilings.shared.db.MeasurementRepository
import com.andreyceilings.shared.db.ProjectRepository
import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import com.andreyceilings.shared.estimate.ImmutableEstimateRepository
import com.andreyceilings.shared.estimate.ConsolidatedDiscount
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.andreyceilings.shared.db.ApprovedFinance
import com.andreyceilings.shared.registry.CeilingFinance
import com.andreyceilings.shared.registry.CeilingQuoteKind
import com.andreyceilings.shared.registry.quoteSeries
import com.andreyceilings.shared.livepricing.PricingMath
import com.andreyceilings.shared.livepricing.PricingOverflow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine

data class ObjectsClientsRows(
    val projects: List<ProjectListEntry>,
    val clients: List<ClientListEntry>
)

data class ProjectMeasurement(
    val id: Long, val uuid: String, val name: String,
    val area: Double, val perimeter: Double
)

data class ProjectDetailsData(
    val project: ProjectListEntry?, val measurements: List<ProjectMeasurement>
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

internal sealed interface PickerProjectSave {
    data object Idle : PickerProjectSave
    data object Saving : PickerProjectSave
    data object Failed : PickerProjectSave
    data class Saved(val id: Long) : PickerProjectSave
}

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
    private val estimates = ImmutableEstimateRepository(database)
    private val sessionClosed = MutableStateFlow(false)
    private val projectRead = Mutex()
    private val contourPreparation = Mutex()
    private val contours = CeilingContourCache()
    private val pickerSave = MutableStateFlow<PickerProjectSave>(PickerProjectSave.Idle)
    internal val pickerProjectSave: Flow<PickerProjectSave> = pickerSave

    internal fun clearPickerResult(state: PickerProjectSave) {
        if (state != PickerProjectSave.Saving) pickerSave.compareAndSet(state, PickerProjectSave.Idle)
    }

    internal suspend fun createPickerProject(draft: ProjectDraft) {
        val previous = pickerSave.value
        if ((previous != PickerProjectSave.Idle && previous != PickerProjectSave.Failed) ||
            !pickerSave.compareAndSet(previous, PickerProjectSave.Saving)) return
        withContext(NonCancellable + Dispatchers.Default) {
            val result = projectRead.withLock {
                if (sessionClosed.value) PickerProjectSave.Failed else try {
                    PickerProjectSave.Saved(saveProject(null, draft))
                } catch (_: Exception) {
                    PickerProjectSave.Failed
                }
            }
            pickerSave.compareAndSet(PickerProjectSave.Saving, result)
        }
    }

    suspend fun isActiveProject(projectId: Long): Boolean = withContext(Dispatchers.Default) {
        projectRead.withLock {
            !sessionClosed.value && projects.find(projectId) != null
        }
    }

    /** Android's production driver drains physical reads under its restore gate. */
    fun closeProjectReads() {
        sessionClosed.value = true
        pickerSave.value = PickerProjectSave.Idle
        contours.close()
    }

    /** Desktop waits for an admitted query before closing its JDBC driver. */
    suspend fun closeAndAwaitProjectReads() {
        closeProjectReads()
        projectRead.withLock { }
    }

    val measurementEdit = com.andreyceilings.shared.measurement.MeasurementEditStore(database, nowMillis)
    val measurementDetails = com.andreyceilings.shared.measurement.MeasurementDetailsStore(database, nowMillis)

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

    fun observeProjectDetails(projectId: Long): Flow<ProjectDetailsData> {
        require(projectId > 0)
        val cards = observeProjectQuery(database.businessSchemaQueries.selectProjectCeilingCards(projectId)) {
            executeAsList().map {
                ProjectMeasurement(it.id, it.uuid, it.name, it.areaSquareMeters, it.perimeterMeters)
            }
        }
        return combine(rows, cards) { current, records ->
            ProjectDetailsData(
                current.projects.firstOrNull { it.id == projectId }.takeIf { records != null },
                records.orEmpty()
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeProjectQuoteSummary(projectId: Long): Flow<ProjectQuoteSummary?> {
        val individualQuery = estimates.projectQuoteMetadata(projectId)
        val consolidatedQuery = database.consolidatedQuotesQueries.approvedFinance(projectId)
        return sessionClosed.flatMapLatest { closed ->
            if (closed) flowOf<ProjectQuoteSummary?>(null)
            else combine(individualQuery.asFlow(), consolidatedQuery.asFlow()) { individuals, consolidated ->
                projectRead.withLock {
                    if (sessionClosed.value) null else database.transactionWithResult {
                        val candidates = individuals.executeAsList().map {
                            ProjectQuoteCandidate(it.ceilingId, it.workflowStatus, it.currency, it.finalCents,
                                it.ceilingUuid, it.revisionUuid)
                        }
                        val approval = approvedManifest(consolidated.executeAsList())
                        projectQuoteSummary(candidates, approval).takeUnless { sessionClosed.value }
                    }
                }
            }
        }.flowOn(Dispatchers.Default).distinctUntilChanged()
    }

    private fun approvedManifest(manifest: List<ApprovedFinance>): ApprovedConsolidatedFinance? {
        val first = manifest.firstOrNull() ?: return null
        val members = manifest.mapNotNull { row ->
            val ceiling = row.measurementUuid ?: return@mapNotNull null
            val revision = row.individualRevisionUuid ?: return@mapNotNull null
            ceiling to revision
        }.toMap()
        val discount = QuoteDiscountMode.entries.firstOrNull { it.name == first.discountMode }
            ?.let { ConsolidatedDiscount(it, first.discountValue) }
        return ApprovedConsolidatedFinance(first.finalCents, members, discount,
            first.finalCents >= 0 && members.size == manifest.size)
    }

    internal fun observeRegistryCeilings() =
        observeProjectQuery(database.registryQueries.activeCeilings()) { executeAsList() }

    internal fun observeRegistryCeilingFinance(): Flow<Map<Long, CeilingFinance>?> {
        val metadata = estimates.projectQuoteMetadata(null)
        val presence = database.registryQueries.ceilingQuotePresence()
        return observeRegistryQueries(metadata, presence) { individuals, counts ->
            val candidates = individuals.executeAsList().map {
                ProjectQuoteCandidate(it.ceilingId, it.workflowStatus, it.currency, it.finalCents, it.ceilingUuid, it.revisionUuid)
            }
            val summary = projectQuoteSummary(candidates)
            counts.executeAsList().groupBy { it.ceilingId }.mapValues { (ceilingId, rows) ->
                val quote = summary.ceilings[ceilingId]
                val numbers = rows.mapNotNull { it.publicNumber?.takeIf(String::isNotBlank) }
                val kind = when {
                    ceilingId in summary.unavailableCeilings || rows.any { it.publicNumber.isNullOrBlank() } ->
                        CeilingQuoteKind.UNAVAILABLE
                    quote != null -> CeilingQuoteKind.valueOf(quote.status.name)
                    rows.all { it.hasOther == 0L } -> CeilingQuoteKind.REJECTED
                    else -> CeilingQuoteKind.UNAVAILABLE
                }
                CeilingFinance(kind, quote.takeUnless {
                    kind == CeilingQuoteKind.UNAVAILABLE
                }, numbers)
            }
        }
    }

    internal fun observeRegistryQuotes() = observeRegistryQueries(
        database.registryQueries.individualVersions(), database.registryQueries.consolidatedVersions()
    ) { individual, consolidated ->
        quoteSeries(individual.executeAsList(), consolidated.executeAsList())
    }

    internal fun observeVisibleCeilingContours(uuids: List<String>, projectId: Long? = null): Flow<Map<String, MeasurementDrawingContour?>?> {
        require(uuids.size <= 40)
        if (uuids.isEmpty()) return flowOf(emptyMap())
        return observeProjectQuery(database.registryQueries.visibleContours(uuids, projectId)) { executeAsList() }
            .map { rows -> rows?.associate { row ->
                val source = CeilingContourSource(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters)
                row.uuid to contourPreparation.withLock { contours.projection(row.uuid, source) }
            } }.flowOn(Dispatchers.Default)
    }

    internal fun observeDashboardSummary() = observeProjectQuery(database.registryQueries.dashboardSummary()) { executeAsOne() }

    internal fun observeDashboardQuoteCount() =
        observeProjectQuery(database.registryQueries.dashboardQuoteCounts()) {
            executeAsOne().let { it.individualCount + it.consolidatedCount }
        }

    internal fun observeDashboardFinance(): Flow<Long?> = observeRegistryQueries(
        estimates.projectQuoteMetadata(null), database.consolidatedQuotesQueries.approvedFinance(null)
    ) { individuals, consolidated ->
        val byProject = individuals.executeAsList().groupBy({ it.ownerId }) {
            ProjectQuoteCandidate(it.ceilingId, it.workflowStatus, it.currency, it.finalCents, it.ceilingUuid, it.revisionUuid)
        }
        val approvals = consolidated.executeAsList().groupBy { it.ownerId }
        var total = 0L
        for (projectId in byProject.keys + approvals.keys) {
            val summary = projectQuoteSummary(byProject[projectId].orEmpty(), approvedManifest(approvals[projectId].orEmpty()))
            if (summary.approvedUnavailable) return@observeRegistryQueries null
            val amount = summary.approvedCents ?: continue
            try {
                total = PricingMath.add(total, amount)
            } catch (_: PricingOverflow) {
                return@observeRegistryQueries null
            }
        }
        total
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <A : Any, B : Any, Result> observeRegistryQueries(
        first: Query<A>, second: Query<B>, read: (Query<A>, Query<B>) -> Result
    ): Flow<Result?> = sessionClosed.flatMapLatest { closed ->
        if (closed) flowOf(null)
        else combine(first.asFlow(), second.asFlow()) { a, b ->
            projectRead.withLock {
                if (sessionClosed.value) null else database.transactionWithResult { read(a, b) }
                    .takeUnless { sessionClosed.value }
            }
        }
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    internal fun observeConsolidatedVersions(projectId: Long) =
        observeProjectQuery(database.consolidatedQuotesQueries.activeVersions(projectId)) { executeAsList() }

    internal fun observeCeilingContour(projectId: Long, ceilingUuid: String): Flow<MeasurementDrawingContour?> =
        observeVisibleCeilingContours(listOf(ceilingUuid), projectId).map { it?.get(ceilingUuid) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <Row : Any, Result> observeProjectQuery(query: Query<Row>, read: Query<Row>.() -> Result): Flow<Result?> =
        sessionClosed.flatMapLatest { closed ->
            if (closed) flowOf(null)
            else query.asFlow().map {
                projectRead.withLock {
                    if (sessionClosed.value) null else it.read().takeUnless { sessionClosed.value }
                }
            }
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    fun saveClient(id: Long?, draft: ClientDraft): Long {
        val name = draft.name.trim()
        require(name.isNotBlank()) { "Введіть ім'я клієнта." }
        return if (id == null) {
            require(draft.phone.any(Char::isDigit))
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

    /**
     * Inline Project Details actions update exactly one field, not a reconstructed
     * ProjectDraft. Other fields (including the independent payment/work state)
     * stay untouched even if another editor has saved them in the meantime.
     */
    fun updateProjectWorkStage(projectId: Long, workStage: String) {
        require(projectId > 0L)
        require(ProjectStatusRules.isKnownWorkStage(workStage)) { "Невідомий етап робіт." }
        database.transaction {
            val current = checkNotNull(projects.find(projectId)) {
                "Об'єкт не знайдено або його видалено."
            }
            if (current.status != workStage) {
                projects.updateStatus(projectId, workStage, nowMillis())
            }
        }
    }

    fun updateProjectPaymentStatus(projectId: Long, paymentStatus: String) {
        require(projectId > 0L)
        require(ProjectStatusRules.isKnownPaymentStatus(paymentStatus)) { "Невідомий стан оплати." }
        database.transaction {
            val current = checkNotNull(projects.find(projectId)) {
                "Об'єкт не знайдено або його видалено."
            }
            if (current.paymentStatus != paymentStatus) {
                projects.updatePaymentStatus(projectId, paymentStatus, nowMillis())
            }
        }
    }

    fun deleteClient(id: Long) {
        checkNotNull(clients.find(id)) { "Клієнта вже немає." }
        check(projects.byClient(id).isEmpty()) {
            "Спершу перенесіть або видаліть об'єкти цього клієнта."
        }
        clients.delete(id)
    }

    suspend fun deleteCeiling(projectId: Long, measurementId: Long, expectedUuid: String) {
        check(!sessionClosed.value)
        com.andreyceilings.shared.measurement.MeasurementsStore(database).delete(projectId, measurementId, expectedUuid)
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
