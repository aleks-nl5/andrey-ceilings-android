package com.andreyceilings.shared.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.PlacementKind
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Shared write boundary. UUIDs (never local row IDs) determine ownership. */
class MeasurementElementRepository(private val database: PrototypeDatabase) {
    private val queries = database.measurementElementsQueries

    fun all(): List<MeasurementElement> = access { queries.selectAll().executeAsList().map { it.model() } }
    fun active(): List<MeasurementElement> = access { queries.selectActive().executeAsList().map { it.model() } }
    fun tombstones(): List<MeasurementElement> = access { queries.selectTombstones(::Measurement_element_records).executeAsList().map { it.model() } }
    fun find(uuid: String): MeasurementElement? = access { read(uuid) }
    fun observeForMeasurement(measurementUuid: String): Flow<List<MeasurementElement>> =
        queries.selectForMeasurement(measurementUuid).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.model() } }
            .catch { failure ->
                if (failure is CancellationException || failure is MeasurementElementFailure) throw failure
                throw MeasurementElementFailure.DatabaseUnavailable(failure)
            }

    /** Reuse the caller's UUID for retries; omit only for a new logical operation. */
    fun createWish(
        measurementUuid: String, label: String, now: Long, uuid: String = newRecordUuid(),
        note: String = "", approval: ElementApproval = ElementApproval.DISCUSSING,
        unit: String? = null, quantity: ElementQuantity = ElementQuantity.Unknown
    ): MeasurementElement = write(uuid) {
        input(uuid, measurementUuid, now)
        val name = label.trim(); val ownUnit = unit?.trim()
        if (name.isBlank()) throw MeasurementElementFailure.InvalidInput()
        ElementQuantityRules.validate(quantity, ownUnit)
        parent(measurementUuid)
        read(uuid)?.let { existing ->
            return@write retry(existing, measurementUuid, existing.source == ElementSource.Wish &&
                existing.label == name && existing.note == note && existing.approval == approval &&
                existing.unit == ownUnit && existing.quantity == quantity)
        }
        insert(uuid, measurementUuid, "wish", null, name, note, null, ownUnit, null, null, approval, quantity, now)
    }

    fun createCatalogSelection(
        measurementUuid: String, priceItemUuid: String, quantity: ElementQuantity, now: Long,
        uuid: String = newRecordUuid(), note: String = "", approval: ElementApproval = ElementApproval.DISCUSSING
    ): MeasurementElement = write(uuid) {
        input(uuid, measurementUuid, now)
        parent(measurementUuid)
        read(uuid)?.let { existing ->
            return@write retry(existing, measurementUuid,
                (existing.source as? ElementSource.CatalogSelection)?.priceItemUuid == priceItemUuid &&
                    existing.note == note && existing.approval == approval && existing.quantity == quantity)
        }
        val item = catalog(priceItemUuid)
        ElementQuantityRules.validate(quantity, item.unit, item.mode())
        insert(uuid, measurementUuid, "catalog_item", item.uuid, item.title, note, item.code,
            item.unit, item.quantityMode, item.placementKind, approval, quantity, now)
    }

    /** Catalog identity and selection-time metadata have no setters in this operation. */
    fun edit(
        original: ElementVersion, note: String, approval: ElementApproval, quantity: ElementQuantity,
        now: Long, wishLabel: String? = null, wishUnit: String? = null
    ): MeasurementElement = write(original.uuid) {
        val current = current(original, now)
        val wish = current.source == ElementSource.Wish
        if (!wish && (wishLabel != null || wishUnit != null)) throw MeasurementElementFailure.InvalidInput()
        val label = if (wish) (wishLabel ?: current.label).trim() else current.label
        val unit = if (wish) wishUnit?.trim() ?: current.unit else current.unit
        if (label.isBlank()) throw MeasurementElementFailure.InvalidInput()
        ElementQuantityRules.validate(quantity, unit, (current.source as? ElementSource.CatalogSelection)?.quantityMode)
        val saved = queries.editElement(label, note, approval.value, unit, quantity.kind(), quantity.exact(),
            quantity.min(), quantity.max(), now, original.uuid, original.measurementUuid, original.expectedUpdatedAt)
            .executeAsOneOrNull()
        written(saved, original.uuid)
    }

    fun linkWishToCatalog(
        original: ElementVersion, priceItemUuid: String, quantity: LinkQuantity, now: Long
    ): MeasurementElement = write(original.uuid) {
        val current = current(original, now)
        if (current.source != ElementSource.Wish) throw MeasurementElementFailure.InvalidInput()
        val item = catalog(priceItemUuid)
        val chosen = when (quantity) {
            LinkQuantity.Keep -> {
                if (current.unit != null && current.unit != item.unit) throw MeasurementElementFailure.IncompatibleQuantity()
                try { ElementQuantityRules.validate(current.quantity, item.unit, item.mode()) }
                catch (_: MeasurementElementFailure.InvalidQuantity) { throw MeasurementElementFailure.IncompatibleQuantity() }
                current.quantity
            }
            is LinkQuantity.Replace -> quantity.quantity.also { ElementQuantityRules.validate(it, item.unit, item.mode()) }
        }
        val saved = queries.linkWish(item.uuid, item.title, item.code, item.unit, item.quantityMode, item.placementKind,
            chosen.kind(), chosen.exact(), chosen.min(), chosen.max(), now,
            original.uuid, original.measurementUuid, original.expectedUpdatedAt).executeAsOneOrNull()
        written(saved, original.uuid)
    }

    fun delete(original: ElementVersion, now: Long): MeasurementElement = write(original.uuid) {
        current(original, now)
        written(queries.deleteElement(now, original.uuid, original.measurementUuid, original.expectedUpdatedAt)
            .executeAsOneOrNull(), original.uuid)
    }

    private fun current(original: ElementVersion, now: Long): MeasurementElement {
        input(original.uuid, original.measurementUuid, now)
        parent(original.measurementUuid)
        val current = read(original.uuid) ?: throw MeasurementElementFailure.StaleVersion()
        if (current.measurementUuid != original.measurementUuid || current.updatedAt != original.expectedUpdatedAt ||
            current.deletedAt != null || current.updatedAt == Long.MAX_VALUE) throw MeasurementElementFailure.StaleVersion()
        return current
    }

    private fun retry(existing: MeasurementElement, parent: String, same: Boolean): MeasurementElement {
        if (existing.measurementUuid != parent || existing.deletedAt != null || !same)
            throw MeasurementElementFailure.RetryConflict()
        return existing
    }

    private fun insert(uuid: String, parent: String, kind: String, item: String?, label: String, note: String,
        code: String?, unit: String?, mode: String?, placement: String?, approval: ElementApproval,
        quantity: ElementQuantity, now: Long): MeasurementElement = written(
        queries.insertElement(uuid, parent, kind, item, label, note, code, unit, mode, placement, approval.value,
            quantity.kind(), quantity.exact(), quantity.min(), quantity.max(), now).executeAsOneOrNull(), uuid)

    private fun written(saved: String?, uuid: String): MeasurementElement {
        if (saved == null) throw MeasurementElementFailure.StaleVersion()
        return read(uuid) ?: throw MeasurementElementFailure.StaleVersion()
    }
    private fun read(uuid: String) = queries.selectByUuid(uuid).executeAsOneOrNull()?.model()
    private fun parent(uuid: String) {
        if (queries.selectActiveParent(uuid).executeAsOneOrNull() == null) throw MeasurementElementFailure.ParentUnavailable()
    }
    private fun catalog(uuid: String): Price_items = queries.selectActiveCatalogItem(uuid).executeAsOneOrNull()
        ?: throw MeasurementElementFailure.CatalogUnavailable()
    private fun input(uuid: String, parent: String, now: Long) {
        if (uuid.isBlank() || parent.isBlank() || now < 0) throw MeasurementElementFailure.InvalidInput()
    }
    private fun <T> write(uuid: String, block: () -> T): T = access {
        database.transactionWithResult {
            queries.claimElementWrite(uuid)
            block()
        }
    }
    private fun <T> access(block: () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: MeasurementElementFailure) { throw failure }
        catch (failure: Exception) { throw MeasurementElementFailure.DatabaseUnavailable(failure) }
}

private fun Price_items.mode() = QuantityMode.entries.single { it.value == quantityMode }
private fun ElementQuantity.kind() = when (this) {
    ElementQuantity.Unknown -> "unknown"
    is ElementQuantity.Exact -> "exact"
    is ElementQuantity.Range -> "range"
    ElementQuantity.CeilingArea -> "ceiling_area"
    ElementQuantity.CeilingPerimeter -> "ceiling_perimeter"
}
private fun ElementQuantity.exact() = (this as? ElementQuantity.Exact)?.milli
private fun ElementQuantity.min() = (this as? ElementQuantity.Range)?.minMilli
private fun ElementQuantity.max() = (this as? ElementQuantity.Range)?.maxMilli
private fun Measurement_element_records.model() = MeasurementElement(
    uuid, measurementUuid,
    if (kind == "wish") ElementSource.Wish else ElementSource.CatalogSelection(
        checkNotNull(priceItemUuid), checkNotNull(priceItemCode), QuantityMode.entries.single { it.value == quantityMode },
        PlacementKind.entries.single { it.value == placementKind },
        if (catalogAvailability == "active") CatalogAvailability.ACTIVE else CatalogAvailability.DELETED_OR_MISSING),
    label, note, unit, ElementApproval.entries.single { it.value == approvalStatus },
    when (quantityKind) {
        "exact" -> ElementQuantity.Exact(checkNotNull(quantityMilli))
        "range" -> ElementQuantity.Range(checkNotNull(quantityMinMilli), checkNotNull(quantityMaxMilli))
        "ceiling_area" -> ElementQuantity.CeilingArea
        "ceiling_perimeter" -> ElementQuantity.CeilingPerimeter
        else -> ElementQuantity.Unknown
    }, ElementPlacement.entries.single { it.value == placementStatus }, placedQuantityMilli, createdAt, updatedAt, deletedAt)
