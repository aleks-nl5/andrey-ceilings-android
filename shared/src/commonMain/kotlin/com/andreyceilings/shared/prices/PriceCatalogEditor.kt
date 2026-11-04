package com.andreyceilings.shared.prices

/** Immutable group/version snapshots for forms; names are always resolved by UUID. */
data class PriceCatalogGroup(
    val uuid: String,
    val name: String,
    val updatedAt: Long,
    val defaultUnit: String?,
    val defaultQuantityMode: QuantityMode,
    val defaultPlacementKind: PlacementKind
)

data class PriceGroupDraft(
    val name: String = "",
    val defaultUnit: String = "",
    val defaultQuantityMode: QuantityMode = QuantityMode.MANUAL,
    val defaultPlacementKind: PlacementKind = PlacementKind.NONE
) {
    fun validationError(): String? = when {
        name.isBlank() -> "Вкажіть назву категорії."
        defaultUnit.isNotBlank() -> parameterError(defaultUnit, defaultQuantityMode)
        else -> null
    }

    companion object {
        fun from(group: PriceCatalogGroup) = PriceGroupDraft(group.name, group.defaultUnit.orEmpty(),
            group.defaultQuantityMode, group.defaultPlacementKind)
    }
}

/** P2 only: no free-text category and no implicit ensureByName on Save. */
data class ConfiguredPriceDraft(
    val title: String = "",
    val groupUuid: String = "",
    val unit: String = "",
    val salePriceText: String = "",
    val quantityMode: QuantityMode = QuantityMode.MANUAL,
    val placementKind: PlacementKind = PlacementKind.NONE,
    val defaultsCopied: Boolean = false,
    val parametersEdited: Boolean = false
) {
    fun selectGroup(group: PriceCatalogGroup): ConfiguredPriceDraft =
        if (!defaultsCopied && !parametersEdited) withDefaults(group).copy(groupUuid = group.uuid)
        else copy(groupUuid = group.uuid)

    /** Explicit action changes only the three parameters, never membership/title/price. */
    fun withDefaults(group: PriceCatalogGroup) = copy(unit = group.defaultUnit.orEmpty(),
        quantityMode = group.defaultQuantityMode, placementKind = group.defaultPlacementKind,
        defaultsCopied = true, parametersEdited = false)

    fun validationError(groups: List<PriceCatalogGroup>): String? = when {
        title.isBlank() -> "Вкажіть назву позиції."
        groupUuid.isBlank() -> "Оберіть категорію."
        groups.none { it.uuid == groupUuid } -> "Категорію видалено. Оберіть активну категорію."
        unit.isBlank() -> "Вкажіть одиницю вимірювання."
        parameterError(unit, quantityMode) != null -> parameterError(unit, quantityMode)
        PriceCatalogRules.parseSalePrice(salePriceText) == null ->
            "Вкажіть ціну продажу від 0 до 999 999 999,99 (до 2 знаків після коми)."
        else -> null
    }

    fun hasChanges(initial: ConfiguredPriceDraft, quick: Boolean): Boolean {
        val priceChanged = if (salePriceText.trim() == initial.salePriceText.trim()) false
            else PriceCatalogRules.parseSalePrice(salePriceText)?.let {
                it != PriceCatalogRules.parseSalePrice(initial.salePriceText)
            } ?: true
        return priceChanged || (!quick && (title != initial.title || groupUuid != initial.groupUuid ||
            unit != initial.unit || quantityMode != initial.quantityMode || placementKind != initial.placementKind))
    }

    companion object {
        fun from(item: PriceCatalogItem) = ConfiguredPriceDraft(item.title, item.groupUuid, item.unit,
            PriceCatalogRules.formatSalePrice(item.price), item.quantityMode, item.placementKind, true)
    }
}

/** Screen-owned state survives the sequential child category form and its cancellation. */
data class PriceItemEditor(
    val original: PriceCatalogItem?,
    val quick: Boolean,
    val initial: ConfiguredPriceDraft,
    val draft: ConfiguredPriceDraft = initial,
    val focusField: String = "title"
) {
    fun categoryCreated(group: PriceCatalogGroup) = copy(draft = draft.selectGroup(group))
    companion object {
        fun open(item: PriceCatalogItem?, quick: Boolean = false) =
            PriceItemEditor(item, quick, item?.let(ConfiguredPriceDraft::from) ?: ConfiguredPriceDraft(),
                focusField = if (quick) "price" else "title")
    }
}

class PriceCatalogConflict : IllegalStateException("Дані змінилися. Оновіть і повторіть")
class PriceGroupNotEmpty : IllegalStateException("Спочатку перемістіть або видаліть позиції цієї категорії")

internal fun parameterError(unit: String, mode: QuantityMode): String? = try {
    PriceParameters.validate(unit.trim(), mode)
    null
} catch (_: IllegalArgumentException) {
    when (mode) {
        QuantityMode.CEILING_AREA -> "Для режиму «За площею стелі» оберіть одиницю м²."
        QuantityMode.CEILING_PERIMETER, QuantityMode.ENTERED_LENGTH ->
            "Для обраного режиму кількості оберіть одиницю м.п."
        QuantityMode.MANUAL -> "Вкажіть одиницю вимірювання."
    }
}

val QuantityMode.label: String get() = when (this) {
    QuantityMode.MANUAL -> "Вручну"
    QuantityMode.CEILING_AREA -> "За площею стелі"
    QuantityMode.CEILING_PERIMETER -> "За периметром стелі"
    QuantityMode.ENTERED_LENGTH -> "За введеною довжиною"
}
val PlacementKind.label: String get() = when (this) {
    PlacementKind.NONE -> "Без розміщення"
    PlacementKind.POINT -> "Точка"
    PlacementKind.LINE -> "Лінія"
    PlacementKind.AREA -> "Площа"
}

/** Group Flow owns labels and membership visibility, including pure group renames. */
fun visiblePriceItems(items: List<PriceCatalogItem>, groups: List<PriceCatalogGroup>, query: String,
    groupUuid: String?): List<PriceCatalogItem> {
    val byUuid = groups.associateBy { it.uuid }
    val term = normalizedNameKey(query)
    return items.mapNotNull { item -> byUuid[item.groupUuid]?.let { item.copy(category = it.name) } }
        .filter { groupUuid == null || it.groupUuid == groupUuid }
        .filter { term.isEmpty() || normalizedNameKey(it.title).contains(term) || it.code.lowercase().contains(term) }
        .sortedWith(compareBy({ normalizedNameKey(it.category) }, { normalizedNameKey(it.title) }, { it.id }))
}

fun activePriceFilter(groupUuid: String?, groups: List<PriceCatalogGroup>): String? =
    groupUuid?.takeIf { uuid -> groups.any { it.uuid == uuid } }
