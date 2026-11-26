package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.db.PrototypeDatabase
import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.estimate.IndividualEstimateSaveFailure.Reason
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString

/** The reservation ledger supplies both annual high-water marks and global public uniqueness. */
internal class EstimateNumberAllocator(private val database: PrototypeDatabase) {
    private val queries = database.estimateIssuanceQueries

    fun policy(): EstimateIssuancePolicy = queries.selectIssuancePolicy().executeAsOneOrNull()
        ?.let { frozenEstimateJson.decodeFromString<EstimateIssuancePolicy>(it) } ?: EstimateIssuancePolicy()

    fun configure(policy: EstimateIssuancePolicy, expected: EstimateIssuancePolicy?): Boolean {
        require(EstimateNumbering.validate(policy).isEmpty())
        return database.transactionWithResult {
            database.priceGroupsQueries.claimCatalogWrite()
            if (expected != null && expected != this@EstimateNumberAllocator.policy()) {
                return@transactionWithResult false
            }
            queries.upsertIssuancePolicy(frozenEstimateJson.encodeToString(policy))
            true
        }
    }

    fun preview(date: LocalDate, requested: EstimateIssuancePolicy?): EstimateNumberPreview =
        database.transactionWithResult {
            val policy = requested ?: policy()
            validate(policy)
            EstimateNumberPreview(
                next(EstimateType.INDIVIDUAL, date, policy).publicNumber,
                next(EstimateType.CONSOLIDATED, date, policy).publicNumber,
                policy.mode == EstimateCounterMode.COMMON
            )
        }

    /** [write] must persist the matching series and any receipt in this transaction. */
    fun <T> issue(
        type: EstimateType,
        date: LocalDate,
        newSeriesUuid: () -> String = ::newRecordUuid,
        write: (EstimateReservation) -> T
    ): T = database.transactionWithResult {
        database.priceGroupsQueries.claimCatalogWrite()
        val policy = policy()
        validate(policy)
        val next = next(type, date, policy)
        repeat(8) {
            val seriesUuid = newSeriesUuid().lowercase()
            require(isEstimateUuid(seriesUuid))
            if (queries.selectReservation(seriesUuid).executeAsOneOrNull() == null &&
                database.estimateHistoryQueries.selectEstimateSeries(seriesUuid).executeAsOneOrNull() == null) {
                queries.insertReservation(seriesUuid, next.publicNumber, type.name, date.toString(),
                    date.year.toLong(), next.sequence, frozenEstimateJson.encodeToString(next.policy), policy.mode.name)
                return@transactionWithResult write(next.copy(seriesUuid = seriesUuid))
            }
        }
        throw IndividualEstimateSaveFailure(Reason.NUMBER_COLLISION)
    }

    private fun validate(policy: EstimateIssuancePolicy) {
        if (EstimateNumbering.validate(policy).isNotEmpty()) throw IndividualEstimateSaveFailure(Reason.NUMBER_POLICY)
    }

    private fun next(type: EstimateType, date: LocalDate, policy: EstimateIssuancePolicy): EstimateReservation {
        require(date.year in 1000..9999)
        fun highWater(type: EstimateType) =
            queries.selectHighWater(date.year.toLong(), type.name).executeAsOneOrNull() ?: 0L
        var sequence = when (policy.mode) {
            EstimateCounterMode.INDEPENDENT -> highWater(type)
            EstimateCounterMode.COMMON -> EstimateType.entries.maxOf(::highWater)
        }
        val format = policy.resolve(type)
        repeat(64) {
            if (sequence == Long.MAX_VALUE) throw IndividualEstimateSaveFailure(Reason.SEQUENCE_EXHAUSTED)
            sequence++
            val publicNumber = try {
                EstimateNumbering.render(format, date, sequence)
            } catch (_: IllegalArgumentException) {
                throw IndividualEstimateSaveFailure(Reason.NUMBER_POLICY)
            }
            if (queries.selectPublicReservation(publicNumber).executeAsOneOrNull() == null) {
                return EstimateReservation("", publicNumber, sequence, format)
            }
        }
        throw IndividualEstimateSaveFailure(Reason.NUMBER_COLLISION)
    }
}

internal data class EstimateReservation(
    val seriesUuid: String,
    val publicNumber: String,
    val sequence: Long,
    val policy: EstimateNumberPolicy
) {
    val documentNumber: String get() = EstimateNumbering.canonical(publicNumber, seriesUuid)
}
