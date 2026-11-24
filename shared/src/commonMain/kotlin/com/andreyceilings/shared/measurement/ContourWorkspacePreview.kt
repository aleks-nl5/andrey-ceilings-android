package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.calculation.CeilingShapeTemplates
import com.andreyceilings.shared.calculation.MeasuredOrthogonalSide
import com.andreyceilings.shared.calculation.OrthogonalSideContour

internal fun MeasurementOutlineDraft.hasEnteredDimensions(): Boolean = when (this) {
    is MeasurementOutlineDraft.Captured -> true
    is MeasurementOutlineDraft.Template -> fields.values.any { it.isNotBlank() }
    is MeasurementOutlineDraft.Orthogonal -> sides.any { it.length.isNotBlank() }
}

internal fun contourWorkspacePreview(draft: MeasurementCreateDraft): MeasurementDrawingContour {
    fun length(text: String): Long? = runCatching { measurementLengthMm(text, draft.precision) }.getOrNull()
    val vertices = when (val outline = draft.outline) {
        is MeasurementOutlineDraft.Captured -> outline.geometry.vertices
        is MeasurementOutlineDraft.Template -> {
            val template = requireNotNull(CeilingShapeTemplates.template(outline.shape))
            val entered = outline.fields.mapValues { (_, text) -> length(text) }
            val unit = entered.values.filterNotNull().maxOrNull() ?: 1000L
            val dimensions = entered.mapValues { (_, mm) -> mm ?: unit }.toMutableMap()
            if (outline.shape == "l_shaped") {
                for ((inside, outside) in listOf("C" to "A", "D" to "B")) {
                    val inner = entered[inside]
                    val outer = maxOf(2L, entered[outside] ?: maxOf(unit, (inner ?: 0L) * 2))
                    dimensions[outside] = outer
                    dimensions[inside] = inner?.takeIf { it < outer } ?: outer / 2
                }
            }
            template.build(dimensions).vertices
        }
        is MeasurementOutlineDraft.Orthogonal -> {
            val entered = outline.sides.map { length(it.length) }
            val unit = entered.filterNotNull().maxOrNull() ?: 1000L
            OrthogonalSideContour.trace(outline.sides.mapIndexed { i, side ->
                MeasuredOrthogonalSide(entered[i] ?: unit, side.turn)
            }).vertices
        }
    }
    return MeasurementDrawingContour(
        vertices.map { DrawingPoint(it.xMm.toDouble(), it.yMm.toDouble()) },
        emptyList(), historical = false, measuredSides = false
    )
}

internal fun MeasurementCreateDraft.problemWalls(issues: List<MeasurementContourIssue>): Set<Int> {
    val errors = issues.filter { it.code != MeasurementContourIssueCode.MISSING }
    val fields = errors.flatMap { it.fields }.toSet()
    val mapped = when (val input = outline) {
        is MeasurementOutlineDraft.Captured -> emptyList()
        is MeasurementOutlineDraft.Template -> {
            val count = if (shape == "rectangle") 4 else 6
            (0 until count).filter { wall -> measurementWallParameters(shape, wall).any { it in fields } }
        }
        is MeasurementOutlineDraft.Orthogonal -> input.sides.indices.filter { input.sides[it].inputId in fields }
    }
    return mapped.toSet() + errors.flatMap { it.walls }
}
