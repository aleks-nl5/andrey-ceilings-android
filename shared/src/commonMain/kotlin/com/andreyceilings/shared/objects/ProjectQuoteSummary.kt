package com.andreyceilings.shared.objects

import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.livepricing.PricingMath
import com.andreyceilings.shared.livepricing.PricingOverflow

internal data class ProjectQuoteCandidate(
    val ceilingId: Long,
    val status: String,
    val currency: String,
    val finalCents: Long
)

internal data class CeilingQuoteSummary(val finalCents: Long, val status: EstimateWorkflowStatus)

internal data class ProjectQuoteSummary(
    val ceilings: Map<Long, CeilingQuoteSummary>,
    val unavailableCeilings: Set<Long>,
    val approvedCents: Long?,
    val approvedUnavailable: Boolean
)

internal fun projectQuoteSummary(candidates: List<ProjectQuoteCandidate>): ProjectQuoteSummary {
    val ceilings = mutableMapOf<Long, CeilingQuoteSummary>()
    val unavailable = mutableSetOf<Long>()
    var approvedTotal: Long? = null
    var approvedUnavailable = false
    for ((ceilingId, rows) in candidates.groupBy { it.ceilingId }) {
        val approved = rows.filter { it.status == EstimateWorkflowStatus.APPROVED.name }
        val eligible = rows.filter { it.status != EstimateWorkflowStatus.REJECTED.name }
        val selected = if (approved.isEmpty()) eligible.singleOrNull() else approved.singleOrNull()
        val status = EstimateWorkflowStatus.entries.firstOrNull { it.name == selected?.status }
        val cents = selected?.finalCents
        // The existing verified individual document format supports stored UAH.
        if (eligible.isEmpty()) continue
        if (status == null || cents == null || cents < 0 || selected?.currency != "UAH") {
            unavailable.add(ceilingId)
            if (approved.isNotEmpty() || status == null) approvedUnavailable = true
            continue
        }
        ceilings[ceilingId] = CeilingQuoteSummary(cents, status)
        if (status == EstimateWorkflowStatus.APPROVED) {
            try {
                approvedTotal = PricingMath.add(approvedTotal ?: 0L, cents)
            } catch (_: PricingOverflow) {
                approvedUnavailable = true
            }
        }
    }
    return ProjectQuoteSummary(
        ceilings, unavailable, approvedTotal.takeUnless { approvedUnavailable }, approvedUnavailable
    )
}
