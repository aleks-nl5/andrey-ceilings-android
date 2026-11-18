package com.andreyceilings.shared.estimate

import androidx.compose.ui.graphics.ImageBitmap
import com.andreyceilings.shared.livepricing.BusinessRole
import com.andreyceilings.shared.livepricing.ExclusionReason
import com.andreyceilings.shared.livepricing.QuoteAmounts
import com.andreyceilings.shared.measurement.AreaShape
import com.andreyceilings.shared.measurement.ElementApproval
import com.andreyceilings.shared.measurement.MeasurementDrawingContour
import com.andreyceilings.shared.measurement.MeasurementGeometryCodec
import com.andreyceilings.shared.measurement.MeasurementGeometryView
import com.andreyceilings.shared.measurement.PointPositionMm
import com.andreyceilings.shared.measurement.measurementDrawingContour
import com.andreyceilings.shared.measurement.pricingProblemText
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class EstimateDocumentSection(val label: String, val editable: Boolean = false) {
    ROOM("Параметри приміщення"),
    DRAWING("Збережене креслення"),
    NOTES("Примітки", true),
    PAYMENT("Умови оплати", true),
    DEADLINES("Строки", true),
    WARRANTY("Гарантійні умови", true),
    ISSUER("Виконавець"),
    PHOTOS("Фото об’єкта")
}

internal data class DocumentPresentationOptions(
    val order: List<EstimateDocumentSection>,
    val hidden: Set<EstimateDocumentSection> = emptySet(),
    val text: Map<EstimateDocumentSection, String> = emptyMap()
)

internal data class DocumentIssuer(val name: String, val contacts: List<String>, val logo: ImageBitmap?)
internal data class DocumentPhoto(val image: ImageBitmap, val caption: String)
internal fun interface DocumentIssuerProvider { fun currentIssuer(): DocumentIssuer? }
internal fun interface DocumentPhotoProvider { fun currentPhotos(): List<DocumentPhoto> }

internal class FrozenEstimateDocument private constructor(
    val publicNumber: String,
    val version: Long,
    val createdOn: String,
    val title: String,
    val project: String,
    val currentAddress: String?,
    val room: String,
    val measuredArea: String,
    val perimeter: String,
    val rows: List<Row>,
    val amounts: QuoteAmounts,
    val omissions: List<String>,
    val drawing: Drawing
) {
    data class Row(
        val title: String,
        val unit: String,
        val quantityMilli: Long,
        val unitPriceCents: Long,
        val totalCents: Long,
        val roundedAreaMilli: Long?
    )

    data class Drawing(
        val contour: MeasurementDrawingContour,
        val points: List<PointPositionMm>,
        val lines: List<Pair<PointPositionMm, PointPositionMm>>,
        val areas: List<AreaShape>
    )

    val fileName: String get() = "${publicNumber.replace(unsafeFileCharacters, "_").trim('.', '_').ifEmpty { "koshtorys" }}-v$version.pdf"

    companion object {
        private val unsafeFileCharacters = Regex("[^\\p{L}\\p{N}._-]")

        /** Receives an exact repository projection after the stored proof and receipts were checked. */
        internal fun from(saved: SavedIndividualQuote, currentAddress: String?, zone: TimeZone): FrozenEstimateDocument {
            val frozen = saved.frozen
            val source = frozen.source
            val geometry = MeasurementGeometryCodec.read(source.drawingJson)
            val contour = checkNotNull(measurementDrawingContour(MeasurementGeometryView.Captured(geometry)))
            val elements = source.elements.associateBy { "element:${it.uuid}" }
            return FrozenEstimateDocument(
                publicNumber = frozen.publicNumber,
                version = saved.versionNumber,
                createdOn = Instant.fromEpochMilliseconds(saved.createdAt).toLocalDateTime(zone).date.toString(),
                title = frozen.title,
                project = source.projectTitle,
                currentAddress = currentAddress?.takeIf { it.isNotBlank() },
                room = source.measurementName,
                measuredArea = geometry.areaSquareMeters.toString().replace('.', ','),
                perimeter = geometry.perimeterMeters.toString().replace('.', ','),
                rows = frozen.rows.map { (_, row) ->
                    Row(row.title, row.unit, row.effectiveBillableQuantityMilli, row.unitPriceCents, row.totalCents,
                        row.sourceQuantityMilli.takeIf { row.quantityRoundingPolicy != null })
                },
                amounts = frozen.amounts,
                omissions = frozen.omissions.map { omitted ->
                    val element = checkNotNull(elements[omitted.sourceKey])
                    require(element.approval == ElementApproval.APPROVED &&
                        element.businessRole !in listOf(BusinessRole.CANVAS, BusinessRole.PRIMARY_PROFILE))
                    val reason = when (omitted.reason) {
                        ExclusionReason.USER_EXCLUDED -> "Виключено під час збереження"
                        ExclusionReason.UNRESOLVED -> {
                            require(omitted.problems.isNotEmpty())
                            omitted.problems.joinToString(transform = ::pricingProblemText)
                        }
                    }
                    "${element.label} · $reason"
                },
                drawing = Drawing(
                    contour,
                    source.points.filter { it.deletedAt == null && !it.mounting.unresolved }
                        .map { PointPositionMm(it.xMm, it.yMm) },
                    source.lines.filter { it.deletedAt == null && !it.unresolved }.map { it.start to it.end },
                    source.areas.filter { it.deletedAt == null && !it.unresolved }.map { it.shape }
                )
            )
        }
    }
}
