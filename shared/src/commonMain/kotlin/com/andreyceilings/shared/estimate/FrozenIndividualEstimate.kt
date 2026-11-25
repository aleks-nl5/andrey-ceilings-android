package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
data class HistoricalRevisionHead(val revisionUuid: String, val versionNumber: Long)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IndividualRevisionTarget(val seriesUuid: String, val expectedLatestRevisionUuid: String,
    val expectedVersion: Long, val expectedStatusVersion: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val historicalHead: HistoricalRevisionHead? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val openedRevisionUuid: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expectedSeriesDeletedAt: Long? = null
) {
    internal val parentUuid get() = historicalHead?.revisionUuid ?: expectedLatestRevisionUuid
    internal val parentVersion get() = historicalHead?.versionNumber ?: expectedVersion
}

@Serializable
data class AppendIndividualRevisionRequest(val target: IndividualRevisionTarget, val calculation: IndividualEstimateSaveRequest)

@Serializable
data class FrozenRevisionIdentity(val seriesUuid: String, val revisionUuid: String,
    val versionNumber: Long, val parentRevisionUuid: String)

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

internal class UnsupportedFrozenEstimateFormat : IllegalArgumentException()
internal class UnsupportedFrozenEstimateCurrency : IllegalArgumentException()
internal class InvalidFrozenEstimateDrawing(cause: IllegalArgumentException) : IllegalArgumentException(cause)

/** Exact authoritative financial record; REAL columns in legacy history are compatibility projections. */
@Serializable
data class FrozenIndividualEstimate(
    @kotlinx.serialization.Required val formatVersion: Int = 1, val title: String, val source: FrozenIndividualSource,
    val sourceSignature: String, val catalogSignature: String, val scopeSignature: String,
    val compositionUuid: String, val sessionId: String, val discountText: String,
    val previewSignature: String, val fingerprint: String, val acknowledgedFingerprint: String?,
    val partial: Boolean, val omissions: List<ExcludedIndividualWork>,
    val rows: List<FrozenIndividualLine>, val amounts: QuoteAmounts,
    val numberPolicy: EstimateNumberPolicy, val publicNumber: String,
    val currency: String = "UAH", val rounding: String = "HALF_UP_PER_ROW_THEN_DISCOUNT",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val revisionIdentity: FrozenRevisionIdentity? = null
) {
    fun validate() {
        if (formatVersion !in 1..4) throw UnsupportedFrozenEstimateFormat()
        require(validEstimateTitle(title) == title)
        if (formatVersion == 1 || formatVersion >= 3 && revisionIdentity == null) require(revisionIdentity == null)
        else {
            val identity = checkNotNull(revisionIdentity)
            require(isEstimateUuid(identity.seriesUuid) && isEstimateUuid(identity.revisionUuid) && isEstimateUuid(identity.parentRevisionUuid))
            require(identity.versionNumber > 1 && identity.revisionUuid != identity.parentRevisionUuid)
        }
        require((formatVersion == 4) == (amounts.discountMode == QuoteDiscountMode.FIXED))
        if (formatVersion < 4) require((formatVersion == 3) == rows.any { it.line.quantityRoundingPolicy != null })
        if (currency != "UAH") throw UnsupportedFrozenEstimateCurrency()
        require(rounding == "HALF_UP_PER_ROW_THEN_DISCOUNT")
        require(source.projectUuid.isNotBlank() && source.measurementUuid.isNotBlank())
        val geometry = try {
            MeasurementGeometryCodec.read(source.drawingJson)
        } catch (invalid: IllegalArgumentException) {
            throw InvalidFrozenEstimateDrawing(invalid)
        }
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
            if (element.quantity == ElementQuantity.CeilingArea) {
                val exactMilli = PricingMath.capturedMilli(geometry.areaSquareMeters)
                if (exactMilli != null) {
                    require(line.sourceQuantityMilli == exactMilli && line.quantityRoundingPolicy == null)
                } else {
                    require(formatVersion >= 3 && line.unit == "м²")
                    require(line.quantityRoundingPolicy == QuantityRoundingPolicy.CAPTURED_AREA_TO_MILLI_HALF_UP_V1)
                    require(line.sourceQuantityMilli == PricingMath.capturedAreaMilli(geometry.areaSquareMeters))
                    require(line.quantityOrigin in listOf(QuantityOrigin.CAPTURED_AREA, QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE))
                }
            } else require(line.quantityRoundingPolicy == null)
            require(line.businessRole == element.businessRole)
            require((element.source as? ElementSource.CatalogSelection)?.priceItemUuid == line.catalogItemUuid)
            require((line.bucket == PricingBucket.CONFIRMED) == (element.approval == ElementApproval.APPROVED))
        }
        require(rows.count { it.line.businessRole == BusinessRole.CANVAS && it.line.bucket == PricingBucket.CONFIRMED } == 1)
        require(rows.count { it.line.businessRole == BusinessRole.PRIMARY_PROFILE && it.line.bucket == PricingBucket.CONFIRMED } == 1)
        val subtotal = rows.fold(0L) { sum, row -> PricingMath.add(sum, row.line.totalCents) }
        val discount = requireNotNull(QuoteDiscount.parse(discountText, amounts.discountMode))
        require(amounts == QuoteDiscount.calculate(subtotal, discount))
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
private data class FrozenEstimateEnvelope(@kotlinx.serialization.Required val version: Int = 1, val document: FrozenIndividualEstimate, val contentProof: String)

/** Exact content binding, deliberately not a cryptographic authentication claim. SQLCipher protects the DB.
 * Length-prefixed canonical content avoids accepting valid-looking field corruption or parser fallback. */
internal fun FrozenIndividualEstimate.encode(): String {
    validate()
    val canonical = frozenEstimateJson.encodeToString(this)
    return frozenEstimateJson.encodeToString(FrozenEstimateEnvelope(document = this, contentProof = contentToken(listOf(canonical))))
}
fun decodeFrozenIndividualEstimate(payload: String): FrozenIndividualEstimate {
    val envelope = frozenEstimateJson.decodeFromString<FrozenEstimateEnvelope>(payload)
    if (envelope.version != 1) throw UnsupportedFrozenEstimateFormat()
    require(envelope.contentProof == contentToken(listOf(frozenEstimateJson.encodeToString(envelope.document))))
    return envelope.document.also { it.validate() }
}

fun validEstimateTitle(value: String): String? = value.trim().takeIf {
    it.isNotEmpty() && it.length <= 160 && it.none { c -> c.isISOControl() }
}
internal fun isEstimateUuid(value: String): Boolean = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(value)
internal fun quoteSaveFingerprint(session: String, preview: String, discount: String, amounts: QuoteAmounts) =
    contentToken(listOf(session, preview, discount, when (amounts.discountMode) {
        QuoteDiscountMode.PERCENT -> amounts.discountBasisPoints.toString()
        QuoteDiscountMode.FIXED -> "fixed-cents:${amounts.discountCents}"
    }, amounts.subtotalCents.toString(), amounts.discountCents.toString(), amounts.finalCents.toString()))

/** UI projection loaded exclusively from immutable storage, including after source deletion. */
data class SavedIndividualQuote(val seriesUuid: String, val createdAt: Long, val frozen: FrozenIndividualEstimate,
    val status: EstimateWorkflowStatus?, val statusVersion: Long,
    val revisionUuid: String = "", val versionNumber: Long = 1, val parentRevisionUuid: String? = null, val projectId: Long = 0,
    val selectedRevisionUuid: String? = null, val choiceVersion: Long = 1,
    val historicalHead: HistoricalRevisionHead? = null, val seriesDeletedAt: Long? = null)

/** Compare drawing and pricing inputs for the workspace warning, independently of provenance. */
internal fun FrozenIndividualEstimate.hasPricingDrift(current: PricingReadResult.Loaded): Boolean {
    val live = current.source
    if (hasMeasurementDrift(live)) return true
    val elements = source.elements.associateBy { it.uuid }
    val items = current.catalog.items.associateBy { it.uuid }
    val includedChanged = rows.any { (_, line) ->
        val item = items[line.catalogItemUuid] ?: return@any true
        val selection = elements[line.elementUuid]?.source as? ElementSource.CatalogSelection ?: return@any true
        item.title != line.title || item.unit != line.unit || item.code != line.stableCode ||
            item.quantityMode != selection.quantityMode || item.placementKind != selection.placementKind ||
            PricingMath.salePriceCents(item.salePrice) != line.unitPriceCents
    }
    if (includedChanged) return true

    // A partial approved quote may become outdated even when every included price is
    // unchanged. Re-evaluate only the previously unresolved omissions, not intentional
    // exclusions or unrelated catalog entries, using the canonical pricing rules.
    val unresolvedOmissions = omissions.filter { it.reason == ExclusionReason.UNRESOLVED }
    if (unresolvedOmissions.isEmpty()) return false
    val roles = BusinessRoleBindings(live.signature,
        live.elements.mapNotNull { e -> e.businessRole?.let { e.uuid to it } }.toMap())
    val preview = LivePricingEngine.buildPreview(live, current.catalog, roles)
    val currentUnresolved = preview.unresolvedItems.associateBy { it.sourceKey }
    return unresolvedOmissions.any { omission ->
        val unresolved = currentUnresolved[omission.sourceKey]
        unresolved == null || unresolved.reasons.toSet() != omission.problems.toSet()
    }
}

internal fun FrozenIndividualEstimate.hasMeasurementDrift(live: LiveEstimateSource): Boolean {
    if (live.projectUuid != source.projectUuid || live.measurementUuid != source.measurementUuid ||
        live.geometry != MeasurementGeometryCodec.read(source.drawingJson)
    ) return true

    val elements = source.elements.associateBy { it.uuid }
    if (live.elements.size != elements.size || live.elements.any { element ->
            val saved = elements[element.uuid]
            saved == null || saved.source != element.source || saved.unit != element.unit ||
                saved.approval != element.approval || saved.quantity != element.quantity ||
                saved.businessRole != element.businessRole ||
                (element.source == ElementSource.Wish && saved.label != element.label)
        }
    ) return true

    val points = source.points.associateBy { it.uuid }
    val lines = source.lines.associateBy { it.uuid }
    val areas = source.areas.associateBy { it.uuid }
    if (live.points.size != points.size || live.points.any { point ->
            val saved = points[point.uuid]
            saved == null || saved.elementUuid != point.elementUuid || saved.xMm != point.xMm ||
                saved.yMm != point.yMm || saved.mounting.unresolved != point.mounting.unresolved
        } || live.lines.size != lines.size || live.lines.any { line ->
            val saved = lines[line.uuid]
            saved == null || saved.elementUuid != line.elementUuid || saved.start != line.start ||
                saved.end != line.end || saved.widthMm != line.widthMm || saved.unresolved != line.unresolved
        } || live.areas.size != areas.size || live.areas.any { area ->
            val saved = areas[area.uuid]
            saved == null || saved.elementUuid != area.elementUuid || saved.shape != area.shape ||
                saved.role != area.role || saved.unresolved != area.unresolved
        }
    ) return true

    return false
}

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
    require(request.overrides.map { it.measurementElementUuid }.toSet().size == request.overrides.size)
    require(request.overrides.map { it.measurementElementUuid }.toSet() == frozen.rows.filter {
        it.line.quantityOrigin == QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE
    }.map { checkNotNull(it.line.elementUuid) }.toSet())
    request.overrides.forEach { override ->
        val line = frozen.rows.single { it.line.elementUuid == override.measurementElementUuid }.line
        require(override.explicitlyConfirmed && override.quantityMilli == line.effectiveBillableQuantityMilli && override.unit == line.unit)
        require(override.expectedBusinessRole == line.businessRole && override.note == line.overrideNote)
        require(override.measurementUuid == frozen.source.measurementUuid && override.projectUuid == frozen.source.projectUuid)
        require(override.basisSourceSignature == frozen.sourceSignature && override.basisCatalogSignature == frozen.catalogSignature)
        require(override.compositionUuid == frozen.compositionUuid && override.basisScopeSignature == frozen.scopeSignature)
    }
}

/** Bind an exact payload to the row and its parent, independent from live source/catalog. */
fun verifyFrozenRevisionIdentity(frozen: FrozenIndividualEstimate, seriesUuid: String, revisionUuid: String,
    versionNumber: Long, parentRevisionUuid: String?) {
    if (versionNumber == 1L) require(frozen.formatVersion in listOf(1, 3, 4) && frozen.revisionIdentity == null && parentRevisionUuid == null)
    else require(frozen.formatVersion in 2..4 && frozen.revisionIdentity ==
        FrozenRevisionIdentity(seriesUuid, revisionUuid, versionNumber, checkNotNull(parentRevisionUuid)))
}

fun verifyFrozenRevisionReceipt(operationUuid: String, command: String, frozen: FrozenIndividualEstimate) {
    val append = frozenEstimateJson.decodeFromString<AppendIndividualRevisionRequest>(command)
    val identity = checkNotNull(frozen.revisionIdentity)
    require(append.target.seriesUuid == identity.seriesUuid && append.target.parentUuid == identity.parentRevisionUuid)
    require(append.target.expectedVersion > 0 && append.target.parentVersion < Long.MAX_VALUE &&
        append.target.parentVersion + 1 == identity.versionNumber && append.target.expectedStatusVersion > 0)
    append.target.historicalHead?.let {
        require(isEstimateUuid(append.target.expectedLatestRevisionUuid) && it.versionNumber > append.target.expectedVersion)
    }
    append.target.expectedSeriesDeletedAt?.let {
        require(it > 0 && append.target.historicalHead == null && append.target.openedRevisionUuid == null)
    }
    append.target.openedRevisionUuid?.let { require(isEstimateUuid(it) && it != identity.revisionUuid) }
    verifyFrozenSaveReceipt(operationUuid, frozenEstimateJson.encodeToString(append.calculation), frozen)
}
