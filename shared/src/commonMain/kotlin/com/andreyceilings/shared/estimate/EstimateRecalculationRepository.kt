package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.PrototypeDatabase

/**
 * SQLDelight read adapter for the pure preview engine.
 * Does not insert or update any revisions or legacy estimate rows.
 */
class EstimateRecalculationRepository(private val database: PrototypeDatabase) {
    private val history = ImmutableEstimateRepository(database)
    private val prices = PriceRepository(database)

    fun previewLatest(seriesUuid: String): EstimateRecalculationPreview {
        val series = checkNotNull(history.series(seriesUuid)) { "Estimate series not found" }
        check(series.deletedAt == null) { "Deleted estimate series cannot be recalculated" }
        val latest = checkNotNull(history.revisions(seriesUuid).lastOrNull()) {
            "Estimate series has no revision"
        }
        val frozenLines = history.lines(latest.revisionUuid).map { line ->
            ImmutableEstimateLine(
                stableCode = line.stableCode,
                title = line.titleSnapshot,
                unit = line.unitSnapshot,
                quantity = line.quantitySnapshot,
                unitPrice = line.unitPriceSnapshot,
                lineTotal = line.lineTotalSnapshot,
                priceItemUuid = line.priceItemUuid
            )
        }
        val currentPrices = currentPrices()
        return EstimateRecalculation.preview(
            baseRevisionUuid = latest.revisionUuid,
            baseVersionNumber = latest.versionNumber,
            originalTotal = latest.totalCost,
            frozenLines = frozenLines,
            prices = currentPrices
        )
    }

    /**
     * Read-only freshness probe for the UI. This is NOT a save precondition:
     * an eventual acceptPreview() must perform the same checks and immutable
     * INSERT in one SQLDelight transaction to close the TOCTOU window.
     */
    fun isPreviewCurrent(seriesUuid: String, preview: EstimateRecalculationPreview): Boolean {
        val root = history.series(seriesUuid) ?: return false
        if (root.deletedAt != null) return false
        val latest = history.revisions(seriesUuid).lastOrNull() ?: return false
        if (latest.revisionUuid != preview.baseRevisionUuid ||
            latest.versionNumber != preview.baseVersionNumber) return false
        val priceRows = currentPrices()
        return EstimateRecalculation.priceListStateToken(priceRows) == preview.priceListStateToken
    }

    /**
     * Transactional gate for the future v2/v3 confirmation path.
     *
     * The caller's action MUST perform its immutable INSERT on this same
     * SQLDelight handle inside the callback; checking isPreviewCurrent()
     * before opening a separate transaction would be vulnerable to a
     * time-of-check/time-of-use race.
     *
     * Recompute the entire proposal, not just the price-list token. This
     * rejects a forged or mutated preview as well as a stale price catalog
     * or a different latest source revision. The callback can write only
     * after all conditions pass; exceptions roll back the whole transaction.
     *
     * Internal until the revision candidate can carry matching detailsJson,
     * measurement inputs, rule version and line totals. No UI save yet.
     */
    internal fun <R> withVerifiedPreviewTransaction(
        seriesUuid: String,
        preview: EstimateRecalculationPreview,
        appendInsideTransaction: () -> R
    ): R = database.transactionWithResult {
        check(preview.canConfirm && preview.priceListStateToken.isNotBlank()) {
            "Unconfirmable or incomplete recalculation preview"
        }
        val current = previewLatest(seriesUuid)
        check(current.canConfirm && current == preview) {
            "Stale or modified preview: the source revision or price catalog changed"
        }
        appendInsideTransaction()
    }

    private fun currentPrices(): List<CurrentEstimatePrice> = prices.all().map { row ->
        CurrentEstimatePrice(
            stableCode = row.code,
            priceItemUuid = row.uuid,
            title = row.title,
            unit = row.unit,
            unitPrice = row.price,
            updatedAt = row.updatedAt,
            category = row.category
        )
    }
}
