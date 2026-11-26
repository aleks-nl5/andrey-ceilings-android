package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.livepricing.PricingMath
import com.andreyceilings.shared.livepricing.QuoteAmounts
import com.andreyceilings.shared.livepricing.QuoteDiscount
import com.andreyceilings.shared.livepricing.QuoteDiscountMode
import com.andreyceilings.shared.livepricing.QuoteDiscountValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.kotlincrypto.hash.sha2.SHA256

@Serializable
internal data class ConsolidatedDiscount(val mode: QuoteDiscountMode, val value: Long) {
    fun calculate(subtotal: Long): QuoteAmounts? = when (mode) {
        QuoteDiscountMode.PERCENT -> value.takeIf { it in 0..10000 }?.let {
            QuoteDiscount.calculate(subtotal, it.toInt())
        }
        QuoteDiscountMode.FIXED -> QuoteDiscount.calculate(subtotal, QuoteDiscountValue.Fixed(value))
    }

    companion object {
        fun parse(text: String, mode: QuoteDiscountMode): ConsolidatedDiscount? = when (
            val value = QuoteDiscount.parse(text, mode)
        ) {
            is QuoteDiscountValue.Percentage -> ConsolidatedDiscount(mode, value.basisPoints.toLong())
            is QuoteDiscountValue.Fixed -> ConsolidatedDiscount(mode, value.cents)
            null -> null
        }
    }
}

@Serializable
internal data class ConsolidatedDraft(
    val title: String,
    val discount: ConsolidatedDiscount,
    val selection: List<ConsolidatedDocumentRef>
)

@Serializable
internal data class ConsolidatedSave(
    val operationUuid: String,
    val projectId: Long,
    val projectUuid: String,
    val expectedHead: String?,
    val basedOn: String?,
    val draft: ConsolidatedDraft
)

@Serializable
internal data class ConsolidatedMember(
    val ref: ConsolidatedDocumentRef,
    val room: String,
    val individualVersion: Long,
    val finalCents: Long
)

@Serializable
internal data class FrozenConsolidatedEstimate(
    val formatVersion: Int = 1,
    val seriesUuid: String,
    val revisionUuid: String,
    val version: Long,
    val parentRevisionUuid: String?,
    val projectUuid: String,
    val projectTitle: String,
    val projectAddress: String?,
    val issueDate: String,
    val publicNumber: String,
    val createdAt: Long,
    val operationUuid: String,
    val commandProof: String,
    val draft: ConsolidatedDraft,
    val members: List<ConsolidatedMember>,
    val currency: String,
    val amounts: QuoteAmounts
)

internal data class SavedConsolidatedEstimate(
    val frozen: FrozenConsolidatedEstimate,
    val status: EstimateWorkflowStatus,
    val statusVersion: Long,
    val deleted: Boolean,
    val individuals: List<SavedIndividualQuote>
) {
    fun document() = ConsolidatedEstimateDocument(
        frozen.projectTitle, frozen.projectAddress,
        individuals.map {
            ConsolidatedEstimateDocument.Section(
                FrozenEstimateDocument.from(it, currentAddress = null, zone = kotlinx.datetime.TimeZone.UTC), null
            )
        },
        frozen.amounts.finalCents, frozen.draft.title, frozen.publicNumber, frozen.version, frozen.amounts
    )
}

internal data class ConsolidatedRevisionIdentity(val seriesUuid: String, val revisionUuid: String, val version: Long)

internal fun consolidatedAmounts(cents: List<Long>, discount: ConsolidatedDiscount): QuoteAmounts? {
    if (cents.isEmpty() || cents.any { it < 0 }) return null
    return discount.calculate(cents.fold(0L, PricingMath::add))
}

internal data class ConsolidatedStatusConfirmation(
    val revisionUuid: String,
    val targetStatus: EstimateWorkflowStatus,
    val statusVersion: Long,
    val priorApproval: Pair<String, Long>?,
    val individuals: Map<String, Pair<EstimateWorkflowStatus?, Long>>
)

internal class DuplicateConsolidatedContent : IllegalArgumentException()

internal class ConsolidatedConflict : IllegalStateException("Consolidated estimate changed or is unavailable")


/** Persist compact proofs of verified frozen data and canonical commands. */
internal fun consolidatedProof(unencoded: String): String =
    SHA256().digest(("libellaro-consolidated-v1:" + unencoded).encodeToByteArray()).toHexString()

internal fun FrozenIndividualEstimate.consolidatedSourceProof(): String =
    consolidatedProof(frozenEstimateJson.encodeToString(this))
