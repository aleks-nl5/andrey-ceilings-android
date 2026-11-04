package com.andreyceilings.shared.prices

import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.newRecordUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.andreyceilings.shared.db.Price_catalog_items

/**
 * One cross-platform price catalog boundary for the Android and Windows CMP hosts.
 * All read/write rules live here; hosts provide only the SQLCipher-backed repository,
 * the platform clock and a background dispatcher for blocking SQLDelight operations.
 *
 * A failed precondition throws and leaves the existing record untouched. Never
 * resolve an identity conflict by overwriting another price item.
 */
class PriceCatalogStore(
    private val repository: PriceRepository,
    private val nowMillis: () -> Long
) {
    private val groupRepository = repository.groups
    val groups: Flow<List<PriceCatalogGroup>> = groupRepository.observeAll().map { rows ->
        rows.map { group -> PriceCatalogGroup(group.uuid, group.name, group.updatedAt, group.defaultUnit,
            QuantityMode.entries.single { it.value == group.defaultQuantityMode },
            PlacementKind.entries.single { it.value == group.defaultPlacementKind }) }
    }
    val items: Flow<List<PriceCatalogItem>> = combine(repository.observeAll(), groups) { rows, activeGroups ->
        val names = activeGroups.associate { it.uuid to it.name }
        rows.mapNotNull { row -> names[row.groupUuid]?.let { row.toCatalogItem(it) } }
    }

    private fun Price_catalog_items.toCatalogItem(categoryName: String = category) = PriceCatalogItem(
        id, code, title, categoryName, unit, price, updatedAt, groupUuid, uuid,
        QuantityMode.entries.single { it.value == quantityMode },
        PlacementKind.entries.single { it.value == placementKind })

    fun saveGroup(original: PriceCatalogGroup?, draft: PriceGroupDraft): String {
        require(draft.validationError() == null) { draft.validationError().orEmpty() }
        val unit = draft.defaultUnit.trim().takeIf { it.isNotEmpty() }
        if (original == null) return groupRepository.create(draft.name, nowMillis(), unit,
            draft.defaultQuantityMode, draft.defaultPlacementKind)
        groupRepository.update(original.uuid, original.updatedAt, draft.name, nowMillis(), unit,
            draft.defaultQuantityMode, draft.defaultPlacementKind)
        return original.uuid
    }

    fun deleteGroup(original: PriceCatalogGroup) =
        groupRepository.delete(original.uuid, original.updatedAt, nowMillis())

    /** P2 writes explicit parameters to an existing UUID. Never creates a group. */
    fun saveConfigured(original: PriceCatalogItem?, draft: ConfiguredPriceDraft): Long {
        require(draft.title.isNotBlank() && draft.groupUuid.isNotBlank()) { "Вкажіть назву та категорію." }
        PriceParameters.validate(draft.unit.trim(), draft.quantityMode)
        val price = requireNotNull(PriceCatalogRules.parseSalePrice(draft.salePriceText)) { "Неприпустима ціна продажу." }
        if (original == null) return repository.createInGroup("custom.${newRecordUuid()}", draft.title,
            draft.groupUuid, price, nowMillis(), draft.unit.trim(), draft.quantityMode, draft.placementKind)
        val current = unchanged(original)
        if (draft.groupUuid != original.groupUuid && draft.title == original.title && draft.unit == original.unit &&
            draft.quantityMode == original.quantityMode && draft.placementKind == original.placementKind && price == original.price)
            return repository.move(current, draft.groupUuid, nowMillis())
        return repository.updateConfigured(current, draft.groupUuid, draft.title, draft.unit,
            draft.quantityMode, draft.placementKind, price, nowMillis())
    }

    /** Only price comes from the draft; all other values are the exact CAS-protected row. */
    fun quickPrice(original: PriceCatalogItem, salePriceText: String): Long {
        val price = requireNotNull(PriceCatalogRules.parseSalePrice(salePriceText)) { "Неприпустима ціна продажу." }
        val current = unchanged(original)
        return repository.updateSalePrice(current, price, nowMillis())
    }

    private fun unchanged(original: PriceCatalogItem): Price_catalog_items {
        val row = repository.find(original.id) ?: throw PriceCatalogConflict()
        if (row.uuid != original.uuid || row.code != original.code || row.updatedAt != original.updatedAt)
            throw PriceCatalogConflict()
        return row
    }

    /**
     * Saves a single row without touching historical estimate snapshots.
     * Original row modification token protects against stale editor updates:
     * if another writer touched the row since it was displayed, require a reload.
     */
    fun save(original: PriceCatalogItem?, draft: PriceCatalogDraft): Long {
        val title = draft.title.trim()
        val category = draft.category.trim()
        val unit = draft.unit.trim()
        require(title.isNotBlank() && category.isNotBlank() && unit.isNotBlank()) {
            "Потрібно заповнити назву, категорію та одиницю."
        }
        // Check numeric cents without Double.toString(): high valid prices can be
        // rendered in scientific notation even though the input was ordinary decimal.
        val cents = draft.price * 100.0
        require(
            draft.price.isFinite() &&
                draft.price >= 0.0 && draft.price <= 999_999_999.99 &&
                kotlin.math.abs(cents - kotlin.math.round(cents)) < 0.0001
        ) { "Неприпустима ціна продажу." }

        if (original == null) {
            return repository.upsertReturningId(
                code = "custom.${newRecordUuid()}",
                title = title,
                category = category,
                unit = unit,
                price = draft.price,
                updatedAt = nowMillis()
            )
        }
        require(original.id > 0L && original.updatedAt > 0L) {
            "Для збереження потрібен актуальний запис прайсу."
        }
        return repository.updateIfUnchanged(
            id = original.id,
            expectedCode = original.code,
            expectedUpdatedAt = original.updatedAt,
            title = title,
            category = category,
            unit = unit,
            price = draft.price,
            updatedAt = nowMillis(),
            unchangedGroupUuid = original.groupUuid.takeIf { normalizedNameKey(draft.category) == normalizedNameKey(original.category) && it.isNotBlank() }
        )
    }

    /** Soft-delete only the exact version which was displayed to the user. */
    fun delete(original: PriceCatalogItem) {
        require(original.id > 0L && original.updatedAt > 0L) {
            "Для видалення потрібен актуальний запис прайсу."
        }
        repository.deleteIfUnchanged(
            id = original.id,
            expectedCode = original.code,
            expectedUpdatedAt = original.updatedAt
        )
    }
}
