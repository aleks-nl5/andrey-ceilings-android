package com.andreyceilings.shared.estimate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import com.andreyceilings.shared.livepricing.QuoteAmounts
import com.andreyceilings.shared.livepricing.QuoteDiscountMode

/** UI-only selection retained by the unlocked database session across Activity recreation. */
internal class ConsolidatedComposerSession(
    val projectId: Long,
    revisionUuid: String? = null,
    val basedOn: String? = null
) {
    val selection = MutableStateFlow<Map<String, ConsolidatedDocumentRef?>>(emptyMap())
    val previewing = MutableStateFlow(false)
    val title = MutableStateFlow<String?>(null)
    val discount = MutableStateFlow("0")
    val discountMode = MutableStateFlow(QuoteDiscountMode.PERCENT)
    val draftBasis = MutableStateFlow<ConsolidatedEditBasis?>(null)
    val savedRevision = MutableStateFlow(revisionUuid)
    val operation = MutableStateFlow<ConsolidatedSave?>(null)
    val saving = MutableStateFlow(false)
}

@Serializable
internal data class ConsolidatedDocumentRef(
    val measurementUuid: String,
    val seriesUuid: String,
    val revisionUuid: String,
    val fingerprint: String
)

internal data class ConsolidatedRevisionOption(
    val ref: ConsolidatedDocumentRef,
    val number: String,
    val version: Long,
    val title: String,
    val status: EstimateWorkflowStatus?,
    val finalCents: Long
)

internal data class ConsolidatedRoomCatalog(val projectUuid: String, val rooms: List<ConsolidatedRoomOption>)

internal data class ConsolidatedRoomOption(
    val uuid: String,
    val name: String,
    val revisions: List<ConsolidatedRevisionOption>,
    val unavailableRevisions: Boolean
)

internal data class ConsolidatedEstimateDocument(
    val currentProjectTitle: String,
    val currentAddress: String?,
    val sections: List<Section>,
    val totalCents: Long,
    val title: String? = null,
    val publicNumber: String? = null,
    val version: Long? = null,
    val amounts: QuoteAmounts? = null
) {
    data class Section(val document: FrozenEstimateDocument, val status: EstimateWorkflowStatus?)

    val fileName: String
        get() {
            if (publicNumber != null && version != null) {
                val number = sanitizeEstimateFileStem(publicNumber, "-").take(56)
                return "zvedenyi-koshtorys-$number-v$version.pdf"
            }
            // Bound the Unicode slug well below the usual 255-byte filename limit.
            val slug = sanitizeEstimateFileStem(projectTitleUuid.replace(currentProjectTitle, ""), "-")
                .replace(slugSeparators, "-").trim('-').take(56)
                .trimEnd { it == '-' || it.isHighSurrogate() }
            return if (slug.isEmpty()) "zvedenyi-koshtorys.pdf" else "zvedenyi-koshtorys-$slug.pdf"
        }

    private companion object {
        val projectTitleUuid = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        val slugSeparators = Regex("[._-]+")
    }
}

internal enum class ConsolidatedDocumentProblem {
    EMPTY, DUPLICATE, PROJECT_MISSING, ROOM_MISSING, REVISION_MISSING, FOREIGN_REVISION,
    STALE, INVALID_REVISION, UNSUPPORTED_FORMAT, INVALID_DRAWING, CURRENCY, OVERFLOW
}

internal sealed interface ConsolidatedDocumentResult {
    data class Ready(val document: ConsolidatedEstimateDocument) : ConsolidatedDocumentResult
    data class Rejected(val problem: ConsolidatedDocumentProblem) : ConsolidatedDocumentResult
}

internal data class ConsolidatedEditBasis(
    val expectedHead: String?,
    val initialSelection: Map<String, ConsolidatedDocumentRef?>,
    val initialTitle: String,
    val initialDiscount: ConsolidatedDiscount,
    val carriedRooms: Map<String, String>
)
