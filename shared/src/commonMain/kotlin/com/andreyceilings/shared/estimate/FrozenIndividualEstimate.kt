package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class EstimateWorkflowStatus(val label: String) {
    DRAFT("Чернетка"), SENT("Надіслано"), APPROVED("Погоджено"), REJECTED("Відхилено")
}

/** Explicit user command. All fields are revalidated against one physical writer transaction. */
@Serializable
data class IndividualEstimateSaveRequest(
    val operationUuid: String, val projectId: Long, val title: String,
    val sessionId: String, val selection: IndividualEstimateSelection,
    val overrides: List<EstimateQuantityOverride>, val discountText: String,
    val expectedAmounts: QuoteAmounts, val expectedFingerprint: String,
    val acknowledgedFingerprint: String?, val expectedOmissions: List<ExcludedIndividualWork>
)

@Serializable
data class FrozenIndividualSource(
    val projectUuid: String, val projectTitle: String, val measurementUuid: String,
    val measurementName: String, val drawingJson: String,
    val metadata: Map<String, String>, val versions: Map<String, String>,
    val elements: List<MeasurementElement>, val points: List<PointPlacement>,
    val lines: List<LinePlacement>, val areas: List<AreaFeature>,
    val walls: List<Map<String, String>>, val receipts: List<Map<String, String>>
)

@Serializable
data class FrozenIndividualLine(val uuid: String, val line: LiveEstimateLine)

/** Exact authoritative financial record; REAL columns in legacy history are compatibility projections. */
@Serializable
data class FrozenIndividualEstimate(
    val formatVersion: Int = 1, val title: String, val source: FrozenIndividualSource,
    val sourceSignature: String, val catalogSignature: String, val scopeSignature: String,
    val compositionUuid: String, val sessionId: String, val discountText: String,
    val previewSignature: String, val fingerprint: String, val acknowledgedFingerprint: String?,
    val partial: Boolean, val omissions: List<ExcludedIndividualWork>,
    val rows: List<FrozenIndividualLine>, val amounts: QuoteAmounts,
    val numberPolicy: EstimateNumberPolicy, val publicNumber: String,
    val currency: String = "UAH", val rounding: String = "HALF_UP_PER_ROW_THEN_DISCOUNT"
) {
    fun validate() {
        require(formatVersion == 1 && validEstimateTitle(title) == title)
        require(currency == "UAH" && rounding == "HALF_UP_PER_ROW_THEN_DISCOUNT")
        require(source.projectUuid.isNotBlank() && source.measurementUuid.isNotBlank())
        val geometry = MeasurementGeometryCodec.read(source.drawingJson)
        require(geometry.vertices.isNotEmpty())
        val reconstructed = LiveEstimateSource(source.projectUuid, source.measurementUuid, geometry,
            source.elements, source.points, source.lines, source.areas,
            source.walls.filter { it["active"] == "1" }.map { CeilingWall(checkNotNull(it["uuid"]), checkNotNull(it["ordinal"]).toInt()) }, source.versions)
        require(sourceSignature == reconstructed.signature && catalogSignature.isNotEmpty() && scopeSignature.isNotEmpty())
        require(source.versions["measurementName"] == source.measurementName)
        require(source.versions["measurementMetadata"] == contentToken(listOf(source.measurementName, source.metadata["notes"].orEmpty(), source.drawingJson)))
        require(rows.isNotEmpty() && rows.map { it.uuid }.toSet().size == rows.size)
        require(rows.all { isEstimateUuid(it.uuid) })
        require(rows.map { it.line.sourceKey }.toSet().size == rows.size)
        require(rows.map { it.line.elementUuid }.toSet().size == rows.size)
        rows.forEach { (_, line) ->
            require(line.measurementUuid == source.measurementUuid && line.elementUuid != null)
            require(line.sourceQuantityMilli > 0 && line.businessRole != null)
            require(line.title.isNotBlank() && line.stableCode.isNotBlank() && line.unit.isNotBlank())
            require(line.totalCents == PricingMath.lineCents(line.unitPriceCents, line.effectiveBillableQuantityMilli))
            val element = source.elements.single { it.uuid == line.elementUuid }
            require(line.businessRole == element.businessRole)
            require((line.bucket == PricingBucket.CONFIRMED) == (element.approval == ElementApproval.APPROVED))
        }
        require(rows.count { it.line.businessRole == BusinessRole.CANVAS && it.line.bucket == PricingBucket.CONFIRMED } == 1)
        require(rows.count { it.line.businessRole == BusinessRole.PRIMARY_PROFILE && it.line.bucket == PricingBucket.CONFIRMED } == 1)
        val subtotal = rows.fold(0L) { sum, row -> PricingMath.add(sum, row.line.totalCents) }
        require(amounts == QuoteDiscount.calculate(subtotal, amounts.discountBasisPoints))
        require(QuoteDiscount.parseBasisPoints(discountText) == amounts.discountBasisPoints)
        require(omissions.all { it.bucket == PricingBucket.CONFIRMED })
        require(omissions.map { it.sourceKey }.toSet().size == omissions.size)
        require(omissions.none { omitted -> rows.any { it.line.sourceKey == omitted.sourceKey } })
        require(partial == omissions.isNotEmpty())
        require(!partial || acknowledgedFingerprint == fingerprint)
        require(fingerprint == quoteSaveFingerprint(sessionId, previewSignature, discountText, amounts))
        require(EstimateNumbering.validatePublic(numberPolicy).isEmpty() && publicNumber.isNotBlank())
        require(!Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE).containsMatchIn(publicNumber))
    }
}

internal val frozenEstimateJson = Json { encodeDefaults = true }
@Serializable
private data class FrozenEstimateEnvelope(val version: Int = 1, val document: FrozenIndividualEstimate, val contentProof: String)

/** Exact content binding, deliberately not a cryptographic authentication claim. SQLCipher protects the DB.
 * Length-prefixed canonical content avoids accepting valid-looking field corruption or parser fallback. */
internal fun FrozenIndividualEstimate.encode(): String {
    validate()
    val canonical = frozenEstimateJson.encodeToString(this)
    return frozenEstimateJson.encodeToString(FrozenEstimateEnvelope(document = this, contentProof = contentToken(listOf(canonical))))
}
fun decodeFrozenIndividualEstimate(payload: String): FrozenIndividualEstimate {
    val envelope = frozenEstimateJson.decodeFromString<FrozenEstimateEnvelope>(payload)
    require(envelope.version == 1)
    require(envelope.contentProof == contentToken(listOf(frozenEstimateJson.encodeToString(envelope.document))))
    return envelope.document.also { it.validate() }
}

fun validEstimateTitle(value: String): String? = value.trim().takeIf {
    it.isNotEmpty() && it.length <= 160 && it.none { c -> c.isISOControl() }
}
internal fun isEstimateUuid(value: String): Boolean = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(value)
internal fun quoteSaveFingerprint(session: String, preview: String, discount: String, amounts: QuoteAmounts) =
    contentToken(listOf(session, preview, discount, amounts.discountBasisPoints.toString(),
        amounts.subtotalCents.toString(), amounts.discountCents.toString(), amounts.finalCents.toString()))

/** UI projection loaded exclusively from immutable storage, including after source deletion. */
data class SavedIndividualQuote(val seriesUuid: String, val createdAt: Long, val frozen: FrozenIndividualEstimate,
    val status: EstimateWorkflowStatus, val statusVersion: Long)

/** Shared normalized-column audit. It never consults a mutable measurement, project or catalog. */
fun verifyFrozenStoredIdentity(frozen: FrozenIndividualEstimate, projectId: Long, measurementUuid: String?, publicNumber: String?,
    subtotalCents: Long, discountBasisPoints: Long, discountCents: Long, totalCents: Long) {
    require(frozen.source.versions["projectId"] == projectId.toString())
    require(frozen.source.measurementUuid == measurementUuid && frozen.publicNumber == publicNumber)
    require(frozen.amounts.subtotalCents == subtotalCents && frozen.amounts.discountBasisPoints.toLong() == discountBasisPoints &&
        frozen.amounts.discountCents == discountCents && frozen.amounts.finalCents == totalCents)
}

fun verifyFrozenSaveReceipt(operationUuid: String, command: String, frozen: FrozenIndividualEstimate) {
    val request = frozenEstimateJson.decodeFromString<IndividualEstimateSaveRequest>(command)
    require(isEstimateUuid(operationUuid) && request.operationUuid == operationUuid)
    require(request.title == frozen.title && request.sessionId == frozen.sessionId && request.discountText == frozen.discountText)
    require(request.expectedAmounts == frozen.amounts && request.expectedFingerprint == frozen.fingerprint)
    require(request.acknowledgedFingerprint == frozen.acknowledgedFingerprint && request.expectedOmissions == frozen.omissions)
    require(request.projectId.toString() == frozen.source.versions["projectId"])
    require(request.selection.projectUuid == frozen.source.projectUuid && request.selection.measurementUuid == frozen.source.measurementUuid)
    require(request.selection.basisSourceSignature == frozen.sourceSignature && request.selection.basisCatalogSignature == frozen.catalogSignature)
    require(request.selection.scopeSignature == frozen.scopeSignature && request.selection.compositionUuid == frozen.compositionUuid)
    require(request.selection.includedSourceKeys == frozen.rows.map { it.line.sourceKey }.toSet())
}
