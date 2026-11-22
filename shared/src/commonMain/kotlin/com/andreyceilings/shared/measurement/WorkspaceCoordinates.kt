package com.andreyceilings.shared.measurement

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceCoordinatesKind { POINT, LINE, AREA }

@Serializable
data class WorkspaceCoordinatesInput(
    val kind: WorkspaceCoordinatesKind,
    val uuid: String,
    val baseline: List<String>,
    val values: List<String> = baseline,
    val hasDiameter: Boolean = false,
    val invalidGeometry: Boolean = false
) {
    val dirty: Boolean get() = values != baseline
    fun millimetres(): List<Long>? {
        val parsed = values.map { parsePointMetres(it, 2_000_000) ?: return null }
        if (hasDiameter && (parsed.last() !in 2..2_000_000 || parsed.last() % 2L != 0L)) return null
        return parsed
    }
}
