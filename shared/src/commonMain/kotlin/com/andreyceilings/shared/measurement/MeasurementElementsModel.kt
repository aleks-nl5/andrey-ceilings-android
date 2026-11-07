package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.prices.QuantityMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ElementEditorAction { CHOOSE, CREATE_WISH, CREATE_CATALOG, EDIT, LINK, DELETE }

data class ElementEditorDraft(
    val action: ElementEditorAction = ElementEditorAction.CHOOSE,
    val uuid: String = newRecordUuid(), val original: MeasurementElement? = null,
    val label: String = original?.label.orEmpty(), val note: String = original?.note.orEmpty(),
    val unit: String = original?.unit.orEmpty(),
    val approval: ElementApproval = original?.approval ?: ElementApproval.DISCUSSING,
    val input: ElementQuantityInput = ElementQuantityInput.from(original?.quantity ?: ElementQuantity.Unknown),
    val selection: ElementCatalogItem? = null, val replaceQuantity: Boolean = false,
    val businessRole: BusinessRole? = original?.businessRole
) {
    val catalog: Boolean get() = action == ElementEditorAction.CREATE_CATALOG || action == ElementEditorAction.LINK ||
        original?.source is ElementSource.CatalogSelection
    val quantityUnit: String? get() = if (catalog) selection?.unit ?: original?.unit else unit.trim().takeIf { it.isNotEmpty() }
    val mode: QuantityMode? get() = if (catalog) selection?.mode ?: (original?.source as? ElementSource.CatalogSelection)?.quantityMode else null
    fun quantity(): ElementQuantity? = input.quantity(quantityUnit, mode)
    fun unitChange(): WishUnitChange = when {
        original?.source != ElementSource.Wish || unit == original.unit.orEmpty() -> WishUnitChange.Keep
        unit.isBlank() -> WishUnitChange.Clear
        else -> WishUnitChange.Set(unit.trim())
    }
    val compatibleKeep: Boolean get() {
        val item = selection ?: return false
        val before = original ?: return false
        return (before.unit == null || before.unit == item.unit) && runCatching {
            ElementQuantityRules.validate(before.quantity, item.unit, item.mode)
        }.isSuccess
    }
    val validationError: String? get() = when {
        action == ElementEditorAction.CHOOSE -> "Оберіть спосіб додавання."
        action == ElementEditorAction.DELETE -> null
        (action == ElementEditorAction.CREATE_CATALOG || action == ElementEditorAction.LINK) && selection == null -> "Оберіть позицію прайсу."
        catalog && businessRole == null && (original?.businessRole != null || action != ElementEditorAction.EDIT) -> "Оберіть роль: полотно, основний профіль або інші роботи."
        catalog && businessRole == BusinessRole.CANVAS && (mode != QuantityMode.CEILING_AREA || quantityUnit != "м²") -> "Полотно потребує позиції з кількістю за площею стелі та одиницею м². Оберіть сумісну позицію."
        catalog && businessRole == BusinessRole.PRIMARY_PROFILE && (mode != QuantityMode.CEILING_PERIMETER || quantityUnit != "м.п.") -> "Основний профіль потребує позиції з кількістю за периметром стелі та одиницею м.п. Оберіть сумісну позицію."
        !catalog && label.isBlank() -> "Вкажіть назву побажання."
        action == ElementEditorAction.LINK && !replaceQuantity ->
            if (compatibleKeep) null else "Кількість або одиниця несумісні. Явно задайте нову кількість."
        quantity() == null -> "Вкажіть додатну кількість до 3 знаків після коми; шт. — цілі, межі діапазону — від меншої до більшої. Одиниця обов'язкова."
        else -> null
    }
    companion object {
        fun edit(element: MeasurementElement, action: ElementEditorAction = ElementEditorAction.EDIT) =
            ElementEditorDraft(action = action, uuid = element.uuid, original = element)
    }
}

data class ElementEditorState(
    val draft: ElementEditorDraft, val baseline: ElementEditorDraft = draft,
    val saving: Boolean = false, val error: String? = null, val conflict: Boolean = false,
    val exitRequested: Boolean = false, val reloadRequested: Boolean = false,
    /** A create whose result may have committed must retry the exact same UUID AND payload. */
    val attemptedCreate: Boolean = false
) { val dirty: Boolean get() = draft != baseline }

fun elementFailureMessage(failure: Throwable): String = when (failure) {
    is MeasurementElementFailure.RoleRequired -> "Явно оберіть роль позиції в цьому замірі."
    is MeasurementElementFailure.IncompatibleRole -> "Роль несумісна з режимом кількості або одиницею. Параметри позиції не змінено."
    is MeasurementElementFailure.DuplicateRole -> "У цьому замірі вже є полотно або основний профіль. Спершу змініть роль чи видаліть попередній елемент."
    is MeasurementElementFailure.StaleVersion -> "Запис змінено або видалено іншим редактором. Чернетку збережено; перезавантажте актуальні дані."
    is MeasurementElementFailure.ParentUnavailable -> "Замір або об'єкт уже недоступний. Збереження заблоковано."
    is MeasurementElementFailure.CatalogUnavailable -> "Позиція або категорія змінилася чи більше недоступна у прайсі. Оновіть каталог і явно оберіть позицію."
    is MeasurementElementFailure.RetryConflict -> "Ця спроба створення вже має інші дані або запис видалено. Перезавантажте актуальні дані; чернетку збережено."
    is MeasurementElementFailure.IncompatibleQuantity -> "Несумісна кількість. Оберіть і підтвердьте нову кількість для цієї позиції."
    is MeasurementElementFailure.InvalidQuantity -> "Кількість не відповідає одиниці або актуальному режиму прайсу. Перевірте вибір і кількість."
    is MeasurementElementFailure.PlacementsInUse -> "Спочатку приберіть розміщені позначки, потім зменшуйте кількість або змінюйте її режим."
    is MeasurementElementFailure.InvalidInput -> "Перевірте назву й одиницю елемента."
    else -> "Захищена база недоступна. Перевірте сесію та вільне місце й повторіть. Чернетку збережено."
}

/** UI-thread intents; SQL and Flow work on Default. Lifetime belongs to the unlocked session. */
class MeasurementElementsModel internal constructor(private val store: MeasurementElementsStore, val route: ElementRoute, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutablePricing = MutableStateFlow<MeasurementPricingState>(MeasurementPricingState.Loading)
    val pricing = mutablePricing.asStateFlow()
    private var pricingJob: Job? = null
    fun refreshPricing() {
        if (closed) return
        pricingJob?.cancel()
        mutablePricing.value = MeasurementPricingState.Loading
        pricingJob = scope.launch { store.observePricing(route).collect { result ->
            mutablePricing.update { if (closed) MeasurementPricingState.Unavailable(
                com.andreyceilings.shared.livepricing.PricingProblemCode.DATABASE_UNAVAILABLE) else result }
        } }
    }
    private val mutableState = MutableStateFlow<MeasurementElementsState>(MeasurementElementsState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableCatalog = MutableStateFlow<ElementCatalogState>(ElementCatalogState.Loading)
    val catalog = mutableCatalog.asStateFlow()
    private val mutableEditor = MutableStateFlow<ElementEditorState?>(null)
    val editor = mutableEditor.asStateFlow()
    private val mutablePoints = MutableStateFlow(PointPlacementState())
    val points = mutablePoints.asStateFlow()
    private val mutableLines=MutableStateFlow<List<LinePlacement>>(emptyList())
    val lines=mutableLines.asStateFlow()
    private val mutableAreas=MutableStateFlow<List<AreaFeature>>(emptyList())
    val areas=mutableAreas.asStateFlow()
    private val committedPoints = MutableStateFlow<Map<String, PointPlacement>>(emptyMap())
    private fun updatePoints(change: (PointPlacementState) -> PointPlacementState) {
        mutablePoints.update { if (closed) PointPlacementState() else change(it) }
    }
    private var dataJob: Job? = null
    private var catalogJob: Job? = null
    private var exitAction: (() -> Unit)? = null
    private val closedState = MutableStateFlow(false)
    private var closed: Boolean
        get() = closedState.value
        set(value) { closedState.value = value }
    private var currentEditor: ElementEditorState?
        get() = mutableEditor.value
        set(value) { mutableEditor.update { if (closed) null else value } }
    init {
        // Pricing is read only while its measurement info view has a subscriber.
        scope.launch {
            mutablePricing.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { visible ->
                if (visible) refreshPricing()
                else {
                    pricingJob?.cancel()
                    if (!closed) mutablePricing.value = MeasurementPricingState.Loading
                }
            }
        }
        retry(); refreshCatalog()
        scope.launch {store.observeAreas(route).catch {emit(emptyList())}.collect {mutableAreas.value=if(closed)emptyList() else it}}
        scope.launch {store.observeLines(route).catch {emit(emptyList())}.collect {mutableLines.value=if(closed)emptyList() else it}}
        scope.launch {
            store.observePoints(route).catch { failure ->
                updatePoints { it.copy(points = emptyList(), available = false,
                    modeElementUuid = null, selectedPointUuid = null, drag = null, undo = null, redo = null, error = pointFailureMessage(failure)) }
            }.collect { rows ->
                val currentRows = rows.mapNotNull { row ->
                    val latest = committedPoints.value[row.uuid]?.takeIf { it.updatedAt > row.updatedAt } ?: row
                    latest.takeIf { it.deletedAt == null }
                }
                updatePoints { if (closed) PointPlacementState() else it.copy(points = currentRows, available = true,
                    drag = it.drag?.takeIf { drag -> rows.any { p -> p.uuid == drag.point.uuid && p.updatedAt == drag.point.updatedAt } },
                    selectedPointUuid = it.selectedPointUuid?.takeIf { uuid -> rows.any { p -> p.uuid == uuid } }) }
                validatePointHistory()
            }
        }
    }

    fun beginPlacement(element: MeasurementElement, contourUpdatedAt: Long) {
        if (closed || editor.value != null || points.value.positionEditor != null || points.value.saving || !points.value.available ||
            state.value !is MeasurementElementsState.Loaded || element.measurementUuid != route.measurementUuid ||
            element.pointCapacity() == null) return
        updatePoints { it.copy(modeElementUuid = element.uuid, contourUpdatedAt = contourUpdatedAt,
            selectedElementUuid = element.uuid, selectedPointUuid = null, error = null, retry = null, confirmingDelete = false) }
    }
    /** Returns false while an accepted transaction is still finishing. */
    fun finishPlacement(): Boolean {
        if (points.value.saving || points.value.positionEditor != null) return false
        updatePoints { it.copy(modeElementUuid = null, contourUpdatedAt = null, selectedElementUuid = null,
            selectedPointUuid = null, confirmingDelete = false, retry = null, error = null, drag = null) }
        return true
    }
    fun selectPoint(uuid: String) {
        if (closed || editor.value != null || points.value.positionEditor != null || points.value.saving || points.value.retry != null) return
        val point = points.value.points.find { it.uuid == uuid } ?: return
        updatePoints { it.copy(selectedPointUuid = uuid, selectedElementUuid = point.elementUuid, error = null) }
    }
    fun tapPoint(xMm: Long, yMm: Long) {
        val scene = points.value
        if (!scene.available || scene.saving || scene.positionEditor != null || scene.retry != null || editor.value != null) return
        val element = (state.value as? MeasurementElementsState.Loaded)?.items?.find { it.uuid == scene.modeElementUuid } ?: return
        performPoint(PointCommand.Add(element.version, scene.contourUpdatedAt ?: return, xMm, yMm))
    }
    fun requestPointDelete() {
        if (points.value.selectedPointUuid != null && !points.value.saving && points.value.retry == null)
            updatePoints { it.copy(confirmingDelete = true) }
    }
    fun cancelPointDelete() { if (!points.value.saving) updatePoints { it.copy(confirmingDelete = false) } }
    fun deletePoint() {
        val point = points.value.points.find { it.uuid == points.value.selectedPointUuid } ?: return
        val element = (state.value as? MeasurementElementsState.Loaded)?.items?.find { it.uuid == point.elementUuid } ?: return
        if (points.value.confirmingDelete) performPoint(PointCommand.Remove(element.version, point))
    }
    fun retryPoint() { points.value.retry?.let(::performPoint) }
    private fun performPoint(command: PointCommand) {
        if (closed || points.value.saving || editor.value != null || state.value !is MeasurementElementsState.Loaded) return
        val completion = store.beginWrite() ?: return
        updatePoints { it.copy(saving = true, error = null, confirmingDelete = false) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    val result = store.savePoint(route, command)
                    if (!closed) pointCommitted(command, result)
                } catch (failure: Exception) {
                    if (!closed) updatePoints { it.copy(saving = false, drag = null,
                        undo = if (failure is PointPlacementFailure.StaleVersion || failure is PointPlacementFailure.StaleContour || failure is PointPlacementFailure.RetryConflict || failure is PointPlacementFailure.ParentUnavailable) null else it.undo,
                        redo = if (failure is PointPlacementFailure.StaleVersion || failure is PointPlacementFailure.StaleContour || failure is PointPlacementFailure.RetryConflict || failure is PointPlacementFailure.ParentUnavailable) null else it.redo,
                        positionEditor = it.positionEditor?.copy(error = pointFailureMessage(failure),
                            conflict = failure is PointPlacementFailure.StaleVersion || failure is PointPlacementFailure.StaleContour || failure is PointPlacementFailure.RetryConflict),
                        error = pointFailureMessage(failure) + if (command is PointCommand.Move && command.historyAction != PointHistoryAction.NORMAL) " Історію положень скинуто; перезавантажте точку." else "",
                        retry = command.takeIf { failure is PointPlacementFailure.DatabaseUnavailable ||
                            failure is MeasurementElementFailure.DatabaseUnavailable }) }
                } finally { store.endWrite(completion) }
            }
        }
    }

    private var pointExitAction: (() -> Unit)? = null
    private fun pointElement(uuid: String) = (state.value as? MeasurementElementsState.Loaded)?.items?.find { it.uuid == uuid }
    fun beginPointDrag(uuid: String, contourVersion: Long) {
        val scene = points.value
        if (closed || scene.saving || !scene.available || scene.retry != null || scene.positionEditor != null || editor.value != null || scene.selectedPointUuid != uuid) return
        val point = scene.points.find { it.uuid == uuid } ?: return
        val element = pointElement(point.elementUuid) ?: return
        updatePoints { it.copy(drag = PointDragPreview(point, element.version, contourVersion), error = null) }
    }
    fun previewPointDrag(xMm: Double, yMm: Double) {
        if (points.value.saving) return
        updatePoints { it.copy(drag = it.drag?.copy(xMm = xMm, yMm = yMm)) }
    }
    fun cancelPointDrag() { updatePoints { it.copy(drag = null) } }
    fun finishPointDrag() {
        val drag = points.value.drag ?: return
        cancelPointDrag()
        val bound = com.andreyceilings.shared.calculation.CeilingPolygonCalculator.MAX_COORDINATE_MM.toDouble()
        if (!drag.xMm.isFinite() || !drag.yMm.isFinite() || drag.xMm !in -bound..bound || drag.yMm !in -bound..bound) {
            updatePoints { it.copy(error = pointFailureMessage(PointPlacementFailure.OutsideContour())) }; return
        }
        val x = kotlin.math.round(drag.xMm).toLong(); val y = kotlin.math.round(drag.yMm).toLong()
        if (x == drag.point.xMm && y == drag.point.yMm && drag.point.precision == PointPrecision.APPROXIMATE) return
        performPoint(PointCommand.Move(drag.element, drag.point, drag.contourUpdatedAt, x, y, PointPrecision.APPROXIMATE))
    }
    fun openPosition(geometry: com.andreyceilings.shared.estimate.FrozenPolygonGeometry, contourVersion: Long) {
        val scene = points.value
        if (closed || scene.saving || scene.retry != null || scene.positionEditor != null || editor.value != null) return
        val point = scene.points.find { it.uuid == scene.selectedPointUuid } ?: return
        val element = pointElement(point.elementUuid) ?: return
        updatePoints { it.copy(drag = null, positionEditor = PointPositionEditorState(
            PointPositionDraft(point, element.version, contourVersion, geometry)), error = null) }
    }
    fun updatePosition(change: (PointPositionDraft) -> PointPositionDraft) {
        if (points.value.saving || points.value.retry != null || points.value.positionEditor?.conflict == true) return
        updatePoints { it.copy(positionEditor = it.positionEditor?.let { editor -> editor.copy(draft = change(editor.draft), error = null) }) }
    }
    fun savePosition() {
        val editor = points.value.positionEditor ?: return
        if (editor.conflict || points.value.retry != null) return
        val target = editor.draft.position() ?: return
        performPoint(PointCommand.Move(editor.draft.element, editor.draft.point, editor.draft.contourUpdatedAt,
            target.xMm, target.yMm, PointPrecision.EXACT))
    }
    fun requestPositionDismiss() = requestPositionExit { updatePoints { it.copy(positionEditor = null) } }
    fun requestPositionExit(action: () -> Unit) {
        if (points.value.saving) return
        val current = points.value.positionEditor
        if (current == null) { action(); return }
        if (!current.dirty && points.value.retry == null) { updatePoints { it.copy(positionEditor = null) }; action() }
        else { pointExitAction = action; updatePoints { it.copy(positionEditor = current.copy(exitRequested = true)) } }
    }
    fun continuePosition() { pointExitAction = null; updatePoints { it.copy(positionEditor = it.positionEditor?.copy(exitRequested = false, reloadRequested = false)) } }
    fun discardPosition() {
        if (points.value.saving) return
        val action = pointExitAction; pointExitAction = null
        updatePoints { it.copy(positionEditor = null, retry = null) }; action?.invoke()
    }
    fun requestPositionReload() { if (!points.value.saving) updatePoints { it.copy(positionEditor = it.positionEditor?.copy(reloadRequested = true)) } }
    fun reloadPosition() {
        val before = points.value.positionEditor ?: return
        if (points.value.saving) return
        updatePoints { it.copy(saving = true) }
        scope.launch {
            try {
                val draft = withContext(Dispatchers.Default) { store.reloadPosition(route, before.draft.point.uuid) }
                updatePoints { it.copy(saving = false, positionEditor = PointPositionEditorState(draft), retry = null, undo = null, redo = null) }
            } catch (failure: Exception) {
                updatePoints { it.copy(saving = false, positionEditor = before.copy(error = pointFailureMessage(failure), reloadRequested = false)) }
            }
        }
    }
    fun undoPointMove() {
        val history = points.value.undo ?: return
        if (points.value.positionEditor != null) return
        performPoint(PointCommand.Move(history.element, history.after, history.contourUpdatedAt,
            history.before.xMm, history.before.yMm, history.before.precision,
            historyAction = PointHistoryAction.UNDO, history = history))
    }
    fun redoPointMove() {
        val history = points.value.redo ?: return
        if (points.value.positionEditor != null) return
        performPoint(PointCommand.Move(history.element, history.before, history.contourUpdatedAt,
            history.after.xMm, history.after.yMm, history.after.precision,
            historyAction = PointHistoryAction.REDO, history = history))
    }
    fun leavePointRoute(): Boolean {
        if (!finishPlacement()) return false
        updatePoints { it.copy(undo = null, redo = null, drag = null, positionEditor = null) }
        pointExitAction = null
        committedPoints.value = emptyMap(); mutableLines.value=emptyList()
        return true
    }
    private fun pointCommitted(command: PointCommand, result: PointMoveResult) {
        committedPoints.update { if (closed) emptyMap() else it + (result.point.uuid to result.point) }
        mutableState.update { current -> if (closed) MeasurementElementsState.Unavailable
            else if (current is MeasurementElementsState.Loaded) current.copy(items = current.items.map {
                if (it.uuid == result.element.uuid) result.element else it }) else current }
        updatePoints { current ->
            val moved = command as? PointCommand.Move
            val history = moved?.let { PointMoveHistory(it.history?.before ?: it.point, result.point, result.element.version, it.contourUpdatedAt) }
            current.copy(saving = false, retry = null, positionEditor = null, drag = null,
                points = (current.points.filterNot { it.uuid == result.point.uuid } + listOfNotNull(result.point.takeIf { it.deletedAt == null }))
                    .sortedWith(compareBy<PointPlacement> { it.createdAt }.thenBy { it.uuid }),
                undo = if (moved?.historyAction != PointHistoryAction.UNDO) history else null,
                redo = if (moved?.historyAction == PointHistoryAction.UNDO) PointMoveHistory(result.point,
                    checkNotNull(moved.history).after, result.element.version, moved.contourUpdatedAt) else null)
        }
    }

    private fun validatePointHistory() {
        val scene = points.value
        if (scene.saving) return
        val history = scene.undo ?: scene.redo ?: return
        val expected = if (scene.undo != null) history.after else history.before
        val live = scene.points.find { it.uuid == expected.uuid }
        val element = pointElement(expected.elementUuid)
        if (live == null || element == null || live.updatedAt > expected.updatedAt || element.updatedAt > history.element.expectedUpdatedAt)
            updatePoints { it.copy(undo = null, redo = null, error = "Положення або елемент змінилися; історію переміщень скинуто. Оберіть актуальну позначку.") }
    }

    fun retry() {
        if (closed) return
        dataJob?.cancel(); mutableState.update { if (closed) MeasurementElementsState.Unavailable else MeasurementElementsState.Loading }
        dataJob = scope.launch { store.observe(route).collect { value ->
            mutableState.update { previous -> if (closed) MeasurementElementsState.Unavailable
                else if (value is MeasurementElementsState.Loaded && previous is MeasurementElementsState.Loaded)
                    value.copy(items = value.items.map { incoming -> previous.items.find { it.uuid == incoming.uuid && it.updatedAt > incoming.updatedAt } ?: incoming })
                else value }
            val ids = (value as? MeasurementElementsState.Loaded)?.items
                ?.filter { it.pointCapacity() != null }?.map { it.uuid }.orEmpty()
            updatePoints { current -> current.copy(
                modeElementUuid = current.modeElementUuid?.takeIf { it in ids },
                selectedElementUuid = current.selectedElementUuid?.takeIf { it in ids },
                drag = current.drag?.takeIf { it.element.uuid in ids },
                selectedPointUuid = current.selectedPointUuid.takeIf { current.selectedElementUuid in ids }) }
            validatePointHistory()
        } }
    }
    fun refreshCatalog() {
        if (closed) return
        catalogJob?.cancel(); mutableCatalog.update { if (closed) ElementCatalogState.Unavailable else ElementCatalogState.Loading }
        catalogJob = scope.launch { store.catalog().collect { value ->
            mutableCatalog.update { if (closed) ElementCatalogState.Unavailable else value }
        } }
    }
    fun add() { if (state.value is MeasurementElementsState.Loaded && editor.value == null && finishPlacement()) currentEditor = ElementEditorState(ElementEditorDraft()) }
    fun open(element: MeasurementElement, action: ElementEditorAction = ElementEditorAction.EDIT) {
        if (state.value !is MeasurementElementsState.Loaded || editor.value != null ||
            element.measurementUuid != route.measurementUuid || !finishPlacement()) return
        currentEditor = ElementEditorState(ElementEditorDraft.edit(element, action))
    }
    fun update(change: (ElementEditorDraft) -> ElementEditorDraft) {
        val before = editor.value ?: return
        if (before.saving || before.attemptedCreate || before.conflict) return
        currentEditor = before.copy(draft = change(before.draft), error = null)
    }
    fun choose(action: ElementEditorAction) = update { it.copy(action = action) }
    fun select(item: ElementCatalogItem) = update { before ->
        // No automatic quantity/unit conversion. Derived creation is an explicit item selection;
        // linking still requires a separate Replace confirmation even for Unknown -> derived.
        before.copy(selection = item)
    }
    fun requestDismiss() = requestExit { currentEditor = null }
    fun requestExit(action: () -> Unit) {
        val current = editor.value
        if (current == null) { action(); return }
        if (current.saving) return
        if (!current.dirty && !current.attemptedCreate) { currentEditor = null; action() }
        else { exitAction = action; currentEditor = current.copy(exitRequested = true, reloadRequested = false) }
    }
    fun continueEditing() { exitAction = null; currentEditor = editor.value?.copy(exitRequested = false) }
    fun discardAndExit() {
        if (editor.value?.saving == true) return
        val action = exitAction; exitAction = null; currentEditor = null; action?.invoke()
    }
    fun requestReload() { if (editor.value?.saving == false) currentEditor = editor.value?.copy(reloadRequested = true) }
    fun cancelReload() { currentEditor = editor.value?.copy(reloadRequested = false) }
    fun reload() {
        val before = editor.value ?: return
        if (before.saving) return
        currentEditor = before.copy(saving = true, reloadRequested = false)
        scope.launch {
            try {
                val actual = withContext(Dispatchers.Default) { store.reload(route, before.draft.uuid) }
                if (!closed) currentEditor = ElementEditorState(ElementEditorDraft.edit(actual,
                    before.draft.action.takeIf { it == ElementEditorAction.LINK && actual.source == ElementSource.Wish || it == ElementEditorAction.DELETE }
                        ?: ElementEditorAction.EDIT))
            } catch (failure: Exception) {
                if (!closed) currentEditor = before.copy(error = elementFailureMessage(failure), reloadRequested = false)
            }
        }
    }
    fun save() {
        val before = editor.value ?: return
        if (closed || before.saving || before.conflict || before.draft.validationError != null ||
            state.value !is MeasurementElementsState.Loaded) return
        val creates = before.draft.action == ElementEditorAction.CREATE_WISH || before.draft.action == ElementEditorAction.CREATE_CATALOG
        val completion = store.beginWrite() ?: return
        currentEditor = before.copy(saving = true, error = null, exitRequested = false)
        exitAction = null
        // UNDIPATCHED enters the protected region before Save returns. The actual
        // transaction and completion signal stay on Default, so a synchronous
        // desktop window-close never needs the blocked UI dispatcher to finish.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.Default) {
                try {
                    store.save(route, before.draft)
                    if (!closed) scope.launch {
                        if (!closed) {
                            if (mutablePricing.subscriptionCount.value > 0) refreshPricing()
                            currentEditor = null
                        }
                    }
                } catch (failure: Exception) {
                    if (!closed) currentEditor = before.copy(saving = false, error = elementFailureMessage(failure),
                        conflict = failure is MeasurementElementFailure.StaleVersion || failure is MeasurementElementFailure.RetryConflict,
                        attemptedCreate = before.attemptedCreate || creates &&
                            (failure is MeasurementElementFailure.DatabaseUnavailable || failure !is MeasurementElementFailure))
                } finally {
                    store.endWrite(completion)
                }
            }
        }
    }
    fun close() {
        closed = true; exitAction = null; pointExitAction = null; currentEditor = null
        committedPoints.value = emptyMap(); mutableLines.value=emptyList()
        mutableState.value = MeasurementElementsState.Unavailable; mutableCatalog.value = ElementCatalogState.Unavailable
        mutablePricing.value = MeasurementPricingState.Unavailable(com.andreyceilings.shared.livepricing.PricingProblemCode.DATABASE_UNAVAILABLE)
        mutablePoints.value = PointPlacementState()
        scope.cancel()
    }
}
