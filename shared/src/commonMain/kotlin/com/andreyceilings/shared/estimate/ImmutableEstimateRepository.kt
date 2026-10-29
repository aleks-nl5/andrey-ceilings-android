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

    fun numberPolicy(): EstimateNumberPolicy {
        val row = queries.selectEstimateNumberSettings().executeAsOneOrNull()
            ?: return EstimateNumberPolicy()
        return EstimateNumberPolicy(row.template, row.prefix, row.sequenceWidth.toInt())
    }

    /** A setting applies only to future series, never to existing stored numbers. */
    fun configureNumberPolicy(policy: EstimateNumberPolicy) {
        require(EstimateNumbering.validate(policy).isEmpty()) { "Invalid document number policy" }
        database.transaction {
            queries.insertDefaultEstimateNumberSettings()
            queries.updateEstimateNumberSettings(policy.template, policy.prefix, policy.sequenceWidth.toLong())
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
                        policy, EstimateNumberContext(candidate, year, sequence)
                    )
                    val inserted = queries.insertEstimateSeriesIfFree(
                        candidate, number, projectId, measurementUuid,
                        policy.template, policy.prefix, policy.sequenceWidth.toLong(),
                        sequence, createdAt
                    ).executeAsOneOrNull()
                    if (inserted != null) {
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

    /** Tombstones reserve both the UUID and document number permanently. */
    fun softDeleteSeries(seriesUuid: String, deletedAt: Long) {
        require(deletedAt > 0L)
        queries.softDeleteEstimateSeries(deletedAt, seriesUuid)
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
