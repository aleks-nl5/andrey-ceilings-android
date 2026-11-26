package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.newRecordUuid
import kotlin.math.abs
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The complete immutable calculation inputs and saved outputs for one revision. */
@Serializable
data class ImmutableEstimateDraft(
    val sourceMeasurementJson: String,
    val detailsJson: String,
    val measurementPrecisionMeters: Double,
    val calculationRuleVersion: String,
    val totalCost: Double,
    val lines: List<ImmutableEstimateLine>,
    val currency: String = "UAH",
    val moneyRoundingPolicy: String = "HALF_AWAY_FROM_ZERO"
)

@Serializable
data class ImmutableEstimateLine(
    val stableCode: String,
    val title: String,
    val unit: String,
    val quantity: Double,
    val unitPrice: Double,
    val lineTotal: Double,
    val priceItemUuid: String? = null
)

data class CreatedEstimateSeries(
    val seriesUuid: String,
    val documentNumber: String,
    val initialRevisionUuid: String,
    val localSequence: Long
)

@Serializable
private data class ImmutableSeriesCommand(
    val projectId: Long,
    val measurementUuid: String?,
    val firstRevision: ImmutableEstimateDraft
)

/**
 * KMP SQLDelight contract. A sequence and both immutable identity rows are saved
 * in one transaction, so failure of ANY line/constraint cannot leave a partial
 * document or advance the counter. Do not expose mutable update APIs for revisions.
 */
class ImmutableEstimateRepository(private val database: PrototypeDatabase) {
    private val queries = database.estimateHistoryQueries

    private val numbering = EstimateNumberAllocator(database)

    fun saveIndividual(request: IndividualEstimateSaveRequest, issueDate: LocalDate, now: Long): CreatedEstimateSeries =
        saveIndividual(database, request, issueDate, now)

    fun saveIndividual(request: IndividualEstimateSaveRequest, year: Int, now: Long): CreatedEstimateSeries =
        saveIndividual(request, LocalDate(year, 1, 1), now)

    fun appendIndividual(request: AppendIndividualRevisionRequest, now: Long): CreatedEstimateSeries =
        saveIndividual(database, request.calculation, null, now, request.target)

    fun frozenIndividual(seriesUuid: String) = database.frozenRevisionQueries.selectExactLatest(seriesUuid).executeAsOneOrNull()

    fun frozenIndividuals() = database.frozenRevisionQueries.selectExactList().executeAsList()

    internal fun projectQuoteMetadata(projectId: Long) =
        database.frozenRevisionQueries.selectProjectQuoteMetadata(projectId)

    fun exactIndividual(seriesUuid: String, revisionUuid: String): SavedIndividualQuote? =
        readIndividual(seriesUuid, revisionUuid, includeDeleted = false)

    internal fun historicalIndividual(seriesUuid: String, revisionUuid: String): SavedIndividualQuote? =
        readIndividual(seriesUuid, revisionUuid, includeDeleted = true)

    private fun readIndividual(seriesUuid: String, revisionUuid: String, includeDeleted: Boolean): SavedIndividualQuote? =
        database.frozenRevisionQueries.selectHistoricalExactRevision(seriesUuid, revisionUuid).executeAsOneOrNull()?.let {
            if (!includeDeleted && it.isActive != 1L) return null
            val f = decodeFrozenIndividualEstimate(it.payload)
            verifyFrozenStoredIdentity(f, it.projectId, it.measurementUuid, it.publicNumber,
                it.subtotalCents, it.discountBasisPoints, it.discountCents, it.totalCents)
            val number = database.estimateIssuanceQueries.selectReservation(it.seriesUuid).executeAsOne()
            require(number.estimateType == EstimateType.INDIVIDUAL.name && number.publicNumber == f.publicNumber &&
                frozenEstimateJson.decodeFromString<EstimateNumberPolicy>(number.policy) == f.numberPolicy)
            verifyFrozenRevisionIdentity(f, it.seriesUuid, it.revisionUuid, it.versionNumber, it.parentRevisionUuid)
            val revision = checkNotNull(revision(it.revisionUuid))
            require(revision.detailsJson == it.payload && revision.sourceMeasurementJson == f.source.drawingJson)
            if (it.versionNumber > 1) {
                val parent = checkNotNull(revision(checkNotNull(it.parentRevisionUuid)))
                require(parent.seriesUuid == seriesUuid && parent.versionNumber == it.versionNumber - 1)
                require(database.frozenRevisionQueries.selectHistoricalExactRevision(seriesUuid, parent.revisionUuid).executeAsOneOrNull() != null)
            }
            if (it.versionNumber == 1L) {
                val receipt = checkNotNull(database.frozenEstimateQueries.selectV1ReceiptByRevision(it.revisionUuid).executeAsOneOrNull())
                require(receipt.seriesUuid == it.seriesUuid)
                verifyFrozenSaveReceipt(receipt.operationUuid, receipt.command, f)
            } else {
                val receipt = checkNotNull(database.frozenRevisionQueries.selectRevisionReceiptByRevision(it.revisionUuid).executeAsOneOrNull())
                require(receipt.seriesUuid == it.seriesUuid)
                verifyFrozenRevisionReceipt(receipt.operationUuid, receipt.command, f)
            }
            SavedIndividualQuote(it.seriesUuid, it.createdAt, f, it.status?.let(EstimateWorkflowStatus::valueOf), it.statusVersion,
                it.revisionUuid, it.versionNumber, it.parentRevisionUuid, it.projectId, it.selectedRevisionUuid, it.choiceVersion)
        }

    fun individualHistory(seriesUuid: String): List<SavedIndividualQuote> =
        database.frozenRevisionQueries.selectExactHistory(seriesUuid).executeAsList().map {
            checkNotNull(exactIndividual(seriesUuid, it.revisionUuid))
        }

    /**
     * The caller can replace approvals only after confirming the exact set shown to the user.
     * Legacy callers supply the default empty set and can only make the first approval.
     */
    fun setWorkflowStatus(
        revisionUuid: String,
        expectedVersion: Long,
        status: EstimateWorkflowStatus,
        now: Long,
        expectedApprovals: Map<String, Long> = emptyMap(),
        expectedConsolidatedApproval: Pair<String, Long>? = null
    ): Boolean {
        require(now > 0 && expectedVersion > 0)
        return try {
            database.transactionWithResult {
                database.priceGroupsQueries.claimCatalogWrite()
                val workflow = database.revisionWorkflowQueries
                val target = workflow.selectActiveQuoteWorkflow(revisionUuid).executeAsOneOrNull()
                    ?: return@transactionWithResult false
                if (target.version != expectedVersion) return@transactionWithResult false
                if (status != EstimateWorkflowStatus.APPROVED && expectedApprovals.isNotEmpty()) {
                    return@transactionWithResult false
                }
                if (status == EstimateWorkflowStatus.APPROVED) {
                    val measurementUuid = target.measurementUuid ?: return@transactionWithResult false
                    // The same SQLite writer transaction also serializes ceiling deletion.
                    // Historical documents remain readable, but cannot acquire a new approval.
                    val business = database.businessSchemaQueries
                    val ceiling = business.selectMeasurementByUuid(measurementUuid).executeAsOneOrNull()
                        ?: return@transactionWithResult false
                    if (ceiling.deletedAt != null || ceiling.projectId != target.projectId ||
                        business.selectProjectById(target.projectId).executeAsOneOrNull() == null
                    ) return@transactionWithResult false
                    val approved = workflow.selectApprovedQuoteVersions(target.projectId, measurementUuid)
                        .executeAsList().associate { it.revisionUuid to it.version }
                    if (target.status == status.name && approved == mapOf(revisionUuid to expectedVersion) &&
                        expectedApprovals.isEmpty()
                    ) return@transactionWithResult true
                    if (approved != expectedApprovals || approved.size > 1) return@transactionWithResult false
                    for ((previousUuid, version) in approved - revisionUuid) {
                        workflow.updateRevisionWorkflow(EstimateWorkflowStatus.SENT.name, now, previousUuid, version)
                            .executeAsOneOrNull() ?: throw WorkflowConflict()
                    }
                }
                val priorIndividual = if (status == EstimateWorkflowStatus.APPROVED) {
                    expectedApprovals.keys.singleOrNull()
                } else revisionUuid.takeIf { target.status == EstimateWorkflowStatus.APPROVED.name }
                val linked = if (priorIndividual != null && priorIndividual != revisionUuid.takeIf {
                        status == EstimateWorkflowStatus.APPROVED
                    }) {
                    val measurement = checkNotNull(target.measurementUuid)
                    database.consolidatedQuotesQueries.coveredApproval(target.projectId, measurement, priorIndividual)
                        .executeAsOneOrNull()?.let { it.revisionUuid to it.version }
                } else null
                if (linked != expectedConsolidatedApproval) throw WorkflowConflict()
                if (linked != null) {
                    database.consolidatedQuotesQueries.updateWorkflow(EstimateWorkflowStatus.SENT.name, now,
                        linked.first, linked.second).executeAsOneOrNull() ?: throw WorkflowConflict()
                }
                if (target.status != status.name) {
                    workflow.updateRevisionWorkflow(status.name, now, revisionUuid, expectedVersion)
                        .executeAsOneOrNull() ?: throw WorkflowConflict()
                }
                true
            }
        } catch (_: WorkflowConflict) {
            false
        }
    }

    private class WorkflowConflict : IllegalStateException()

    fun selectRevision(seriesUuid: String, revisionUuid: String, expectedVersion: Long, now: Long): Boolean {
        require(now > 0 && expectedVersion > 0)
        return database.transactionWithResult {
            database.measurementElementsQueries.claimElementWrite("")
            if (exactIndividual(seriesUuid, revisionUuid) == null) return@transactionWithResult false
            database.revisionWorkflowQueries.updateRevisionChoice(revisionUuid, now, seriesUuid, expectedVersion).executeAsOneOrNull() != null
        }
    }

    fun issuancePolicy(): EstimateIssuancePolicy = numbering.policy()

    fun configureIssuancePolicy(policy: EstimateIssuancePolicy, expected: EstimateIssuancePolicy? = null): Boolean =
        numbering.configure(policy, expected)

    fun previewNumbers(issueDate: LocalDate, policy: EstimateIssuancePolicy? = null): EstimateNumberPreview =
        numbering.preview(issueDate, policy)

    fun numberPolicy(): EstimateNumberPolicy = issuancePolicy().resolve(EstimateType.INDIVIDUAL)

    fun configureNumberPolicy(policy: EstimateNumberPolicy) {
        val current = issuancePolicy()
        check(configureIssuancePolicy(current.copy(
            individualPrefix = policy.prefix, template = policy.template, sequenceWidth = policy.sequenceWidth
        ), current)) { "Numbering policy changed" }
    }

    internal fun issueIndividual(
        projectId: Long,
        measurementUuid: String?,
        issueDate: LocalDate,
        now: Long,
        newSeriesUuid: () -> String = ::newRecordUuid,
        write: (EstimateReservation) -> CreatedEstimateSeries
    ): CreatedEstimateSeries = numbering.issue(EstimateType.INDIVIDUAL, issueDate, newSeriesUuid) { number ->
        val policy = number.policy
        checkNotNull(queries.insertEstimateSeriesIfFree(number.seriesUuid, number.documentNumber, projectId, measurementUuid,
            policy.template, policy.prefix, policy.sequenceWidth.toLong(), number.sequence, now, number.publicNumber)
            .executeAsOneOrNull())
        write(number)
    }

    fun series(seriesUuid: String) = queries.selectEstimateSeries(seriesUuid).executeAsOneOrNull()
    fun seriesByNumber(number: String) = queries.selectEstimateSeriesByNumber(number).executeAsOneOrNull()
    fun seriesForProject(projectId: Long) = queries.selectEstimateSeriesForProject(projectId).executeAsList()
    fun revisions(seriesUuid: String) = queries.selectEstimateRevisions(seriesUuid).executeAsList()
    fun revision(revisionUuid: String) = queries.selectEstimateRevision(revisionUuid).executeAsOneOrNull()
    fun lines(revisionUuid: String) = queries.selectEstimateRevisionLines(revisionUuid).executeAsList()

    /** New document always owns its own series UUID and immutable v1. */
    fun createSeries(
        projectId: Long,
        year: Int,
        createdAt: Long,
        firstRevision: ImmutableEstimateDraft,
        measurementUuid: String? = null,
        newSeriesUuid: () -> String = ::newRecordUuid,
        newRevisionUuid: () -> String = ::newRecordUuid,
        operationUuid: String = newRecordUuid()
    ): CreatedEstimateSeries = createSeries(projectId, LocalDate(year, 1, 1), createdAt, firstRevision,
        measurementUuid, newSeriesUuid, newRevisionUuid, operationUuid)

    fun createSeries(
        projectId: Long,
        issueDate: LocalDate,
        createdAt: Long,
        firstRevision: ImmutableEstimateDraft,
        measurementUuid: String? = null,
        newSeriesUuid: () -> String = ::newRecordUuid,
        newRevisionUuid: () -> String = ::newRecordUuid,
        operationUuid: String = newRecordUuid()
    ): CreatedEstimateSeries {
        require(projectId > 0 && isEstimateUuid(operationUuid))
        validateDraft(firstRevision)
        val command = frozenEstimateJson.encodeToString(ImmutableSeriesCommand(projectId, measurementUuid, firstRevision))
        val issuance = database.estimateIssuanceQueries
        return database.transactionWithResult {
            database.priceGroupsQueries.claimCatalogWrite()
            fun conflict(): Nothing = throw IndividualEstimateSaveFailure(IndividualEstimateSaveFailure.Reason.OPERATION_CONFLICT)
            if (database.consolidatedQuotesQueries.receipt(operationUuid).executeAsOneOrNull() != null ||
                database.frozenEstimateQueries.selectEstimateSaveReceipt(operationUuid).executeAsOneOrNull() != null ||
                database.frozenRevisionQueries.selectRevisionReceipt(operationUuid).executeAsOneOrNull() != null) conflict()
            issuance.selectIssuanceReceipt(operationUuid).executeAsOneOrNull()?.let { receipt ->
                if (receipt.command != command) conflict()
                val series = checkNotNull(series(receipt.seriesUuid))
                val first = revisions(series.seriesUuid).first()
                return@transactionWithResult CreatedEstimateSeries(series.seriesUuid, series.documentNumber,
                    first.revisionUuid, series.localSequence)
            }
            issueIndividual(projectId, measurementUuid, issueDate, createdAt, newSeriesUuid) { number ->
                val revisionUuid = newRevisionUuid().lowercase()
                require(database.consolidatedQuotesQueries.revision(revisionUuid).executeAsOneOrNull() == null)
                insertRevision(number.seriesUuid, revisionUuid, null, 1L, createdAt, firstRevision)
                issuance.recordIssuanceReceipt(operationUuid, command, number.seriesUuid)
                CreatedEstimateSeries(number.seriesUuid, number.documentNumber, revisionUuid, number.sequence)
            }
        }
    }

    /**
     * Confirming a preview must supply the exact revision it was based upon.
     * A stale preview fails instead of silently creating a second v2.
     */
    fun appendRevision(
        seriesUuid: String,
        expectedLatestRevisionUuid: String,
        createdAt: Long,
        snapshot: ImmutableEstimateDraft,
        newRevisionUuid: () -> String = ::newRecordUuid
    ): String {
        validateDraft(snapshot)
        return database.transactionWithResult {
            val root = checkNotNull(series(seriesUuid)) { "Estimate series not found" }
            check(root.deletedAt == null) { "Deleted estimate series cannot be revised" }
            check(database.frozenRevisionQueries.countFrozenSeries(seriesUuid).executeAsOne() == 0L) {
                "Exact individual series require the measurement-sourced writer"
            }
            val latest = checkNotNull(queries.selectLatestEstimateRevision(seriesUuid).executeAsOneOrNull()) {
                "Estimate series has no first revision"
            }
            check(latest.revisionUuid == expectedLatestRevisionUuid) {
                "Stale preview: the estimate was already revised"
            }
            val id = newRevisionUuid().lowercase()
            require(database.consolidatedQuotesQueries.revision(id).executeAsOneOrNull() == null)
            insertRevision(seriesUuid, id, latest.revisionUuid, latest.versionNumber + 1L, createdAt, snapshot)
            id
        }
    }

    /** Legacy non-frozen series retain their existing deletion route. */
    fun softDeleteSeries(seriesUuid: String, deletedAt: Long) {
        require(deletedAt > 0)
        database.transaction {
            database.priceGroupsQueries.claimCatalogWrite()
            if (database.frozenRevisionQueries.countFrozenSeries(seriesUuid).executeAsOne() == 0L) {
                queries.softDeleteEstimateSeries(deletedAt, seriesUuid).executeAsOneOrNull()
            }
        }
    }

    fun deleteIndividualRevision(seriesUuid: String, revisionUuid: String, expectedStatusVersion: Long, now: Long): Boolean {
        require(now > 0 && expectedStatusVersion > 0)
        return database.transactionWithResult {
            database.priceGroupsQueries.claimCatalogWrite()
            val workflow = database.revisionWorkflowQueries
            database.frozenRevisionQueries.insertRevisionTombstone(
                deletedAt = now, revisionUuid = revisionUuid, seriesUuid = seriesUuid,
                expectedStatusVersion = expectedStatusVersion
            ).executeAsOneOrNull() ?: return@transactionWithResult false
            if (workflow.selectActiveSeriesWorkflow(seriesUuid).executeAsList().isEmpty()) {
                checkNotNull(queries.softDeleteEstimateSeries(now, seriesUuid).executeAsOneOrNull())
            }
            true
        }
    }

    fun deleteIndividualSeries(seriesUuid: String, expectedActiveVersions: Map<String, Long>, now: Long): Boolean {
        require(now > 0)
        return database.transactionWithResult {
            database.priceGroupsQueries.claimCatalogWrite()
            val active = database.revisionWorkflowQueries.selectActiveSeriesWorkflow(seriesUuid).executeAsList()
            if (active.isEmpty() || active.any { it.status == null || it.status == EstimateWorkflowStatus.APPROVED.name } ||
                active.associate { it.revisionUuid to it.version } != expectedActiveVersions
            ) return@transactionWithResult false
            queries.softDeleteEstimateSeries(now, seriesUuid).executeAsOneOrNull() != null
        }
    }

    private fun insertRevision(
        seriesUuid: String,
        revisionUuid: String,
        parentRevisionUuid: String?,
        versionNumber: Long,
        createdAt: Long,
        draft: ImmutableEstimateDraft
    ) {
        queries.insertImmutableEstimateRevision(
            revisionUuid, seriesUuid, parentRevisionUuid, versionNumber, createdAt,
            draft.sourceMeasurementJson, draft.detailsJson, draft.measurementPrecisionMeters,
            draft.calculationRuleVersion, draft.currency, draft.moneyRoundingPolicy, draft.totalCost
        )
        draft.lines.forEachIndexed { index, line ->
            queries.insertImmutableEstimateRevisionLine(
                revisionUuid, index.toLong(), line.stableCode, line.priceItemUuid,
                line.title, line.unit, line.quantity, line.unitPrice, line.lineTotal
            )
        }
    }

    private fun validateDraft(draft: ImmutableEstimateDraft) {
        require(draft.sourceMeasurementJson.isNotBlank() && draft.detailsJson.isNotBlank())
        require(draft.measurementPrecisionMeters.isFinite() && draft.measurementPrecisionMeters > 0)
        require(draft.calculationRuleVersion.isNotBlank())
        require(draft.currency.isNotBlank() && draft.moneyRoundingPolicy.isNotBlank())
        require(draft.totalCost.isFinite() && draft.totalCost >= 0)
        require(draft.lines.isNotEmpty()) { "An immutable estimate needs saved line items" }
        draft.lines.forEach { line ->
            require(line.stableCode.isNotBlank() && line.title.isNotBlank() && line.unit.isNotBlank())
            require(line.quantity.isFinite() && line.quantity >= 0.0)
            require(line.unitPrice.isFinite() && (line.unitPrice >= 0.0 || line.stableCode == "system.rounding"))
            require(line.lineTotal.isFinite() && (line.lineTotal >= 0.0 || line.stableCode == "system.rounding"))
        }
        require(abs(draft.lines.sumOf { it.lineTotal } - draft.totalCost) < 0.01) {
            "Saved line totals must equal the document total"
        }
    }
}
