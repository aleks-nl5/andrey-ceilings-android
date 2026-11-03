package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.navigation.AppNavigationState

/**
 * Host exit policy reads the actual editor state synchronously, without copying
 * a saving flag through effects or into ID-only navigation snapshots.
 * Hosts bind their current editor and pass every navigation change through transition().
 */
class MeasurementCreateNavigationGuard {
    private var editor: MeasurementCreateModel? = null

    fun bind(model: MeasurementCreateModel) { editor = model }

    fun unbind(model: MeasurementCreateModel) {
        if (editor === model) editor = null
    }

    fun blocksExit(current: AppNavigationState): Boolean {
        val state = editor?.state?.value ?: return false
        return current.measurementCreate?.projectId == state.draft.projectId &&
            (state.saving || state.savedId != null)
    }

    fun transition(current: AppNavigationState, proposed: AppNavigationState): AppNavigationState {
        if (!blocksExit(current)) return proposed
        val state = requireNotNull(editor).state.value
        // Keep the editor visible between commit and its onSaved effect too.
        // Only delivery of this exact successful result may replace it.
        val completed = state.savedId?.let { current.measurementCreated(state.draft.projectId, it) }
        return if (completed != null && proposed == completed) proposed else current
    }
}
