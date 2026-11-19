package com.andreyceilings.shared.estimate

import kotlinx.coroutines.flow.MutableStateFlow

/** UI-only selection retained by the unlocked database session across Activity recreation. */
internal class ConsolidatedComposerSession(val projectId: Long) {
    val selection = MutableStateFlow<Map<String, ConsolidatedDocumentRef?>>(emptyMap())
    val previewing = MutableStateFlow(false)
}

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
    val finalCents: Long,
    val selectedForWork: Boolean
)

internal data class ConsolidatedRoomCatalog(val projectUuid: String, val rooms: List<ConsolidatedRoomOption>)

internal data class ConsolidatedRoomOption(
    val id: Long,
    val uuid: String,
    val name: String,
    val revisions: List<ConsolidatedRevisionOption>,
    val unavailableRevisions: Boolean
)

internal data class ConsolidatedEstimateDocument(
    val project: String,
    val currentAddress: String?,
    val sections: List<Section>,
    val totalCents: Long
) {
    data class Section(val document: FrozenEstimateDocument, val status: EstimateWorkflowStatus?)

    val fileName: String get() = "zvedenyi-koshtorys.pdf"
}

internal enum class ConsolidatedDocumentProblem(val label: String) {
    EMPTY("Оберіть приміщення."),
    DUPLICATE("Для кожного приміщення потрібна одна окрема версія."),
    PROJECT_MISSING("Об’єкт недоступний або змінився."),
    ROOM_MISSING("Вибране приміщення більше недоступне."),
    REVISION_MISSING("Вибрана версія більше недоступна."),
    FOREIGN_REVISION("Вибрана версія належить іншому приміщенню або об’єкту."),
    STALE("Збережена версія змінилася. Відкрийте вибір повторно."),
    INVALID_REVISION("Не вдалося перевірити збережені дані вибраної версії."),
    UNSUPPORTED_FORMAT("Формат вибраної збереженої версії не підтримується."),
    INVALID_DRAWING("Не вдалося прочитати збережене креслення вибраної версії."),
    CURRENCY("Валюта вибраної збереженої версії не підтримується."),
    OVERFLOW("Підсумок перевищує допустиму суму.")
}

internal sealed interface ConsolidatedDocumentResult {
    data class Ready(val document: ConsolidatedEstimateDocument) : ConsolidatedDocumentResult
    data class Rejected(val problem: ConsolidatedDocumentProblem) : ConsolidatedDocumentResult
}
