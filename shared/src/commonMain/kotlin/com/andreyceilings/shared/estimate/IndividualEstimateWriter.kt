package com.andreyceilings.shared.estimate

import com.andreyceilings.shared.db.*
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.measurement.*
import kotlinx.serialization.encodeToString

/** Typed expected failures stay in the preview; unexpected SQL errors roll back the outer transaction. */
class IndividualEstimateSaveFailure(val reason: Reason) : IllegalStateException(reason.name) {
    enum class Reason { INVALID_COMMAND, STALE_SOURCE, STALE_CATALOG, NOT_READY, ACK_REQUIRED, OPERATION_CONFLICT, NUMBER_POLICY, NUMBER_COLLISION, SEQUENCE_EXHAUSTED, STALE_REVISION, STALE_STATUS, VERSION_EXHAUSTED, UNAVAILABLE }
}

/** Extension of the existing canonical series repository, using its counter and immutable history. */
internal fun ImmutableEstimateRepository.saveIndividual(
    database: PrototypeDatabase, request: IndividualEstimateSaveRequest, year: Int, now: Long, publicToken: () -> String, target: IndividualRevisionTarget? = null
): CreatedEstimateSeries = database.transactionWithResult {
    fun fail(reason: IndividualEstimateSaveFailure.Reason): Nothing = throw IndividualEstimateSaveFailure(reason)
    // SQLite writer lock is claimed before receipt/source/settings reads. The host has already
    // registered this operation; Android's outer transaction owns the physical restore lease.
    database.priceGroupsQueries.claimCatalogWrite()
    if (!isEstimateUuid(request.operationUuid) || !isEstimateUuid(request.sessionId) || request.projectId <= 0 ||
        validEstimateTitle(request.title) != request.title || now <= 0 || year !in 1000..9999)
        fail(IndividualEstimateSaveFailure.Reason.INVALID_COMMAND)
    val q = database.frozenEstimateQueries
    val revisions = database.frozenRevisionQueries
    val command = if (target == null) frozenEstimateJson.encodeToString(request)
        else frozenEstimateJson.encodeToString(AppendIndividualRevisionRequest(target, request))
    if (target != null && q.selectEstimateSaveReceipt(request.operationUuid).executeAsOneOrNull() != null ||
        target == null && revisions.selectRevisionReceipt(request.operationUuid).executeAsOneOrNull() != null)
        fail(IndividualEstimateSaveFailure.Reason.OPERATION_CONFLICT)
    if (target != null) revisions.selectRevisionReceipt(request.operationUuid).executeAsOneOrNull()?.let { receipt ->
        if (receipt.command != command) fail(IndividualEstimateSaveFailure.Reason.OPERATION_CONFLICT)
        val existing = checkNotNull(series(receipt.seriesUuid))
        val exact = checkNotNull(exactIndividual(receipt.seriesUuid, receipt.revisionUuid))
        verifyFrozenRevisionReceipt(request.operationUuid, command, exact.frozen)
        return@transactionWithResult CreatedEstimateSeries(existing.seriesUuid, existing.documentNumber, receipt.revisionUuid, existing.localSequence)
    }
    q.selectEstimateSaveReceipt(request.operationUuid).executeAsOneOrNull()?.let { receipt ->
        if (receipt.command != command) fail(IndividualEstimateSaveFailure.Reason.OPERATION_CONFLICT)
        val existing = checkNotNull(series(receipt.seriesUuid))
        return@transactionWithResult CreatedEstimateSeries(existing.seriesUuid, existing.documentNumber,
            receipt.revisionUuid, existing.localSequence)
    }
    val previous = target?.let { t ->
        if (!isEstimateUuid(t.seriesUuid) || !isEstimateUuid(t.expectedLatestRevisionUuid) || t.expectedVersion <= 0 || t.expectedStatusVersion <= 0)
            fail(IndividualEstimateSaveFailure.Reason.INVALID_COMMAND)
        val root = series(t.seriesUuid) ?: fail(IndividualEstimateSaveFailure.Reason.UNAVAILABLE)
        if (root.deletedAt != null || root.projectId != request.projectId || root.measurementUuid != request.selection.measurementUuid)
            fail(IndividualEstimateSaveFailure.Reason.INVALID_COMMAND)
        val latest = database.estimateHistoryQueries.selectLatestEstimateRevision(t.seriesUuid).executeAsOneOrNull()
        if (latest == null || latest.revisionUuid != t.expectedLatestRevisionUuid || latest.versionNumber != t.expectedVersion)
            fail(IndividualEstimateSaveFailure.Reason.STALE_REVISION)
        val frozen = exactIndividual(t.seriesUuid, t.expectedLatestRevisionUuid)
            ?: fail(IndividualEstimateSaveFailure.Reason.UNAVAILABLE)
        if (frozen.statusVersion != t.expectedStatusVersion) fail(IndividualEstimateSaveFailure.Reason.STALE_STATUS)
        if (t.expectedVersion == Long.MAX_VALUE || t.expectedStatusVersion == Long.MAX_VALUE)
            fail(IndividualEstimateSaveFailure.Reason.VERSION_EXHAUSTED)
        frozen
    }
    val selection = request.selection
    val read = LivePricingReader(database).readInTransaction(request.projectId, selection.measurementUuid,
        selection.basisSourceSignature, selection.basisCatalogSignature)
    if (read is PricingReadResult.Unavailable) fail(when (read.problem) {
        PricingProblemCode.STALE_SOURCE -> IndividualEstimateSaveFailure.Reason.STALE_SOURCE
        PricingProblemCode.STALE_CATALOG -> IndividualEstimateSaveFailure.Reason.STALE_CATALOG
        else -> IndividualEstimateSaveFailure.Reason.UNAVAILABLE
    })
    read as PricingReadResult.Loaded
    val roles = BusinessRoleBindings(read.source.signature, read.source.elements.mapNotNull { e -> e.businessRole?.let { e.uuid to it } }.toMap())
    val live = LivePricingEngine.buildPreview(read.source, read.catalog, roles, selection.compositionUuid,
        request.overrides, selection.scopeSignature)
    val preview = IndividualEstimatePricing.previewSelection(selection, live)
    if (!preview.canFinalizeFull && !preview.canRequestPartialAcknowledgement) fail(IndividualEstimateSaveFailure.Reason.NOT_READY)
    val bp = QuoteDiscount.parseBasisPoints(request.discountText) ?: fail(IndividualEstimateSaveFailure.Reason.INVALID_COMMAND)
    val amounts = QuoteDiscount.calculate(preview.selectedIncludedTotalCents, bp)
    val fingerprint = quoteSaveFingerprint(request.sessionId, preview.signature, request.discountText, amounts)
    if (amounts != request.expectedAmounts || fingerprint != request.expectedFingerprint)
        fail(IndividualEstimateSaveFailure.Reason.STALE_SOURCE)
    val omissions = preview.excluded.filter { it.bucket == PricingBucket.CONFIRMED }
    if (omissions != request.expectedOmissions || preview.isPartial && request.acknowledgedFingerprint != fingerprint)
        fail(IndividualEstimateSaveFailure.Reason.ACK_REQUIRED)
    // Reject overrides of omitted/unavailable lines, even when the pure pricing layer didn't use them.
    val included = preview.included.filter { it.selectedForDocument }.map { it.line }
    if (request.overrides.any { override -> included.none { it.elementUuid == override.measurementElementUuid } })
        fail(IndividualEstimateSaveFailure.Reason.INVALID_COMMAND)
    val policy = previous?.frozen?.numberPolicy ?: numberPolicy()
    if (EstimateNumbering.validatePublic(policy).isNotEmpty()) fail(IndividualEstimateSaveFailure.Reason.NUMBER_POLICY)
    val m = checkNotNull(MeasurementRepository(database).findByUuid(selection.measurementUuid))
    val project = checkNotNull(ProjectRepository(database).find(request.projectId))
    val source = FrozenIndividualSource(project.uuid, project.title, m.uuid, m.name, m.drawingJson,
        mapOf("shape" to m.shape, "lengthMeters" to m.lengthMeters.toString(), "widthMeters" to m.widthMeters.toString(),
            "areaSquareMeters" to m.areaSquareMeters.toString(), "perimeterMeters" to m.perimeterMeters.toString(),
            "notes" to m.notes, "createdAt" to m.createdAt.toString(), "updatedAt" to m.updatedAt.toString()),
        read.source.versions, read.source.elements, read.source.points, read.source.lines, read.source.areas,
        database.livePricingQueries.selectPricingWalls(m.uuid).executeAsList().map { w ->
            mapOf("uuid" to w.uuid, "ordinal" to w.ordinal.toString(), "ax" to w.ax.toString(), "ay" to w.ay.toString(),
                "bx" to w.bx.toString(), "by" to w.by_.toString(), "geometryVersion" to w.geometryVersion.toString(), "active" to w.active.toString()) },
        database.livePricingQueries.selectPricingReceipts(m.uuid).executeAsList().map { r ->
            mapOf("uuid" to r.uuid, "expectedUpdatedAt" to r.expectedUpdatedAt.toString(), "payload" to r.payload,
                "createdAt" to r.createdAt.toString(), "measurementVersion" to r.measurementVersion.toString(),
                "pointVersions" to r.pointVersions.orEmpty(), "elementVersions" to r.elementVersions.orEmpty(),
                "lineVersions" to r.lineVersions.orEmpty(), "areaVersions" to r.areaVersions.orEmpty()) })
    val history = database.estimateHistoryQueries
    val created = if (target != null) {
        val root = checkNotNull(series(target.seriesUuid))
        CreatedEstimateSeries(root.seriesUuid, root.documentNumber, newRecordUuid(), root.localSequence)
    } else {
        // Reserve only after every source/composition validation; any later error rolls back this counter.
        history.insertDefaultEstimateNumberSettings()
        val sequence = (history.allocateEstimateSequence().executeAsOneOrNull()
            ?: fail(IndividualEstimateSaveFailure.Reason.SEQUENCE_EXHAUSTED)) - 1L
        var identity: CreatedEstimateSeries? = null
        repeat(5) {
            if (identity == null) {
                val seriesId = newRecordUuid()
                val number = EstimateNumbering.render(policy, EstimateNumberContext(seriesId, year, sequence,
                    publicToken()))
                val publicNumber = EstimateNumbering.printable(number)
                if (q.selectEstimatePublicNumber(publicNumber).executeAsOneOrNull() == null &&
                    history.insertEstimateSeriesIfFree(seriesId, number, request.projectId, m.uuid, policy.template,
                        policy.prefix, policy.sequenceWidth.toLong(), sequence, now).executeAsOneOrNull() != null) {
                    q.setEstimatePublicNumber(publicNumber, seriesId)
                    identity = CreatedEstimateSeries(seriesId, number, newRecordUuid(), sequence)
                }
            }
        }
        identity ?: fail(IndividualEstimateSaveFailure.Reason.NUMBER_COLLISION)
    }
    val version = if (target == null) 1L else target.expectedVersion + 1
    val frozen = FrozenIndividualEstimate(formatVersion = if (target == null) 1 else 2,
        revisionIdentity = target?.let { FrozenRevisionIdentity(it.seriesUuid, created.initialRevisionUuid, version, it.expectedLatestRevisionUuid) }, title = request.title, source = source, sourceSignature = read.source.signature,
        catalogSignature = read.catalog.signature, scopeSignature = selection.scopeSignature, compositionUuid = selection.compositionUuid,
        sessionId = request.sessionId, discountText = request.discountText, previewSignature = preview.signature,
        fingerprint = fingerprint, acknowledgedFingerprint = request.acknowledgedFingerprint, partial = preview.isPartial,
        omissions = omissions, rows = included.map { FrozenIndividualLine(newRecordUuid(), it) }, amounts = amounts,
        numberPolicy = policy, publicNumber = EstimateNumbering.printable(created.documentNumber))
    val payload = frozen.encode()
    // These REAL values support legacy history tools only. All P4C1 reads use the exact frozen table.
    history.insertImmutableEstimateRevision(created.initialRevisionUuid, created.seriesUuid, target?.expectedLatestRevisionUuid, version, now,
        m.drawingJson, payload, read.source.geometry.precisionStepMeters, if (target == null) "individual-quote-v1" else "individual-quote-v2", "UAH",
        frozen.rounding, amounts.finalCents.toDouble() / 100)
    if (target == null) {
        q.insertFrozenV1(created.initialRevisionUuid, payload, amounts.subtotalCents, bp.toLong(), amounts.discountCents, amounts.finalCents)
        q.insertEstimateWorkflow(created.seriesUuid, now)
        q.insertEstimateSaveReceipt(request.operationUuid, command, created.seriesUuid, created.initialRevisionUuid)
    } else {
        revisions.insertFrozenRevision(created.initialRevisionUuid, payload, amounts.subtotalCents, bp.toLong(), amounts.discountCents, amounts.finalCents)
        if (q.updateEstimateWorkflow(EstimateWorkflowStatus.DRAFT.name, now, target.seriesUuid, target.expectedStatusVersion).executeAsOneOrNull() == null)
            fail(IndividualEstimateSaveFailure.Reason.STALE_STATUS)
        revisions.insertRevisionReceipt(request.operationUuid, command, created.seriesUuid, created.initialRevisionUuid)
    }
    created
}
