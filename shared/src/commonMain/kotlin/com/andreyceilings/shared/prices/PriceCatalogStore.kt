package com.andreyceilings.shared.prices

import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.db.newRecordUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    val items: Flow<List<PriceCatalogItem>> = repository.observeAll().map { rows ->
        rows.map { row ->
            PriceCatalogItem(
                id = row.id,
                code = row.code,
                title = row.title,
                category = row.category,
                unit = row.unit,
                price = row.price,
                updatedAt = row.updatedAt
            )
        }
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

        val code = if (original == null) {
            "custom.${newRecordUuid()}"
        } else {
            require(original.id > 0L && original.updatedAt > 0L) {
                "Для збереження потрібен актуальний запис прайсу."
            }
            val current = checkNotNull(repository.find(original.id)) {
                "Позицію прайсу видалено. Оновіть перелік."
            }
            check(current.code == original.code &&
                current.updatedAt == original.updatedAt) {
                "Позицію вже змінено. Оновіть перелік перед редагуванням."
            }
            original.code
        }
        return repository.upsertReturningId(
            id = original?.id ?: 0L,
            code = code,
            title = title,
            category = category,
            unit = unit,
            price = draft.price,
            updatedAt = nowMillis()
        )
    }

    /** Soft-delete only the exact version which was displayed to the user. */
    fun delete(original: PriceCatalogItem) {
        require(original.id > 0L && original.updatedAt > 0L) {
            "Для видалення потрібен актуальний запис прайсу."
        }
        val current = checkNotNull(repository.find(original.id)) {
            "Позицію вже видалено."
        }
        check(current.code == original.code &&
            current.updatedAt == original.updatedAt) {
            "Позицію вже змінено. Оновіть перелік перед видаленням."
        }
        repository.delete(original.id)
    }
}
