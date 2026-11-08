package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.estimate.*
import com.andreyceilings.shared.livepricing.*
import app.cash.sqldelight.coroutines.asFlow
import com.andreyceilings.shared.prices.*
import kotlinx.coroutines.*
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.flow.*

/** Price-free picker projection. Catalog CRUD and price calculation stay in their existing boundaries. */
data class ElementCatalogItem(val uuid: String, val groupUuid: String, val title: String,
    val code: String, val unit: String, val mode: QuantityMode, val placementKind: PlacementKind)
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
    private val quotePresenters = MutableStateFlow<Set<TransientQuotePresenter>>(emptySet())
    fun newQuote(measurementUuid: String): TransientQuotePresenter = TransientQuotePresenter(measurementUuid).also { presenter ->
        quotePresenters.update { if (closed) it else it + presenter }
        if (closed) releaseQuote(presenter)
    }
    fun releaseQuote(presenter: TransientQuotePresenter) { quotePresenters.update { it - presenter }; presenter.close() }
    private fun closeQuotes() { quotePresenters.getAndUpdate { emptySet() }.forEach { it.close() } }
    private val pricingReader = LivePricingReader(database)
    private val repository = MeasurementElementRepository(database)
    private val points = PointPlacementRepository(database)
    private val measurements = MeasurementRepository(database)
    private val projects = ProjectRepository(database)
    private val prices = PriceCatalogStore(PriceRepository(database), nowMillis)
    private var active: MeasurementElementsModel? = null
    // Registration and shutdown share one atomic state: closing cannot miss a Save
    // that was accepted synchronously but has not reached SQLDelight yet.
    private data class SessionWrites(
        val closed: Boolean = false,
        val pending: Set<CompletableDeferred<Unit>> = emptySet()
    )
    private val lifecycle = MutableStateFlow(SessionWrites())
    private val closed: Boolean get() = lifecycle.value.closed

    private val estimates = ImmutableEstimateRepository(database)

    suspend fun savedQuotes(projectId: Long? = null, measurementUuid: String? = null): List<SavedIndividualQuote> = estimateAccess {
        estimates.frozenIndividuals().filter { (projectId == null || it.projectId == projectId) &&
            (measurementUuid == null || it.measurementUuid == measurementUuid) }.map {
            val frozen = decodeFrozenIndividualEstimate(it.payload)
            verifyFrozenStoredIdentity(frozen, it.projectId, it.measurementUuid, it.publicNumber,
                it.subtotalCents, it.discountBasisPoints, it.discountCents, it.totalCents)
            SavedIndividualQuote(it.seriesUuid, it.createdAt, frozen, EstimateWorkflowStatus.valueOf(it.status), it.statusVersion)
        }
    }
    suspend fun savedQuote(seriesUuid: String): SavedIndividualQuote? = estimateAccess {
        estimates.frozenIndividual(seriesUuid)?.let {
            val payload = decodeFrozenIndividualEstimate(it.payload)
            verifyFrozenStoredIdentity(payload, it.projectId, it.measurementUuid, it.publicNumber,
                it.subtotalCents, it.discountBasisPoints, it.discountCents, it.totalCents)
            SavedIndividualQuote(it.seriesUuid, it.createdAt, payload, EstimateWorkflowStatus.valueOf(it.status), it.statusVersion)
        }
    }
    suspend fun setQuoteStatus(seriesUuid: String, expectedVersion: Long, status: EstimateWorkflowStatus): Boolean = estimateAccess {
        estimates.setWorkflowStatus(seriesUuid, expectedVersion, status, nowMillis())
    }
    private suspend fun <T> estimateAccess(block: () -> T): T {
        val completion = beginWrite() ?: throw IllegalStateException("Database closed")
        return withContext(NonCancellable + Dispatchers.Default) {
            try { database.transactionWithResult { check(!closed); block() } }
            finally { endWrite(completion) }
        }
    }

    /** Session owns the command and delivery, so Activity disposal cannot lose or duplicate a Save. */
    fun submitQuote(presenter: TransientQuotePresenter, projectId: Long) {
        val command = presenter.beginSave(projectId) ?: return
        val completion = beginWrite()
        if (completion == null) { presenter.saveFailed("Захищена база недоступна."); return }
        CoroutineScope(SupervisorJob() + modelDispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(NonCancellable + Dispatchers.Default) {
                    try {
                        database.transactionWithResult {
                            if (closed) throw IndividualEstimateSaveFailure(IndividualEstimateSaveFailure.Reason.UNAVAILABLE)
                            estimates.saveIndividual(command, kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).year, nowMillis())
                        }
                    } finally { endWrite(completion) }
                }
                presenter.saved(result.seriesUuid)
            } catch (failure: IndividualEstimateSaveFailure) {
                presenter.saveFailed(when (failure.reason) {
                    IndividualEstimateSaveFailure.Reason.STALE_SOURCE, IndividualEstimateSaveFailure.Reason.STALE_CATALOG -> "Замір або прайс змінився. Перевірте оновлений розрахунок."
                    IndividualEstimateSaveFailure.Reason.NUMBER_POLICY -> "Перевірте формат номера та суфікс у налаштуваннях."
                    else -> "Кошторис не збережено. Перевірте склад і доступність бази."
                })
            } catch (_: Exception) { presenter.saveFailed("Не вдалося підтвердити збереження. Повторіть ту саму дію або перевірте список кошторисів.") }
        }
    }

    internal fun beginWrite(): CompletableDeferred<Unit>? {
        val completion = CompletableDeferred<Unit>()
        val registered = lifecycle.updateAndGet { current ->
            if (current.closed) current else current.copy(pending = current.pending + completion)
        }
        return completion.takeIf { it in registered.pending }
    }

    internal fun endWrite(completion: CompletableDeferred<Unit>) {
        // Signal on the SQL worker before any Main-dispatcher continuation.
        completion.complete(Unit)
        lifecycle.update { it.copy(pending = it.pending - completion) }
    }

    private fun stopAcceptingWrites(): List<CompletableDeferred<Unit>> =
        lifecycle.updateAndGet { it.copy(closed = true) }.pending.toList()

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
    fun close() {
        pricingReader.closeAdmission()
        stopAcceptingWrites()
        closeQuotes()
        active?.close()
        active = null
    }

    /** Desktop must drain scheduled AND active writes before closing its JDBC driver.
     * Android production restores already hold the exclusive ProductionDatabaseGate
     * after SQLDelight transaction leases; never block inside that exclusive gate.
     */
    suspend fun closeAndAwaitWrites() {
        val inFlight = stopAcceptingWrites()
        closeQuotes()
        active?.close()
        active = null
        pricingReader.closeReads()
        inFlight.forEach { it.await() }
    }

    /** Android calls under its exclusive physical DB gate, which already drained outer transactions.
     * Desktop uses closeAndAwaitWrites to drain scheduled work before a reopenable JDBC driver closes.
     */
    suspend fun closePricingReads() {
        close()
        pricingReader.closeReads()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeQuotePricing(route: ElementRoute): Flow<PricingReadResult?> =
        database.livePricingQueries.selectPricingInvalidations().asFlow().transformLatest {
            emit(null)
            val completion = beginWrite()
            if (completion == null) emit(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE))
            else try {
                val result = withContext(Dispatchers.Default) {
                    // Admission to the reader happens AFTER the outer transaction/Android gate lease.
                    // Restore can drain the physical gate then await closeReads without a gate inversion.
                    database.transactionWithResult {
                        if (closed) PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE)
                        else pricingReader.read(route.projectId, route.measurementUuid)
                    }
                }
                emit(if (closed) PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE) else result)
            } finally { endWrite(completion) }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(PricingReadResult.Unavailable(PricingProblemCode.DATABASE_UNAVAILABLE))
        }.flowOn(Dispatchers.Default)

    internal fun observePricing(route: ElementRoute): Flow<MeasurementPricingState> = observeQuotePricing(route).map { result ->
        when (result) {
            null -> MeasurementPricingState.Loading
            is PricingReadResult.Loaded -> MeasurementPricingState.Loaded(result.buildPreview(),
                result.source.versions["measurementUpdatedAt"]?.toLongOrNull(),
                result.source.elements.associate { it.uuid to it.updatedAt })
            is PricingReadResult.Unavailable -> MeasurementPricingState.Unavailable(result.problem)
        }
    }

    internal fun observe(route: ElementRoute): Flow<MeasurementElementsState> = combine(
        projects.observeById(route.projectId), measurements.observeById(route.measurementId),
        repository.observeForMeasurement(route.measurementUuid)
    ) { project, measurement, items ->
        if (project == null || measurement == null || measurement.projectId != route.projectId ||
            measurement.uuid != route.measurementUuid) MeasurementElementsState.Missing
        else MeasurementElementsState.Loaded(items)
    }.onStart<MeasurementElementsState> { emit(MeasurementElementsState.Loading) }
        .catch { emit(MeasurementElementsState.Unavailable) }.flowOn(Dispatchers.Default)

    internal fun observeAreas(route:ElementRoute) = combine(projects.observeById(route.projectId),measurements.observeById(route.measurementId),
        AreaFeatureRepository(database).observeForMeasurement(route.measurementUuid)) {project,measurement,areas ->
        if(project==null || measurement?.uuid!=route.measurementUuid || measurement.projectId!=route.projectId)emptyList() else areas
    }.flowOn(Dispatchers.Default)

    internal fun observeLines(route:ElementRoute) = combine(projects.observeById(route.projectId),measurements.observeById(route.measurementId),
        LinePlacementRepository(database).observeForMeasurement(route.measurementUuid)) {project,measurement,lines ->
        if(project==null || measurement?.uuid!=route.measurementUuid || measurement.projectId!=route.projectId) emptyList() else lines
    }.flowOn(Dispatchers.Default)

    internal fun catalog(): Flow<ElementCatalogState> = combine(prices.groups, prices.items) { groups, items ->
        ElementCatalogState.Loaded(groups.map { ElementCatalogGroup(it.uuid, it.name) },
            items.filter { item -> groups.any { it.uuid == item.groupUuid } }.map {
                ElementCatalogItem(it.uuid, it.groupUuid, it.title, it.code, it.unit, it.quantityMode, it.placementKind)
            })
    }.onStart<ElementCatalogState> { emit(ElementCatalogState.Loading) }
        .catch { emit(ElementCatalogState.Unavailable) }.flowOn(Dispatchers.Default)

    internal fun observePoints(route: ElementRoute) = points.observeForMeasurement(route.measurementUuid)

    internal fun savePoint(route: ElementRoute, command: PointCommand): PointMoveResult = database.transactionWithResult {
        database.measurementElementsQueries.claimElementWrite(command.element.uuid)
        checkParent(route)
        if (command.element.measurementUuid != route.measurementUuid) throw PointPlacementFailure.ParentUnavailable()
        val point = when (command) {
            is PointCommand.Add -> points.addPoint(command.element, command.contourUpdatedAt,
                command.xMm, command.yMm, nowMillis(), command.uuid)
            is PointCommand.Remove -> points.removePoint(command.element, command.point, nowMillis())
            is PointCommand.Move -> return@transactionWithResult points.movePoint(command.element, command.point,
                command.contourUpdatedAt, command.xMm, command.yMm, command.precision, nowMillis(), command.uuid)
        }
        PointMoveResult(point, checkNotNull(repository.find(command.element.uuid)))
    }

    internal fun reloadPosition(route: ElementRoute, uuid: String): PointPositionDraft = database.transactionWithResult {
        checkParent(route)
        val point = points.find(uuid)?.takeIf { it.deletedAt == null } ?: throw PointPlacementFailure.StaleVersion()
        val element = reload(route, point.elementUuid)
        val row = checkNotNull(measurements.find(route.measurementId))
        val geometry = (measurementGeometryForViewing(row.shape, row.drawingJson, row.lengthMeters, row.widthMeters)
            as? MeasurementGeometryView.Captured)?.geometry ?: throw PointPlacementFailure.InvalidContour()
        PointPositionDraft(point, element.version, row.updatedAt, geometry)
    }

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
                item.quantityMode != selected.mode.value || item.groupUuid != selected.groupUuid ||
                item.placementKind != selected.placementKind.value)
                throw MeasurementElementFailure.CatalogUnavailable()
        }
        when (draft.action) {
            ElementEditorAction.CREATE_WISH -> repository.createWish(route.measurementUuid, draft.label, nowMillis(),
                draft.uuid, draft.note, draft.approval, draft.unit.takeIf { it.isNotBlank() }?.trim(), checkNotNull(draft.quantity()))
            ElementEditorAction.CREATE_CATALOG -> repository.createCatalogSelection(route.measurementUuid,
                checkNotNull(draft.selection).uuid, checkNotNull(draft.quantity()), nowMillis(), draft.uuid, draft.note, draft.approval, draft.businessRole ?: throw MeasurementElementFailure.RoleRequired())
            ElementEditorAction.EDIT -> repository.edit(checkNotNull(original).version, draft.note, draft.approval,
                checkNotNull(draft.quantity()), nowMillis(), draft.unitChange(),
                wishLabel = draft.label.takeIf { original.source == ElementSource.Wish }, businessRole = draft.businessRole)
            ElementEditorAction.LINK -> repository.linkWishToCatalog(checkNotNull(original).version,
                checkNotNull(draft.selection).uuid,
                if (draft.replaceQuantity) LinkQuantity.Replace(checkNotNull(draft.quantity())) else LinkQuantity.Keep, nowMillis(), draft.businessRole ?: throw MeasurementElementFailure.RoleRequired())
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
