package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.navigation.AppNavigationState

/**
 * Host exit policy reads the actual editor state synchronously, without copying
 * a saving flag through effects or into ID-only navigation snapshots.
 * Hosts bind their current editor and pass every navigation change through transition().
 */
class MeasurementCreateNavigationGuard {
    val fullscreen = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var editor: MeasurementCreateModel? = null
    private var elements: MeasurementElementsModel? = null
    fun bindElements(model: MeasurementElementsModel) { elements = model }
    fun unbindElements(model: MeasurementElementsModel) {
        // Retain the session model through Activity disposal/startup gaps while a draft/write exists.
        if (elements === model && model.editor.value == null && !model.points.value.saving &&
            model.points.value.modeElementUuid == null && model.points.value.selectedPointUuid == null &&
            model.points.value.positionEditor == null) elements = null
    }

    private var edit: MeasurementEditModel? = null
    private var surfaceBack: (() -> Boolean)? = null
    fun bindEdit(model: MeasurementEditModel) { edit = model }
    fun unbindEdit(model: MeasurementEditModel) {
        if (edit === model && !model.state.value.saving && !model.state.value.checking && !model.state.value.saved) edit = null
    }
    fun bindSurface(back: () -> Boolean) { surfaceBack = back }
    fun unbindSurface(back: () -> Boolean) { if (surfaceBack === back) surfaceBack = null }
    fun requestClose(close: () -> Unit) {
        val elementModel = elements
        if (elementModel?.points?.value?.positionEditor != null) { elementModel.requestPositionExit(close); return }
        if (elementModel != null && !elementModel.finishPlacement()) return
        if (elementModel?.editor?.value != null) { elementModel.requestExit(close); return }
        val model = edit
        if (model != null) model.requestExit(close)
        else if (editor?.state?.value?.let { it.saving || it.savedId != null } != true) close()
    }

    fun bind(model: MeasurementCreateModel) { editor = model }

    fun unbind(model: MeasurementCreateModel) {
        if (editor === model && !model.state.value.saving && model.state.value.savedId == null) editor = null
    }

    fun blocksExit(current: AppNavigationState): Boolean {
        if (elements?.editor?.value?.saving == true || elements?.points?.value?.saving == true) return true
        val editState = edit?.state?.value
        if (editState != null && current.measurementEdit?.measurementId == editState.original.measurementId &&
            current.measurementEdit.projectId == editState.draft.projectId) return editState.saving || editState.checking || editState.saved
        val state = editor?.state?.value ?: return false
        return current.measurementCreate?.projectId == state.draft.projectId &&
            (state.saving || state.savedId != null)
    }

    /**
     * A successful edit is an authorized result, NOT a Back transition. If hosts
     * pass the resulting route through transition() a second time, fullscreen
     * consumes it as Back and strands the saved editor in a disabled state.
     */
    fun deliverEditResult(current: AppNavigationState, projectId: Long, measurementId: Long,
        applyApproved: (AppNavigationState) -> Unit): Boolean {
        val state = edit?.state?.value ?: return false
        if (!state.saved || state.original.measurementId != measurementId || state.draft.projectId != projectId ||
            current.measurementEdit?.measurementId != measurementId || current.measurementEdit.projectId != projectId) return false
        applyApproved(approved(current.measurementSaved(projectId, measurementId)))
        return true
    }

    private fun approved(proposed: AppNavigationState): AppNavigationState {
        elements?.leavePointRoute()
        if (proposed.measurementEdit == null) edit = null
        if (proposed.measurementCreate == null) editor = null
        if (proposed.measurementDetails == null && proposed.measurementEdit == null && proposed.measurementCreate == null) fullscreen.value = false
        return proposed
    }

    fun transition(current: AppNavigationState, proposed: AppNavigationState,
        applyApproved: (AppNavigationState) -> Unit = {}): AppNavigationState {
        if (proposed == current) return current
        val elementModel = elements
        if (elementModel?.points?.value?.saving == true) return current
        if (elementModel?.points?.value?.positionEditor != null) {
            if (proposed == current.back()) elementModel.requestPositionDismiss()
            else elementModel.requestPositionExit { applyApproved(approved(proposed)) }
            return current
        }
        if (proposed == current.back() && elementModel?.points?.value?.let {
            it.modeElementUuid != null || it.selectedPointUuid != null
        } == true) { elementModel.finishPlacement(); return current }
        if (elementModel?.editor?.value != null) {
            // Back dismisses the modal first; a tab/route change resumes only after explicit discard.
            if (proposed == current.back()) elementModel.requestDismiss()
            else elementModel.requestExit { applyApproved(approved(proposed)) }
            return current
        }
        val editModel = edit
        editModel?.cancelPointDrag()
        val editState = editModel?.state?.value
        if (editState != null && current.measurementEdit?.measurementId == editState.original.measurementId &&
            current.measurementEdit.projectId == editState.draft.projectId) {
            if (editState.saving || editState.checking || editState.saved) return current
        }
        if (!blocksExit(current)) {
            if (proposed == current.back() && surfaceBack?.invoke() == true) return current
            if (editState?.dirty == true) {
                editModel.requestExit { applyApproved(approved(proposed)) }
                return current
            }
            return approved(proposed)
        }
        val state = requireNotNull(editor).state.value
        // Keep the editor visible between commit and its onSaved effect too.
        // Only delivery of this exact successful result may replace it.
        val completed = state.savedId?.let { current.measurementCreated(state.draft.projectId, it) }
        return if (completed != null && proposed == completed) approved(proposed) else current
    }
}
