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
        val currentPrices = prices.all().map { row ->
            CurrentEstimatePrice(
                stableCode = row.code,
                priceItemUuid = row.uuid,
                title = row.title,
                unit = row.unit,
                unitPrice = row.price,
                updatedAt = row.updatedAt
            )
        }
        return EstimateRecalculation.preview(
            baseRevisionUuid = latest.revisionUuid,
            baseVersionNumber = latest.versionNumber,
            originalTotal = latest.totalCost,
            frozenLines = frozenLines,
            prices = currentPrices
        )
    }
}
