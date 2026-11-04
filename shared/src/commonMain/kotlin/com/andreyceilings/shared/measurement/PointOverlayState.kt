package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.estimate.FrozenPolygonGeometry

sealed interface PointOverlayState {
    data object Loading : PointOverlayState
    data object Missing : PointOverlayState
    data object Unavailable : PointOverlayState
    data class Loaded(val points: List<PointPlacement>, val savedGeometry: FrozenPolygonGeometry) : PointOverlayState
}
