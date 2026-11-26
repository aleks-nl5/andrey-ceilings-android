package com.andreyceilings.shared.objects

import com.andreyceilings.shared.measurement.DrawingPoint
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import com.andreyceilings.shared.measurement.MeasurementGeometryView
import com.andreyceilings.shared.measurement.measurementDrawingContour
import com.andreyceilings.shared.measurement.measurementGeometryForViewing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class CeilingContourSource(
    val shape: String,
    val drawingJson: String,
    val lengthMeters: Double,
    val widthMeters: Double
) {
    fun identity(): CeilingContourSource = if (shape == "rectangle" && drawingJson.filterNot { it.isWhitespace() } == "{}") copy(drawingJson = "{}")
        else copy(lengthMeters = 0.0, widthMeters = 0.0)

    fun project(): MeasurementDrawingContour? =
        when (val view = measurementGeometryForViewing(shape, drawingJson, lengthMeters, widthMeters)) {
            is MeasurementGeometryView.Captured -> MeasurementDrawingContour(
                view.geometry.vertices.map { DrawingPoint(it.xMm.toDouble(), it.yMm.toDouble()) },
                emptyList(), historical = false, measuredSides = false
            )
            is MeasurementGeometryView.HistoricalRectangle -> measurementDrawingContour(view)?.copy(sideLabels = emptyList())
            MeasurementGeometryView.Unavailable -> null
        }
}

/** One instance per unlocked database. Identity compares saved content exactly. */
internal class CeilingContourCache(
    private val capacity: Int = 40,
    private val prepare: (CeilingContourSource) -> MeasurementDrawingContour? = { it.project() }
) {
    private data class Entry(
        val source: CeilingContourSource,
        val contour: MeasurementDrawingContour?
    )

    // null closes admission and clears entries in one update, including a racing preparation.
    private val entries = MutableStateFlow<List<Pair<String, Entry>>?>(emptyList())

    fun close() { entries.value = null }

    fun snapshot(): Map<String, MeasurementDrawingContour?> =
        entries.value.orEmpty().associate { it.first to it.second.contour }

    /** The store serializes preparation. Closing may occur on another thread. */
    suspend fun projection(ceilingUuid: String, source: CeilingContourSource): MeasurementDrawingContour? {
        currentCoroutineContext().ensureActive()
        val current = entries.value ?: return null
        if (source.drawingJson.length > 262_144) return null
        val identity = source.identity()
        val entry = current.firstOrNull { it.first == ceilingUuid }?.second
            ?.takeIf { it.source == identity } ?: Entry(identity, prepare(source))
        currentCoroutineContext().ensureActive()
        entries.update { active ->
            active?.let { (it.filterNot { cached -> cached.first == ceilingUuid } + (ceilingUuid to entry))
                .takeLast(capacity) }
        }
        return entry.contour.takeIf { entries.value != null }
    }
}
