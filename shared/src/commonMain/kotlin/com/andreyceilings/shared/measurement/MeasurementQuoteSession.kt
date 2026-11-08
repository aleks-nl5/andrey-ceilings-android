package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.livepricing.TransientQuotePresenter
import com.andreyceilings.shared.navigation.AppNavigationState

/** Owned by the host's living editor session (Android ViewModel / desktop window), never SavedState. */
class MeasurementQuoteSession {
    private var route: Pair<Long, Long>? = null
    private var store: MeasurementElementsStore? = null
    private var presenter: TransientQuotePresenter? = null

    fun navigate(next: AppNavigationState) {
        val target = next.measurementDetails?.let { it.projectId to it.measurementId }
            ?: next.measurementEdit?.let { it.projectId to it.measurementId }
        if (target != route) clear()
        route = target
        // Details and Edit must never reuse a ready price when reopening Quote.
        if (!next.measurementQuote) presenter?.beginRead()
    }

    fun acquire(projectId: Long, measurementId: Long, measurementUuid: String,
        owner: MeasurementElementsStore): TransientQuotePresenter {
        val target = projectId to measurementId
        if (route != target || store !== owner || presenter?.measurementUuid != measurementUuid) {
            clear(); route = target; store = owner; presenter = owner.newQuote(measurementUuid)
        }
        return checkNotNull(presenter)
    }

    fun clear() { presenter?.let { store?.releaseQuote(it) }; presenter = null; store = null; route = null }
}
