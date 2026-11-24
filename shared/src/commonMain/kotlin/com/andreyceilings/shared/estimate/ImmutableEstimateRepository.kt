package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.newRecordUuid
import kotlin.math.abs

/** The complete immutable calculation inputs and saved outputs for one revision. */
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

/**
 * KMP SQLDelight contract. A sequence and both immutable identity rows are saved
 * in one transaction, so failure of ANY line/constraint cannot leave a partial
 * document or advance the counter. Do not expose mutable update APIs for revisions.
 */
class ImmutableEstimateRepository(private val database: PrototypeDatabase) {
    private val queries = database.estimateHistoryQueries

    fun saveIndividual(request: IndividualEstimateSaveRequest, year: Int, now: Long,
        publicToken: () -> String = EstimateNumbering::newPublicToken): CreatedEstimateSeries =
        saveIndividual(database, request, year, now, publicToken)

    fun appendIndividual(request: AppendIndividualRevisionRequest, now: Long): CreatedEstimateSeries =
        saveIndividual(database, request.calculation, 2000, now, EstimateNumbering::newPublicToken, request.target)

    fun frozenIndividual(seriesUuid: String) = database.frozenRevisionQueries.selectExactLatest(seriesUuid).executeAsOneOrNull()

    fun frozenIndividuals() = database.frozenRevisionQueries.selectExactList().executeAsList()

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
        expectedApprovals: Map<String, Long> = emptyMap()
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
                    val approved = workflow.selectApprovedQuoteVersions(target.projectId, measurementUuid)
                        .executeAsList().associate { it.revisionUuid to it.version }
                    if (target.status == status.name && approved == mapOf(revisionUuid to expectedVersion) &&
                        expectedApprovals.isEmpty()
                    ) return@transactionWithResult true
                    if (approved != expectedApprovals) return@transactionWithResult false
                    for ((previousUuid, version) in approved - revisionUuid) {
                        workflow.updateRevisionWorkflow(EstimateWorkflowStatus.SENT.name, now, previousUuid, version)
                            .executeAsOneOrNull() ?: throw WorkflowConflict()
                    }
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

    fun numberPolicy(): EstimateNumberPolicy {
        val row = queries.selectEstimateNumberSettings().executeAsOneOrNull()
            ?: return EstimateNumberPolicy(template = "{prefix}-{year}-{sequence}-{suffix}")
        return EstimateNumberPolicy(row.template, row.prefix, row.sequenceWidth.toInt(), row.suffixLabel)
    }

    /** A setting applies only to future series, never to existing stored numbers. */
    fun configureNumberPolicy(policy: EstimateNumberPolicy) {
        require(EstimateNumbering.validate(policy).isEmpty()) { "Invalid document number policy" }
        database.transaction {
            queries.insertDefaultEstimateNumberSettings()
            queries.updateEstimateNumberSettings(policy.template, policy.prefix, policy.sequenceWidth.toLong(), policy.suffixLabel)
        }
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
        newRevisionUuid: () -> String = ::newRecordUuid
    ): CreatedEstimateSeries {
        require(projectId > 0) { "A series requires a project" }
        validateDraft(firstRevision)
        return database.transactionWithResult {
            queries.insertDefaultEstimateNumberSettings()
            val policy = numberPolicy()
            check(EstimateNumbering.validate(policy).isEmpty()) { "Stored numbering configuration is invalid" }
            val sequence = queries.allocateEstimateSequence().executeAsOne() - 1L
            var seriesUuid: String? = null
            var documentNumber: String? = null
            // A real collision is expected to be extremely rare. A deliberately
            // repeated UUID fails explicitly after bounded retries, with rollback.
            repeat(5) {
                if (seriesUuid == null) {
                    val candidate = newSeriesUuid().lowercase()
                    val number = EstimateNumbering.render(
                        policy, EstimateNumberContext(candidate, year, sequence, EstimateNumbering.newPublicToken())
                    )
                    val publicNumber = EstimateNumbering.printable(number)
                    val publicAvailable = "{suffix}" !in policy.template || database.frozenEstimateQueries.selectEstimatePublicNumber(publicNumber).executeAsOneOrNull() == null
                    val inserted = if (publicAvailable) queries.insertEstimateSeriesIfFree(
                        candidate, number, projectId, measurementUuid,
                        policy.template, policy.prefix, policy.sequenceWidth.toLong(),
                        sequence, createdAt
                    ).executeAsOneOrNull() else null
                    if (inserted != null) {
                        if ("{suffix}" in policy.template) database.frozenEstimateQueries.setEstimatePublicNumber(publicNumber, candidate)
                        seriesUuid = candidate
                        documentNumber = number
                    }
                }
            }
            val id = checkNotNull(seriesUuid) { "Could not allocate a unique estimate series UUID" }
            val number = checkNotNull(documentNumber)
            val revisionUuid = newRevisionUuid().lowercase()
            insertRevision(id, revisionUuid, null, 1L, createdAt, firstRevision)
            CreatedEstimateSeries(id, number, revisionUuid, sequence)
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
            check(database.frozenEstimateQueries.selectFrozenV1(seriesUuid).executeAsOneOrNull() == null) {
                "Exact individual series require the measurement-sourced writer"
            }
            val latest = checkNotNull(queries.selectLatestEstimateRevision(seriesUuid).executeAsOneOrNull()) {
                "Estimate series has no first revision"
            }
            check(latest.revisionUuid == expectedLatestRevisionUuid) {
                "Stale preview: the estimate was already revised"
            }
            val id = newRevisionUuid().lowercase()
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
            val target = workflow.selectActiveQuoteWorkflow(revisionUuid).executeAsOneOrNull()
                ?: return@transactionWithResult false
            if (target.seriesUuid != seriesUuid || target.version != expectedStatusVersion ||
                target.status == null || target.status == EstimateWorkflowStatus.APPROVED.name
            ) return@transactionWithResult false
            database.frozenRevisionQueries.insertRevisionTombstone(revisionUuid, now)
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
