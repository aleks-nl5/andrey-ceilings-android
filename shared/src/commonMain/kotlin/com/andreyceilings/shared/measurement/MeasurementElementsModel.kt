package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.newRecordUuid
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
    val selection: ElementCatalogItem? = null, val replaceQuantity: Boolean = false
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
    private val mutableState = MutableStateFlow<MeasurementElementsState>(MeasurementElementsState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableCatalog = MutableStateFlow<ElementCatalogState>(ElementCatalogState.Loading)
    val catalog = mutableCatalog.asStateFlow()
    private val mutableEditor = MutableStateFlow<ElementEditorState?>(null)
    val editor = mutableEditor.asStateFlow()
    private val mutablePoints = MutableStateFlow(PointPlacementState())
    val points = mutablePoints.asStateFlow()
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
        retry(); refreshCatalog()
        scope.launch {
            store.observePoints(route).catch { failure ->
                updatePoints { it.copy(points = emptyList(), available = false,
                    modeElementUuid = null, selectedPointUuid = null, error = pointFailureMessage(failure)) }
            }.collect { rows ->
                updatePoints { if (closed) PointPlacementState() else it.copy(points = rows, available = true,
                    selectedPointUuid = it.selectedPointUuid?.takeIf { uuid -> rows.any { p -> p.uuid == uuid } }) }
            }
        }
    }

    fun beginPlacement(element: MeasurementElement, contourUpdatedAt: Long) {
        if (closed || editor.value != null || points.value.saving || !points.value.available ||
            state.value !is MeasurementElementsState.Loaded || element.measurementUuid != route.measurementUuid ||
            element.pointCapacity() == null) return
        updatePoints { it.copy(modeElementUuid = element.uuid, contourUpdatedAt = contourUpdatedAt,
            selectedElementUuid = element.uuid, selectedPointUuid = null, error = null, retry = null, confirmingDelete = false) }
    }
    /** Returns false while an accepted transaction is still finishing. */
    fun finishPlacement(): Boolean {
        if (points.value.saving) return false
        updatePoints { it.copy(modeElementUuid = null, contourUpdatedAt = null, selectedElementUuid = null,
            selectedPointUuid = null, confirmingDelete = false, retry = null, error = null) }
        return true
    }
    fun selectPoint(uuid: String) {
        if (closed || editor.value != null || points.value.saving || points.value.retry != null) return
        val point = points.value.points.find { it.uuid == uuid } ?: return
        updatePoints { it.copy(selectedPointUuid = uuid, selectedElementUuid = point.elementUuid, error = null) }
    }
    fun tapPoint(xMm: Long, yMm: Long) {
        val scene = points.value
        if (!scene.available || scene.saving || scene.retry != null || editor.value != null) return
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
                    store.savePoint(route, command)
                    if (!closed) updatePoints { it.copy(saving = false, retry = null) }
                } catch (failure: Exception) {
                    if (!closed) updatePoints { it.copy(saving = false, error = pointFailureMessage(failure),
                        retry = command.takeIf { failure is PointPlacementFailure.DatabaseUnavailable ||
                            failure is MeasurementElementFailure.DatabaseUnavailable }) }
                } finally { store.endWrite(completion) }
            }
        }
    }

    fun retry() {
        if (closed) return
        dataJob?.cancel(); mutableState.update { if (closed) MeasurementElementsState.Unavailable else MeasurementElementsState.Loading }
        dataJob = scope.launch { store.observe(route).collect { value ->
            mutableState.update { if (closed) MeasurementElementsState.Unavailable else value }
            val ids = (value as? MeasurementElementsState.Loaded)?.items?.map { it.uuid }.orEmpty()
            updatePoints { current -> current.copy(
                modeElementUuid = current.modeElementUuid?.takeIf { it in ids },
                selectedElementUuid = current.selectedElementUuid?.takeIf { it in ids },
                selectedPointUuid = current.selectedPointUuid.takeIf { current.selectedElementUuid in ids }) }
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
                    if (!closed) currentEditor = null
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
        closed = true; exitAction = null; currentEditor = null
        mutableState.value = MeasurementElementsState.Unavailable; mutableCatalog.value = ElementCatalogState.Unavailable
        mutablePoints.value = PointPlacementState()
        scope.cancel()
    }
}
