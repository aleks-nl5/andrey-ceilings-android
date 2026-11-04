package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.db.newRecordUuid

sealed interface PointCommand {
    val element: ElementVersion
    data class Add(override val element: ElementVersion, val contourUpdatedAt: Long,
        val xMm: Long, val yMm: Long, val uuid: String = newRecordUuid()) : PointCommand
    data class Remove(override val element: ElementVersion, val point: PointPlacement) : PointCommand
}

data class PointPlacementState(
    val points: List<PointPlacement> = emptyList(), val available: Boolean = false,
    val modeElementUuid: String? = null, val contourUpdatedAt: Long? = null,
    val selectedPointUuid: String? = null, val selectedElementUuid: String? = null,
    val confirmingDelete: Boolean = false, val saving: Boolean = false,
    val error: String? = null, val retry: PointCommand? = null
)
