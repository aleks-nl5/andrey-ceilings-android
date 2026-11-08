package com.andreyceilings.shared.measurement

import com.andreyceilings.shared.input.DecimalTextPolicy

import com.andreyceilings.shared.calculation.*
import com.andreyceilings.shared.db.newRecordUuid
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import kotlinx.serialization.json.*

/** Input methods are variants, so 90° turns are not a constraint on all future outlines. */
sealed interface MeasurementOutlineDraft {
    /** A verified old polygon without measured source sides is preserved, never reverse-engineered. */
    data class Captured(val geometry: FrozenPolygonGeometry) : MeasurementOutlineDraft {
        init { require(geometry.shapeType=="polygon" && geometry.sides.isEmpty());FrozenPolygonGeometryContract.requireValid(geometry) }
    }
    data class Template(val shape: String, val fields: Map<String, String>) : MeasurementOutlineDraft
    data class Orthogonal(val sides: List<MeasurementSideInput>) : MeasurementOutlineDraft
}

data class MeasurementSideInput(
    val length: String = "", val turn: OrthogonalTurn = OrthogonalTurn.RIGHT,
    val inputId: String = newRecordUuid()
)

data class MeasurementCreateDraft(
    val projectId: Long,
    val precision: MeasurementPrecision,
    val name: String = "",
    val notes: String = "",
    val outline: MeasurementOutlineDraft = emptyTemplate("rectangle"),
    /** Identity of this creation, retained across form restore and explicit Save retries. */
    val recordUuid: String = newRecordUuid()
) {
    init { require(projectId > 0) }
    val shape: String get() = when (outline) {
        is MeasurementOutlineDraft.Template -> outline.shape
        is MeasurementOutlineDraft.Orthogonal -> "polygon"
        is MeasurementOutlineDraft.Captured -> outline.geometry.shapeType
    }

    /** Local form state only. No pending Save, DB content or draft in navigation snapshots. */
    fun snapshot(): String = buildJsonObject {
        put("version", 1); put("project", projectId); put("precision", precision.name); put("uuid", recordUuid)
        put("name", name); put("notes", notes); put("shape", shape)
        when (val input = outline) {
            is MeasurementOutlineDraft.Captured -> put("capturedGeometry",MeasurementGeometryCodec.write(input.geometry))
            is MeasurementOutlineDraft.Template -> putJsonObject("fields") {
                input.fields.forEach { (key, value) -> put(key, value) }
            }
            is MeasurementOutlineDraft.Orthogonal -> putJsonArray("sides") {
                input.sides.forEach { side -> add(buildJsonObject {
                    put("id", side.inputId); put("length", side.length); put("turn", side.turn.name)
                }) }
            }
        }
    }.toString()

    companion object {
        fun emptyTemplate(shape: String): MeasurementOutlineDraft.Template {
            val template = requireNotNull(CeilingShapeTemplates.template(shape))
            return MeasurementOutlineDraft.Template(shape, template.parameterKeys.associateWith { "" })
        }

        fun restore(snapshot: String, projectId: Long): MeasurementCreateDraft? = runCatching {
            require(snapshot.length <= 262_144)
            val root = Json.parseToJsonElement(snapshot).jsonObject
            require(root.getValue("version").jsonPrimitive.int == 1)
            require(root.getValue("project").jsonPrimitive.long == projectId)
            val shape = root.getValue("shape").jsonPrimitive.content
            val outline = if(root["capturedGeometry"]!=null) {
                val geometry=MeasurementGeometryCodec.read(root.getValue("capturedGeometry").jsonPrimitive.content)
                require(geometry.shapeType==shape)
                MeasurementOutlineDraft.Captured(geometry)
            } else if (shape == "polygon") {
                val sides = root.getValue("sides").jsonArray
                require(sides.size in 4..CeilingPolygonCalculator.MAX_VERTICES)
                MeasurementOutlineDraft.Orthogonal(sides.map { value ->
                    val side = value.jsonObject
                    MeasurementSideInput(side.getValue("length").jsonPrimitive.content,
                        OrthogonalTurn.valueOf(side.getValue("turn").jsonPrimitive.content),
                        side.getValue("id").jsonPrimitive.content)
                }.also { require(it.map { s -> s.inputId }.toSet().size == it.size) })
            } else {
                val fields = root.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content }
                require(fields.keys == emptyTemplate(shape).fields.keys)
                MeasurementOutlineDraft.Template(shape, fields)
            }
            MeasurementCreateDraft(projectId,
                MeasurementPrecision.valueOf(root.getValue("precision").jsonPrimitive.content),
                root.getValue("name").jsonPrimitive.content, root.getValue("notes").jsonPrimitive.content, outline,
                root.getValue("uuid").jsonPrimitive.content.also {
                    require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(it))
                })
        }.getOrNull()
    }
}

sealed interface MeasurementCreateIntent {
    data class Name(val value: String) : MeasurementCreateIntent
    data class Notes(val value: String) : MeasurementCreateIntent
    data class Shape(val value: String) : MeasurementCreateIntent
    data class Dimension(val key: String, val value: String) : MeasurementCreateIntent
    data class SideLength(val index: Int, val value: String) : MeasurementCreateIntent
    data class SideTurn(val index: Int, val value: OrthogonalTurn) : MeasurementCreateIntent
    data object ReplaceCapturedWithSides : MeasurementCreateIntent
    data object AddSide : MeasurementCreateIntent
    data class RemoveSide(val index: Int) : MeasurementCreateIntent
}

fun MeasurementCreateDraft.reduce(intent: MeasurementCreateIntent): MeasurementCreateDraft {
    fun editSide(index: Int, edit: (MeasurementSideInput) -> MeasurementSideInput): MeasurementCreateDraft {
        val input = outline as? MeasurementOutlineDraft.Orthogonal ?: return this
        if (index !in input.sides.indices) return this
        return copy(outline = input.copy(sides = input.sides.mapIndexed { i, side -> if (i == index) edit(side) else side }))
    }
    return when (intent) {
        is MeasurementCreateIntent.Name -> copy(name = intent.value)
        is MeasurementCreateIntent.Notes -> copy(notes = intent.value)
        is MeasurementCreateIntent.Shape -> if (intent.value == shape) this else when {
            intent.value == "polygon" -> copy(outline = MeasurementOutlineDraft.Orthogonal(List(4) { MeasurementSideInput() }))
            CeilingShapeTemplates.template(intent.value) != null -> copy(outline = MeasurementCreateDraft.emptyTemplate(intent.value))
            else -> this
        }
        is MeasurementCreateIntent.Dimension -> {
            val input = outline as? MeasurementOutlineDraft.Template ?: return this
            if (intent.key !in input.fields) this else copy(outline = input.copy(fields = input.fields + (intent.key to intent.value)))
        }
        is MeasurementCreateIntent.SideLength -> editSide(intent.index) { it.copy(length = intent.value) }
        is MeasurementCreateIntent.SideTurn -> editSide(intent.index) { it.copy(turn = intent.value) }
        MeasurementCreateIntent.ReplaceCapturedWithSides -> if(outline is MeasurementOutlineDraft.Captured)
            copy(outline=MeasurementOutlineDraft.Orthogonal(List(4){MeasurementSideInput()})) else this
        MeasurementCreateIntent.AddSide -> {
            val input = outline as? MeasurementOutlineDraft.Orthogonal ?: return this
            if (input.sides.size >= CeilingPolygonCalculator.MAX_VERTICES) this
            else copy(outline = input.copy(sides = input.sides + MeasurementSideInput()))
        }
        is MeasurementCreateIntent.RemoveSide -> {
            val input = outline as? MeasurementOutlineDraft.Orthogonal ?: return this
            if (input.sides.size <= 4 || intent.index !in input.sides.indices) this
            else copy(outline = input.copy(sides = input.sides.filterIndexed { i, _ -> i != intent.index }))
        }
    }
}

data class MeasurementCreateValidation(
    val nameError: String?, val fieldErrors: Map<String, String>,
    val geometry: FrozenPolygonGeometry?, val geometryError: String?
) {
    val canSave: Boolean get() = nameError == null && geometry != null && fieldErrors.isEmpty()
}

/** Decimal text -> integer mm, without an intermediate floating-point or silent rounding. */
fun measurementLengthMm(text: String, precision: MeasurementPrecision): Long {
    require(text.trim().isNotEmpty()) { "Введіть довжину." }
    // Preserve the existing exact-zero suffix rule (123.001000 == 123001 mm).
    // Only significant fractional digits consume precision; no non-zero digit is rounded.
    val value = DecimalTextPolicy(Int.MAX_VALUE).committed(text.trim())
    require(text.trim().length <= 32 && value != null) {
        "Введіть число в метрах, до 3 знаків після коми/крапки, наприклад 2,5."
    }
    val whole = value.substringBefore('.').ifEmpty { "0" }.toLongOrNull()
    val fraction = value.substringAfter('.', "").trimEnd('0')
    require(whole != null && whole <= CeilingPolygonCalculator.MAX_COORDINATE_MM / 1000 && fraction.length <= 3) {
        "Довжина має бути в межах 1000 м, до цілих міліметрів."
    }
    val mm = whole * 1000 + fraction.padEnd(3, '0').toLong()
    require(mm in 1..CeilingPolygonCalculator.MAX_COORDINATE_MM) { "Довжина має бути більшою за нуль і не перевищувати 1000 м." }
    val stepMm = (precision.stepMeters * 1000).toLong()
    require(mm % stepMm == 0L) { "Довжина має відповідати точності ${precision.label}." }
    return mm
}

fun validateMeasurementCreate(draft: MeasurementCreateDraft): MeasurementCreateValidation {
    val errors = linkedMapOf<String, String>()
    fun length(key: String, label: String, text: String): Long? = try {
        measurementLengthMm(text, draft.precision)
    } catch (failure: IllegalArgumentException) {
        errors[key] = "$label: ${failure.message}"; null
    }
    var geometryError: String? = null
    val geometry = try {
        when (val input = draft.outline) {
            is MeasurementOutlineDraft.Captured -> FrozenPolygonGeometryContract.requireValid(input.geometry).also {
                require(it.precisionStepMeters==draft.precision.stepMeters) { "Точність збереженого контуру не змінюється." }
            }
            is MeasurementOutlineDraft.Template -> {
                val template = requireNotNull(CeilingShapeTemplates.template(input.shape)) { "Невідома форма стелі." }
                require(input.fields.keys == template.parameterKeys.toSet()) { "Введіть усі параметри форми." }
                val mm = template.parameterKeys.mapNotNull { key ->
                    length(key, measurementTemplateFieldLabel(input.shape, key), input.fields.getValue(key))?.let { key to it }
                }.toMap()
                if (errors.isNotEmpty()) null else {
                    // Retain the existing VerifiedCeilingGeometry capture policies, using exact mm.
                    if (input.shape == "rectangle") require(mm.values.all { it >= 100 }) {
                        "Прямокутна стеля має мати сторони не менше 0,1 м."
                    }
                    val frozen = FrozenPolygonGeometryContract.capture(input.shape, template.build(mm).vertices, mm, draft.precision.stepMeters)
                    if (input.shape == "l_shaped") require(frozen.areaSquareMeters >= 1.0) {
                        "Площа L-подібної стелі має бути не меншою за 1 м²."
                    }
                    frozen
                }
            }
            is MeasurementOutlineDraft.Orthogonal -> {
                require(input.sides.size in 4..CeilingPolygonCalculator.MAX_VERTICES) { "Потрібно від 4 до 128 сторін." }
                val labels = measurementSideLabels(input.sides.size)
                val sides = input.sides.mapIndexedNotNull { i, side ->
                    length(side.inputId, "Сторона ${labels[i]}", side.length)?.let { MeasuredOrthogonalSide(it, side.turn) }
                }
                if (errors.isNotEmpty()) null else FrozenPolygonGeometryContract.captureSides(sides, draft.precision.stepMeters)
            }
        }
    } catch (failure: IllegalArgumentException) {
        geometryError = failure.message ?: "Некоректна геометрія."; null
    }
    return MeasurementCreateValidation(if (draft.name.isBlank()) "Введіть назву заміру." else null,
        errors, geometry, geometryError ?: if (errors.isNotEmpty()) "Заповніть і виправте розміри: креслення поки недоступне." else null)
}
