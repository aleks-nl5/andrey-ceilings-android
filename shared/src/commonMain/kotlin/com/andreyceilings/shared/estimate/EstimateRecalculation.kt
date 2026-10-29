package com.andreyceilings.shared.estimate

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A pure, read-only price-list comparison. It deliberately does not mutate
 * SQLDelight, allocate version numbers or claim to be an accepted revision.
 *
 * Historical quantities and prices come only from an immutable snapshot.
 * Never match price items by their display title or silently use a fallback.
 */
data class CurrentEstimatePrice(
    val stableCode: String,
    val priceItemUuid: String,
    val title: String,
    val unit: String,
    val unitPrice: Double,
    val updatedAt: Long,
    val category: String = ""
)

enum class RecalculationProblem {
    MISSING_PRICE,
    AMBIGUOUS_PRICE,
    CHANGED_UNIT,
    INVALID_PRICE,
    HISTORICAL_ADJUSTMENT,
    INCONSISTENT_SNAPSHOT
}

data class EstimateLineDifference(
    val before: ImmutableEstimateLine,
    val after: ImmutableEstimateLine?,
    val problem: RecalculationProblem?
) {
    val difference: Double? get() = after?.lineTotal?.minus(before.lineTotal)
}

data class EstimateRecalculationPreview(
    val baseRevisionUuid: String,
    val baseVersionNumber: Long,
    val originalTotal: Double,
    val proposedTotal: Double?,
    val lines: List<EstimateLineDifference>,
    val problems: Set<RecalculationProblem>,
    /**
     * Exact, order-independent snapshot of the current price catalog at
     * preview time; internal only, never displayed as a document number.
     * The eventual transactional confirmation must re-read and compare it.
     */
    val priceListStateToken: String = ""
) {
    val canConfirm: Boolean get() = problems.isEmpty() && proposedTotal != null
    val delta: Double? get() = proposedTotal?.minus(originalTotal)
}

/**
 * Stage 1: calculate the candidate prices only. Persisting v2/v3 must wait
 * for optimistic price-list validation, a consistent detailsJson snapshot
 * and Android review/confirmation; callers must not save the preview as-is.
 */
object EstimateRecalculation {
    /**
     * Collision-free canonical catalog state for optimistic preview checks.
     * Neither the greatest updatedAt nor a non-cryptographic hash is safe:
     * two rows can change at the same millisecond, or a row can be deleted.
     * Length prefixes avoid ambiguous separators and sorting ignores
     * SQL row order. Includes inactive-looking rows via caller's list;
     * PriceRepository.all() filters tombstoned entries by design.
     */
    fun priceListStateToken(prices: List<CurrentEstimatePrice>): String {
        fun encoded(value: String): String = "${value.length}:$value"
        val rows = prices.map { price ->
            buildString {
                append(encoded(price.priceItemUuid))
                append(encoded(price.stableCode))
                append(encoded(price.title))
                append(encoded(price.unit))
                append(encoded(price.category))
                append(encoded(price.unitPrice.toBits().toString()))
                append(encoded(price.updatedAt.toString()))
            }
        }.sorted()
        return buildString {
            append(rows.size)
            append('|')
            rows.forEach { row -> append(encoded(row)) }
        }
    }

    fun preview(
        baseRevisionUuid: String,
        baseVersionNumber: Long,
        originalTotal: Double,
        frozenLines: List<ImmutableEstimateLine>,
        prices: List<CurrentEstimatePrice>
    ): EstimateRecalculationPreview {
        require(baseRevisionUuid.isNotBlank() && baseVersionNumber > 0L)
        require(originalTotal.isFinite() && originalTotal >= 0.0)
        require(frozenLines.isNotEmpty())

        val allProblems = linkedSetOf<RecalculationProblem>()
        if (abs(frozenLines.sumOf { it.lineTotal } - originalTotal) >= 0.01) {
            allProblems += RecalculationProblem.INCONSISTENT_SNAPSHOT
        }

        val differences = frozenLines.map { before ->
            val matches = when {
                before.stableCode == "system.rounding" || before.stableCode.startsWith("legacy.") -> {
                    allProblems += RecalculationProblem.HISTORICAL_ADJUSTMENT
                    return@map EstimateLineDifference(before, null, RecalculationProblem.HISTORICAL_ADJUSTMENT)
                }
                before.priceItemUuid != null ->
                    prices.filter { it.priceItemUuid == before.priceItemUuid }
                else -> prices.filter { it.stableCode == before.stableCode }
            }

            val problem = when {
                matches.isEmpty() -> RecalculationProblem.MISSING_PRICE
                matches.size != 1 -> RecalculationProblem.AMBIGUOUS_PRICE
                matches.single().unit != before.unit -> RecalculationProblem.CHANGED_UNIT
                !matches.single().unitPrice.isFinite() || matches.single().unitPrice < 0.0 ->
                    RecalculationProblem.INVALID_PRICE
                else -> null
            }
            if (problem != null) {
                allProblems += problem
                EstimateLineDifference(before, null, problem)
            } else {
                val price = matches.single()
                val amount = roundMoney(before.quantity * price.unitPrice)
                if (!amount.isFinite() || amount < 0.0) {
                    allProblems += RecalculationProblem.INVALID_PRICE
                    EstimateLineDifference(before, null, RecalculationProblem.INVALID_PRICE)
                } else {
                    EstimateLineDifference(
                        before,
                        ImmutableEstimateLine(
                            stableCode = price.stableCode,
                            title = price.title,
                            unit = price.unit,
                            quantity = before.quantity,
                            unitPrice = price.unitPrice,
                            lineTotal = amount,
                            priceItemUuid = price.priceItemUuid
                        ),
                        null
                    )
                }
            }
        }
        val total = if (allProblems.isEmpty()) differences.sumOf { checkNotNull(it.after).lineTotal } else null
        return EstimateRecalculationPreview(
            baseRevisionUuid, baseVersionNumber, originalTotal, total, differences,
            allProblems, priceListStateToken(prices)
        )
    }

    private fun roundMoney(value: Double): Double =
        if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)
}
