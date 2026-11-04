package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Price-free picker projection. Catalog CRUD and price calculation stay in their existing boundaries. */
data class ElementCatalogItem(val uuid: String, val groupUuid: String, val title: String,
    val code: String, val unit: String, val mode: QuantityMode)
data class ElementCatalogGroup(val uuid: String, val name: String)
sealed interface ElementCatalogState {
    data object Loading : ElementCatalogState
    data object Unavailable : ElementCatalogState
    data class Loaded(val groups: List<ElementCatalogGroup>, val items: List<ElementCatalogItem>) : ElementCatalogState
}
fun ElementCatalogState.Loaded.filtered(query: String, groupUuid: String?): List<ElementCatalogItem> {
    val term = normalizedNameKey(query)
    return items.filter { (groupUuid == null || it.groupUuid == groupUuid) &&
        (term.isEmpty() || normalizedNameKey(it.title).contains(term) || normalizedNameKey(it.code).contains(term)) }
}

data class ElementRoute(val projectId: Long, val measurementId: Long, val measurementUuid: String)
sealed interface MeasurementElementsState {
    data object Loading : MeasurementElementsState
    data object Unavailable : MeasurementElementsState
    data object Missing : MeasurementElementsState
    data class Loaded(val items: List<MeasurementElement>) : MeasurementElementsState
}

/** One registry per unlocked DB session. Drafts never enter Android saved state or route snapshots. */
class MeasurementElementsStore(private val database: PrototypeDatabase, private val nowMillis: () -> Long,
    private val modelDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private val repository = MeasurementElementRepository(database)
    private val measurements = MeasurementRepository(database)
    private val projects = ProjectRepository(database)
    private val prices = PriceCatalogStore(PriceRepository(database), nowMillis)
    private var active: MeasurementElementsModel? = null
    private val closedState = MutableStateFlow(false)
    private var closed: Boolean
        get() = closedState.value
        set(value) { closedState.value = value }

    fun model(route: ElementRoute): MeasurementElementsModel {
        if (closed) return MeasurementElementsModel(this, route, modelDispatcher).also { it.close() }
        active?.takeIf { it.route == route }?.let { return it }
        active?.close()
        return MeasurementElementsModel(this, route, modelDispatcher).also {
            active = it
            // DB close may run on a platform worker while the UI acquires its route model.
            if (closed) { it.close(); active = null }
        }
    }
    fun close() { closed = true; active?.close(); active = null }

    internal fun observe(route: ElementRoute): Flow<MeasurementElementsState> = combine(
        projects.observeById(route.projectId), measurements.observeById(route.measurementId),
        repository.observeForMeasurement(route.measurementUuid)
    ) { project, measurement, items ->
        if (project == null || measurement == null || measurement.projectId != route.projectId ||
            measurement.uuid != route.measurementUuid) MeasurementElementsState.Missing
        else MeasurementElementsState.Loaded(items)
    }.onStart<MeasurementElementsState> { emit(MeasurementElementsState.Loading) }
        .catch { emit(MeasurementElementsState.Unavailable) }.flowOn(Dispatchers.Default)

    internal fun catalog(): Flow<ElementCatalogState> = combine(prices.groups, prices.items) { groups, items ->
        ElementCatalogState.Loaded(groups.map { ElementCatalogGroup(it.uuid, it.name) },
            items.filter { item -> groups.any { it.uuid == item.groupUuid } }.map {
                ElementCatalogItem(it.uuid, it.groupUuid, it.title, it.code, it.unit, it.quantityMode)
            })
    }.onStart<ElementCatalogState> { emit(ElementCatalogState.Loading) }
        .catch { emit(ElementCatalogState.Unavailable) }.flowOn(Dispatchers.Default)

    internal fun save(route: ElementRoute, draft: ElementEditorDraft): MeasurementElement = database.transactionWithResult {
        // Take the same writer lock before checking the displayed picker metadata. The repository
        // repeats UUID/active checks and CAS in its nested transaction; no schema/API changes.
        database.measurementElementsQueries.claimElementWrite(draft.uuid)
        checkParent(route)
        val original = draft.original
        if (draft.action == ElementEditorAction.LINK ||
            draft.action == ElementEditorAction.CREATE_CATALOG && repository.find(draft.uuid) == null) {
            val selected = draft.selection ?: throw MeasurementElementFailure.InvalidInput()
            val item = database.measurementElementsQueries.selectActiveCatalogItem(selected.uuid).executeAsOneOrNull()
                ?: throw MeasurementElementFailure.CatalogUnavailable()
            if (item.title != selected.title || item.code != selected.code || item.unit != selected.unit ||
                item.quantityMode != selected.mode.value || item.groupUuid != selected.groupUuid)
                throw MeasurementElementFailure.CatalogUnavailable()
        }
        when (draft.action) {
            ElementEditorAction.CREATE_WISH -> repository.createWish(route.measurementUuid, draft.label, nowMillis(),
                draft.uuid, draft.note, draft.approval, draft.unit.takeIf { it.isNotBlank() }?.trim(), checkNotNull(draft.quantity()))
            ElementEditorAction.CREATE_CATALOG -> repository.createCatalogSelection(route.measurementUuid,
                checkNotNull(draft.selection).uuid, checkNotNull(draft.quantity()), nowMillis(), draft.uuid, draft.note, draft.approval)
            ElementEditorAction.EDIT -> repository.edit(checkNotNull(original).version, draft.note, draft.approval,
                checkNotNull(draft.quantity()), nowMillis(), draft.unitChange(),
                wishLabel = draft.label.takeIf { original.source == ElementSource.Wish })
            ElementEditorAction.LINK -> repository.linkWishToCatalog(checkNotNull(original).version,
                checkNotNull(draft.selection).uuid,
                if (draft.replaceQuantity) LinkQuantity.Replace(checkNotNull(draft.quantity())) else LinkQuantity.Keep, nowMillis())
            ElementEditorAction.DELETE -> repository.delete(checkNotNull(original).version, nowMillis())
            ElementEditorAction.CHOOSE -> throw MeasurementElementFailure.InvalidInput()
        }
    }
    private fun checkParent(route: ElementRoute) {
        if (closed) throw MeasurementElementFailure.DatabaseUnavailable(IllegalStateException("Session closed"))
        val row = measurements.find(route.measurementId)
        if (projects.find(route.projectId) == null || row?.projectId != route.projectId || row.uuid != route.measurementUuid)
            throw MeasurementElementFailure.ParentUnavailable()
    }
    internal fun reload(route: ElementRoute, uuid: String): MeasurementElement {
        checkParent(route)
        return repository.find(uuid)?.takeIf { it.deletedAt == null && it.measurementUuid == route.measurementUuid }
            ?: throw MeasurementElementFailure.StaleVersion()
    }
}
