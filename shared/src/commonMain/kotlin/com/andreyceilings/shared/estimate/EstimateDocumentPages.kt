package com.andreyceilings.shared.estimate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.andreyceilings.shared.measurement.AreaShape
import com.andreyceilings.shared.measurement.DrawingPoint
import com.andreyceilings.shared.measurement.PointPositionMm
import com.andreyceilings.shared.measurement.formatElementMilli
import com.andreyceilings.shared.measurement.measurementDrawingProjection
import com.andreyceilings.shared.measurement.measurementMoney
import kotlin.math.roundToInt

/** Resolved A4 pages. The platform draws the same vector content as the preview. */
class EstimateDocumentPages internal constructor(
    val fileName: String,
    private val pages: List<List<DocumentMark>>
) {
    val count: Int get() = pages.size

    fun drawPage(scope: DrawScope, index: Int) = with(scope) {
        scale(size.width / WIDTH, size.height / HEIGHT, pivot = Offset.Zero) {
            drawRect(Color.White, size = Size(WIDTH.toFloat(), HEIGHT.toFloat()))
            pages[index].forEach { it.draw(this) }
        }
    }

    internal fun pageText(index: Int): String =
        pages[index].filterIsInstance<DocumentMark.Text>().joinToString("\n") { it.layout.layoutInput.text.text }

    companion object {
        const val WIDTH = 595
        const val HEIGHT = 842
    }
}

internal sealed interface DocumentMark {
    fun draw(scope: DrawScope)

    data class Text(val layout: TextLayoutResult, val x: Float, val y: Float) : DocumentMark {
        override fun draw(scope: DrawScope) = with(scope) { drawText(layout, topLeft = Offset(x, y)) }
    }

    data class Rule(val y: Float) : DocumentMark {
        override fun draw(scope: DrawScope) = with(scope) {
            drawLine(RuleInk, Offset(40f, y), Offset(555f, y), strokeWidth = 0.65f)
        }
    }

    data class Image(val bitmap: ImageBitmap, val x: Int, val y: Int, val width: Int, val height: Int) : DocumentMark {
        override fun draw(scope: DrawScope) = with(scope) {
            drawImage(bitmap, dstOffset = IntOffset(x, y), dstSize = IntSize(width, height))
        }
    }

    data class Drawing(val drawing: FrozenEstimateDocument.Drawing, val y: Float) : DocumentMark {
        override fun draw(scope: DrawScope) = with(scope) {
            val height = 195f
            val projection = checkNotNull(measurementDrawingProjection(drawing.contour, 515f, height))
            fun screen(p: DrawingPoint): Offset = projection.project(p).let { Offset(40f + it.x.toFloat(), y + it.y.toFloat()) }
            fun screen(p: PointPositionMm) = screen(DrawingPoint(p.xMm.toDouble(), p.yMm.toDouble()))
            fun path(points: List<Offset>) = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            clipRect(40f, y, 555f, y + height) {
                val contour = path(drawing.contour.points.map { screen(it) })
                drawRect(PaperTint, Offset(40f, y), Size(515f, height))
                drawPath(contour, Ink, style = Stroke(1.8f))
                drawing.areas.forEach { area ->
                    when (area) {
                        is AreaShape.Polygon -> drawPath(path(area.vertices.map { screen(it) }), Accent, style = Stroke(1.3f))
                        is AreaShape.Circle -> drawCircle(Accent, (area.radiusMm * projection.scale).toFloat(),
                            screen(area.center), style = Stroke(1.3f))
                    }
                }
                drawing.lines.forEach { (start, end) -> drawLine(Accent, screen(start), screen(end), strokeWidth = 1.5f) }
                drawing.points.forEach { drawCircle(Accent, 2.8f, screen(it)) }
            }
        }
    }
}

private val Ink = Color(0xFF172D36)
private val Accent = Color(0xFF1A5964)
private val RuleInk = Color(0xFF8CA2A7)
private val PaperTint = Color(0xFFF1F6F6)

internal fun layoutEstimateDocument(
    document: FrozenEstimateDocument,
    options: DocumentPresentationOptions,
    measurer: TextMeasurer,
    issuerProvider: DocumentIssuerProvider? = null,
    photoProvider: DocumentPhotoProvider? = null
): EstimateDocumentPages = DocumentPagination(document, measurer).apply {
    paragraph("Кошторис робіт", 25, bold = true)
    paragraph(document.title, 15, bold = true)
    paragraph("Об’єкт · ${document.project}")
    document.currentAddress?.let { paragraph("Актуальна адреса · $it") }
    paragraph("Приміщення · ${document.room}")
    paragraph("Дата версії · ${document.createdOn}")
    paragraph(if (document.omissions.isEmpty()) "Повний кошторис" else "Частковий кошторис", bold = true)
    gap(12)

    table(document.rows)

    val amounts = document.amounts
    val discount = amounts.discountBasisPoints
    val discountText = (discount / 100).toString() +
        (discount % 100).takeIf { it != 0 }?.let { "," + it.toString().padStart(2, '0').trimEnd('0') }.orEmpty()
    keep(75)
    paragraph("До знижки · ${measurementMoney(amounts.subtotalCents)}")
    paragraph("Знижка $discountText % · ${measurementMoney(amounts.discountCents)}")
    paragraph("Разом до оплати · ${measurementMoney(amounts.finalCents)}", 16, bold = true)

    if (document.omissions.isNotEmpty()) {
        heading("Невраховані погоджені роботи")
        document.omissions.forEach { paragraph(it) }
    }
    options.order.filterNot { it in options.hidden }.forEach { section ->
        when (section) {
            EstimateDocumentSection.ROOM -> {
                heading(section.label)
                paragraph("Площа заміру · ${document.measuredArea} м²")
                paragraph("Периметр · ${document.perimeter} м")
            }
            EstimateDocumentSection.DRAWING -> {
                keep(250)
                heading(section.label)
                drawing(document.drawing)
                paragraph(document.drawing.contour.namedSideLabels.joinToString("  ·  "), 10)
            }
            EstimateDocumentSection.ISSUER -> issuerProvider?.currentIssuer()?.let {
                heading(section.label)
                paragraph(it.name)
                it.contacts.filter(String::isNotBlank).forEach { contact -> paragraph(contact) }
                it.logo?.let { logo -> image(logo, 110) }
            }
            EstimateDocumentSection.PHOTOS -> {
                val photos = photoProvider?.currentPhotos().orEmpty()
                if (photos.isNotEmpty()) {
                    heading(section.label)
                    photos.forEach {
                        image(it.image, 190)
                        if (it.caption.isNotBlank()) paragraph(it.caption)
                    }
                }
            }
            else -> options.text[section]?.trim()?.takeIf(String::isNotEmpty)?.let {
                heading(section.label)
                paragraph(it)
            }
        }
    }
}.finish()

/** Fixed units are PDF points, independent of screen density and font scaling. */
private class DocumentPagination(private val document: FrozenEstimateDocument, private val measurer: TextMeasurer) {
    private val pages = mutableListOf<MutableList<DocumentMark>>()
    private var marks = mutableListOf<DocumentMark>()
    private var y = 0f
    private val bottom = 795f

    init { newPage() }

    private fun newPage() {
        marks = mutableListOf()
        pages.add(marks)
        y = 35f
        val header = lines("${document.publicNumber} · v${document.version}", 515, 12, bold = true)
        header.forEach { place(it, 40f); y += it.size.height + 2 }
        rule()
        gap(12)
    }

    fun keep(height: Int) {
        if (y + height > bottom) newPage()
    }

    fun gap(points: Int) { y += points }
    private fun rule() { marks.add(DocumentMark.Rule(y)) }
    private fun place(text: TextLayoutResult, x: Float) { marks.add(DocumentMark.Text(text, x, y)) }

    private fun measure(text: String, font: Int, bold: Boolean, width: Int? = null): TextLayoutResult {
        val style = TextStyle(color = Ink, fontSize = font.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        return measurer.measure(text, style = style, softWrap = width != null,
            constraints = if (width == null) Constraints() else Constraints(maxWidth = width),
            density = Density(1f), layoutDirection = LayoutDirection.Ltr)
    }

    private fun lines(text: String, width: Int, font: Int, bold: Boolean = false): List<TextLayoutResult> {
        val layout = measure(text, font, bold, width)
        return List(layout.lineCount) { line ->
            measure(text.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true)), font, bold)
        }
    }

    private fun number(text: String, width: Int): List<TextLayoutResult> {
        for (font in 10 downTo 6) {
            val layout = measure(text, font, false)
            if (layout.size.width <= width) return listOf(layout)
        }
        return lines(text, width, 6)
    }

    fun paragraph(text: String, font: Int = 11, bold: Boolean = false) {
        lines(text, 515, font, bold).forEach {
            val step = it.size.height + 3
            keep(step)
            place(it, 40f)
            gap(step)
        }
        gap(3)
    }

    fun heading(text: String) {
        keep(54)
        gap(12)
        paragraph(text, 14, bold = true)
    }

    fun drawing(drawing: FrozenEstimateDocument.Drawing) {
        keep(205)
        marks.add(DocumentMark.Drawing(drawing, y))
        gap(205)
    }

    fun image(bitmap: ImageBitmap, maxHeight: Int) {
        val ratio = minOf(515f / bitmap.width, maxHeight.toFloat() / bitmap.height)
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        keep(height + 8)
        marks.add(DocumentMark.Image(bitmap, 40, y.roundToInt(), width, height))
        gap(height + 8)
    }

    fun table(rows: List<FrozenEstimateDocument.Row>) {
        val widths = listOf(172, 63, 73, 103, 104)
        val offsets = widths.runningFold(40, Int::plus)
        val header = listOf("Позиція", "Од.", "Кількість", "Ціна, грн", "Сума, грн")
        val headerCells = header.mapIndexed { index, text -> lines(text, widths[index] - 8, 10, bold = true) }
        fun band(cells: List<List<TextLayoutResult>>, line: Int) {
            var height = 0
            cells.forEachIndexed { index, cell -> cell.getOrNull(line)?.let {
                val x = if (index < 2) offsets[index] else offsets[index + 1] - (it.size.width + 7)
                place(it, x.toFloat())
                height = maxOf(height, it.size.height)
            } }
            gap(height + 3)
        }
        fun tableHeader() {
            band(headerCells, 0)
            rule()
            gap(4)
        }
        keep(46)
        tableHeader()
        rows.forEach { row ->
            val name = buildString {
                append(row.title)
                row.roundedAreaMilli?.let {
                    append("\nПлоща заміру · ${document.measuredArea} м²")
                    append("\nДо 0,001 м² · ${formatElementMilli(it)} м²")
                }
            }
            val values = listOf(name, row.unit, formatElementMilli(row.quantityMilli),
                measurementMoney(row.unitPriceCents).removeSuffix(" грн"),
                measurementMoney(row.totalCents).removeSuffix(" грн"))
            val cells = values.mapIndexed { index, text ->
                val width = widths[index] - 8
                if (index < 2) lines(text, width, 10) else number(text, width)
            }
            val lineCount = cells.maxOf { it.size }
            val steps = (0 until lineCount).map { line -> cells.maxOf { it.getOrNull(line)?.size?.height ?: 0 } + 3 }
            // Keep ordinary rows whole. Oversized titles continue under the repeated header.
            val height = steps.sum() + 7
            if (y + minOf(height, 60) > bottom || height < 650 && y + height > bottom) {
                newPage()
                tableHeader()
            }
            steps.forEachIndexed { line, step ->
                if (y + step + 7 > bottom) {
                    newPage()
                    tableHeader()
                }
                band(cells, line)
            }
            gap(2)
            rule()
            gap(5)
        }
        gap(8)
    }

    fun finish(): EstimateDocumentPages {
        pages.forEachIndexed { index, page ->
            page.add(DocumentMark.Rule(804f))
            val footer = lines("Сторінка ${index + 1} / ${pages.size}", 130, 10).single()
            page.add(DocumentMark.Text(footer, 555f - footer.size.width, 809f))
        }
        return EstimateDocumentPages(document.fileName, pages)
    }
}
