package com.andreyceilings.shared.objects

import com.andreyceilings.shared.estimate.ConsolidatedDiscount
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.estimate.consolidatedAmounts
import com.andreyceilings.shared.livepricing.PricingMath
import com.andreyceilings.shared.livepricing.PricingOverflow

internal data class ProjectQuoteCandidate(
    val ceilingId: Long,
    val status: String,
    val currency: String,
    val finalCents: Long,
    val ceilingUuid: String = "",
    val revisionUuid: String = ""
)

internal data class CeilingQuoteSummary(val finalCents: Long, val status: EstimateWorkflowStatus)

internal data class ProjectQuoteSummary(
    val ceilings: Map<Long, CeilingQuoteSummary>,
    val unavailableCeilings: Set<Long>,
    val approvedCents: Long?,
    val approvedUnavailable: Boolean
)

internal data class ApprovedConsolidatedFinance(
    val finalCents: Long,
    val members: Map<String, String>,
    val discount: ConsolidatedDiscount?,
    val valid: Boolean
)

internal fun projectQuoteSummary(
    candidates: List<ProjectQuoteCandidate>,
    consolidated: ApprovedConsolidatedFinance? = null
): ProjectQuoteSummary {
    val ceilings = mutableMapOf<Long, CeilingQuoteSummary>()
    val unavailable = mutableSetOf<Long>()
    var approvedTotal = consolidated?.finalCents
    val covered = consolidated?.members.orEmpty()
    val matched = mutableMapOf<String, Long>()
    var approvedUnavailable = consolidated?.valid == false
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
            val expected = covered[selected.ceilingUuid]
            if (expected != null) {
                if (expected == selected.revisionUuid) matched[selected.ceilingUuid] = cents
                continue
            }
            try {
                approvedTotal = PricingMath.add(approvedTotal ?: 0L, cents)
            } catch (_: PricingOverflow) {
                approvedUnavailable = true
            }
        }
    }
    if (consolidated != null) {
        val expectedPayable = try {
            consolidated.discount?.let { consolidatedAmounts(matched.values.toList(), it)?.finalCents }
        } catch (_: PricingOverflow) {
            null
        }
        if (matched.keys != covered.keys || expectedPayable != consolidated.finalCents) approvedUnavailable = true
    }
    return ProjectQuoteSummary(
        ceilings, unavailable, approvedTotal.takeUnless { approvedUnavailable }, approvedUnavailable
    )
}
