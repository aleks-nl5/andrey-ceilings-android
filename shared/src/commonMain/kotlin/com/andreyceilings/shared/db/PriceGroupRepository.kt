package com.andreyceilings.shared.db

import com.andreyceilings.shared.prices.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers

/** Groups are ordinary editable rows. UUID, never the name, owns item membership. */
class PriceGroupRepository(private val database: PrototypeDatabase) {
    private val queries = database.priceGroupsQueries
    fun all() = queries.selectGroups().executeAsList()
    fun observeAll() = queries.selectGroups().asFlow().mapToList(Dispatchers.Default)
    fun find(uuid: String) = queries.selectGroup(uuid).executeAsOneOrNull()
    fun findByName(name: String) = queries.selectGroupByName(name.trim()).executeAsOneOrNull()

    fun create(
        name: String, now: Long, defaultUnit: String? = null,
        defaultQuantityMode: QuantityMode = QuantityMode.MANUAL,
        defaultPlacementKind: PlacementKind = PlacementKind.NONE
    ): String {
        validate(name, defaultUnit, defaultQuantityMode)
        return queries.insertGroup(newRecordUuid(), name.trim(), defaultUnit?.trim(),
            defaultQuantityMode.value, defaultPlacementKind.value, now, now).executeAsOne()
    }

    /** For the P1 compatibility editor and the isolated legacy-import proof only. */
    fun ensureByName(name: String, now: Long): String = database.transactionWithResult {
        findByName(name)?.uuid ?: create(name, now)
    }

    fun update(
        uuid: String, expectedUpdatedAt: Long, name: String, now: Long,
        defaultUnit: String? = null, defaultQuantityMode: QuantityMode = QuantityMode.MANUAL,
        defaultPlacementKind: PlacementKind = PlacementKind.NONE
    ) {
        validate(name, defaultUnit, defaultQuantityMode)
        checkNotNull(queries.updateGroupIfVersion(name.trim(), defaultUnit?.trim(), defaultQuantityMode.value,
            defaultPlacementKind.value, now, uuid, expectedUpdatedAt).executeAsOneOrNull()) {
            "Групу змінено або видалено. Оновіть дані."
        }
    }

    fun delete(uuid: String, expectedUpdatedAt: Long, now: Long) {
        checkNotNull(queries.deleteGroupIfVersion(now, uuid, expectedUpdatedAt).executeAsOneOrNull()) {
            "Група містить активні позиції, змінена або видалена. Оновіть дані."
        }
    }

    private fun validate(name: String, unit: String?, mode: QuantityMode) {
        require(name.isNotBlank()) { "Вкажіть назву групи." }
        if (unit != null) PriceParameters.validate(unit.trim(), mode)
    }
}
