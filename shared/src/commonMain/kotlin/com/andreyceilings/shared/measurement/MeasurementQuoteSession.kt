package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.livepricing.TransientQuotePresenter
import com.andreyceilings.shared.navigation.AppNavigationState

/** Owned by the host's living editor session (Android ViewModel / desktop window), never SavedState. */
class MeasurementQuoteSession {
    private var route: Pair<Long, Long>? = null
    private var store: MeasurementElementsStore? = null
    private var presenter: TransientQuotePresenter? = null
    private var phase: String? = null
    val saving: Boolean get() = presenter?.saveState?.value?.saving == true

    fun navigate(next: AppNavigationState) {
        val target = next.measurementDetails?.let { it.projectId to it.measurementId }
            ?: next.measurementEdit?.let { it.projectId to it.measurementId }
        if (target != route) clear()
        route = target
        // Invalidate synchronously before the new surface's first frame, including restored routes.
        val nextPhase = if (next.measurementQuote) "quote" else if (next.measurementEdit != null) "edit" else "details"
        if (nextPhase != phase) presenter?.beginRead()
        phase = nextPhase
    }

    fun acquire(projectId: Long, measurementId: Long, measurementUuid: String,
        owner: MeasurementElementsStore): TransientQuotePresenter {
        val target = projectId to measurementId
        if (route != target || store !== owner || presenter?.measurementUuid != measurementUuid) {
            clear(); route = target; store = owner; presenter = owner.newQuote(measurementUuid)
        }
        return checkNotNull(presenter)
    }

    fun clear() { presenter?.let { store?.releaseQuote(it) }; presenter = null; store = null; route = null; phase = null }
}
