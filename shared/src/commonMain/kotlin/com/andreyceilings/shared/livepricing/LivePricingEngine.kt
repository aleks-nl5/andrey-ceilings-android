package com.andreyceilings.shared.livepricing

import com.andreyceilings.shared.estimate.FrozenPolygonGeometryContract
import com.andreyceilings.shared.measurement.*
import com.andreyceilings.shared.prices.*
import kotlinx.serialization.encodeToString

object LivePricingEngine {
    fun buildPreview(source: LiveEstimateSource, catalog: ActiveCatalogSnapshot,
        roles: BusinessRoleBindings? = null, compositionUuid: String = "preview",
        overrides: List<EstimateQuantityOverride> = emptyList(), scopeSignature: String = compositionUuid): LiveEstimatePreview {
        val fatal = mutableListOf<PricingProblem>()
        val warnings = mutableListOf<PricingProblem>()
        val unresolved = mutableListOf<UnresolvedWork>()
        val priced = mutableListOf<LiveEstimateLine>()
        fun fail(code: PricingProblemCode, key: String? = null) { fatal += PricingProblem(code, key) }
        val geometryValid = runCatching { FrozenPolygonGeometryContract.requireValid(source.geometry) }.isSuccess
        if (!geometryValid) fail(PricingProblemCode.INVALID_GEOMETRY)
        val sourceSignature = if (geometryValid) source.signature else "invalid-geometry"
        val catalogSignature = catalog.signature
        if (source.projectUuid.isBlank() || source.measurementUuid.isBlank() || compositionUuid.isBlank() ||
            source.elements.any { it.uuid.isBlank() || it.measurementUuid != source.measurementUuid || it.deletedAt != null ||
                it.label.isBlank() || it.createdAt < 0 || it.updatedAt < it.createdAt ||
                (it.source as? ElementSource.CatalogSelection)?.let { s -> s.priceItemUuid.isBlank() || s.code.isBlank() } == true ||
                it.placement == ElementPlacement.UNPLACED && it.placedQuantityMilli != 0L || it.placedQuantityMilli !in 0..ElementQuantityRules.MAX_MILLI } ||
            source.elements.map { it.uuid }.distinct().size != source.elements.size ||
            catalog.items.map { it.uuid }.distinct().size != catalog.items.size ||
            catalog.items.any { it.uuid.isBlank() || it.groupUuid.isBlank() || it.code.isBlank() || it.updatedAt < 0 || it.groupUpdatedAt < 0 }) {
            fail(PricingProblemCode.INVALID_SOURCE)
        }
        val catalogElements = source.elements.filter { it.source is ElementSource.CatalogSelection }
        if (roles != null && roles.basisSourceSignature != sourceSignature) fail(PricingProblemCode.STALE_SOURCE)
        if (roles != null && roles.roles.keys.any { id -> catalogElements.none { it.uuid == id } }) fail(PricingProblemCode.INVALID_SOURCE)
        catalogElements.filter { roles?.roles?.get(it.uuid) == null }.forEach { fail(PricingProblemCode.ROLE_UNDETERMINED, elementKey(it.uuid)) }
        for ((role, code) in listOf(BusinessRole.CANVAS to PricingProblemCode.DUPLICATE_MAIN_CANVAS,
            BusinessRole.PRIMARY_PROFILE to PricingProblemCode.DUPLICATE_PRIMARY_PROFILE)) {
            if (catalogElements.count { roles?.roles?.get(it.uuid) == role } > 1) fail(code)
        }
        if (geometryValid && runCatching { validatePlacements(source, fatal) }.isFailure) fail(PricingProblemCode.INVALID_SOURCE)
        val byId = catalog.items.associateBy { it.uuid }
        if (overrides.map { it.measurementElementUuid }.distinct().size != overrides.size) fail(PricingProblemCode.INVALID_OVERRIDE)
        overrides.forEach { override ->
            val e = source.elements.singleOrNull { it.uuid == override.measurementElementUuid }
            val role = roles?.roles?.get(e?.uuid)
            val mode = (e?.source as? ElementSource.CatalogSelection)?.quantityMode
            val validRole = role == BusinessRole.CANVAS && mode == QuantityMode.CEILING_AREA && override.unit == "м²" ||
                role == BusinessRole.PRIMARY_PROFILE && mode == QuantityMode.CEILING_PERIMETER && override.unit == "м.п."
            if (override.compositionUuid != compositionUuid || override.basisScopeSignature != scopeSignature || override.projectUuid != source.projectUuid ||
                override.measurementUuid != source.measurementUuid || override.expectedBusinessRole != role || !validRole ||
                !override.explicitlyConfirmed || override.quantityMilli !in 1..ElementQuantityRules.MAX_MILLI || override.unit != e?.unit) {
                fail(PricingProblemCode.INVALID_OVERRIDE, elementKey(override.measurementElementUuid))
            }
            if (override.basisSourceSignature != sourceSignature) fail(PricingProblemCode.STALE_SOURCE)
            if (override.basisCatalogSignature != catalogSignature) fail(PricingProblemCode.STALE_CATALOG)
        }
        for (e in source.elements.sortedBy { it.uuid }.distinctBy { it.uuid }) {
            val key = elementKey(e.uuid)
            val reasons = mutableListOf<PricingProblemCode>()
            val selection = e.source as? ElementSource.CatalogSelection
            val item = selection?.let { byId[it.priceItemUuid] }
            if (selection == null) reasons += PricingProblemCode.SELECTION_MISSING
            else if (item == null || selection.availability != CatalogAvailability.ACTIVE) reasons += PricingProblemCode.CATALOG_MISSING
            else if (item.code != selection.code || item.unit != e.unit || item.quantityMode != selection.quantityMode ||
                item.placementKind != selection.placementKind) reasons += PricingProblemCode.METADATA_DRIFT
            if (e.unit !in PriceCatalogRules.standardUnits) reasons += PricingProblemCode.UNSUPPORTED_UNIT
            val cents = item?.let { PricingMath.salePriceCents(it.salePrice) }
            if (item != null && cents == null) reasons += PricingProblemCode.INVALID_PRICE
            val validQuantity = runCatching { ElementQuantityRules.validate(e.quantity, e.unit, selection?.quantityMode) }.isSuccess &&
                (e.unit !in listOf("шт.", "компл.") || when (val q = e.quantity) {
                    is ElementQuantity.Exact -> q.milli % 1000 == 0L
                    is ElementQuantity.Range -> q.minMilli % 1000 == 0L && q.maxMilli % 1000 == 0L
                    else -> true
                })
            if (!validQuantity) reasons += PricingProblemCode.INVALID_QUANTITY
            val quantity = when (val q = e.quantity) {
                ElementQuantity.Unknown -> { reasons += PricingProblemCode.UNKNOWN_QUANTITY; null }
                is ElementQuantity.Range -> { reasons += PricingProblemCode.RANGE_QUANTITY; null }
                is ElementQuantity.Exact -> q.milli
                ElementQuantity.CeilingArea -> if (geometryValid) PricingMath.capturedMilli(source.geometry.areaSquareMeters) else null
                ElementQuantity.CeilingPerimeter -> if (geometryValid) PricingMath.capturedMilli(source.geometry.perimeterMeters) else null
            }
            if (geometryValid && quantity == null && e.quantity in listOf(ElementQuantity.CeilingArea, ElementQuantity.CeilingPerimeter))
                reasons += PricingProblemCode.QUANTITY_PRECISION
            val role = roles?.roles?.get(e.uuid)
            if (role == BusinessRole.CANVAS && e.quantity != ElementQuantity.CeilingArea ||
                role == BusinessRole.PRIMARY_PROFILE && e.quantity != ElementQuantity.CeilingPerimeter) fail(PricingProblemCode.INVALID_SOURCE, key)
            val reviewed = source.points.any { it.elementUuid == e.uuid && (it.mounting.needsReview || it.mounting.unresolved) } ||
                source.lines.any { it.elementUuid == e.uuid && (it.needsReview || it.unresolved) } ||
                source.areas.any { it.elementUuid == e.uuid && (it.needsReview || it.unresolved) }
            if (reviewed) reasons += PricingProblemCode.PLACEMENT_REVIEW
            val flags = if (selection?.placementKind == PlacementKind.NONE) emptyList() else buildList {
                if (e.placement == ElementPlacement.UNPLACED) add(PricingProblemCode.PLACEMENT_UNPLACED)
                if (e.placement == ElementPlacement.APPROXIMATE) add(PricingProblemCode.PLACEMENT_APPROXIMATE)
                if (quantity != null && e.placedQuantityMilli < quantity) add(PricingProblemCode.PLACEMENT_PARTIAL)
            }
            flags.forEach { warnings += PricingProblem(it, key) }
            if (reasons.isNotEmpty() || quantity == null || item == null || cents == null) {
                unresolved += UnresolvedWork(key, source.measurementUuid, e.uuid, e.label, e.approval.bucket(), reasons.distinct().sortedBy { it.name })
                continue
            }
            // An invalid explicit override must never fall back to the measured amount.
            if (overrides.any { it.measurementElementUuid == e.uuid } && fatal.isNotEmpty()) continue
            val override = overrides.singleOrNull { it.measurementElementUuid == e.uuid }
            val effective = override?.quantityMilli ?: quantity
            val total = try { PricingMath.lineCents(cents, effective) }
            catch (_: PricingOverflow) { fail(PricingProblemCode.OVERFLOW, key); continue }
            catch (_: IllegalArgumentException) { fail(PricingProblemCode.INVALID_SOURCE, key); continue }
            priced += LiveEstimateLine(key, source.measurementUuid, e.uuid, item.uuid, item.code, item.title, item.unit,
                quantity, effective, cents, total, if (override != null) QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE else when (e.quantity) {
                    ElementQuantity.CeilingArea -> QuantityOrigin.CAPTURED_AREA
                    ElementQuantity.CeilingPerimeter -> QuantityOrigin.CAPTURED_PERIMETER
                    else -> QuantityOrigin.DECLARED_EXACT
                }, e.approval.bucket(), flags, override?.note, role)
        }
        var confirmed = 0L
        var potential = 0L
        try {
            priced.forEach { if (it.bucket == PricingBucket.CONFIRMED) confirmed = PricingMath.add(confirmed, it.totalCents)
                else potential = PricingMath.add(potential, it.totalCents) }
        } catch (_: PricingOverflow) { fail(PricingProblemCode.OVERFLOW); confirmed = 0; potential = 0 }
        val businessRoles = catalogElements.mapNotNull { e -> roles?.roles?.get(e.uuid)?.let { e.uuid to it } }.toMap()
        val mandatory = mandatoryRoleProblems(source.measurementUuid, businessRoles.values.toList(), priced)
        val signature = contentToken(listOf(sourceSignature, catalogSignature, compositionUuid, scopeSignature,
            contentToken(roles?.roles.orEmpty().entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value.name) }),
            pricingJson.encodeToString(overrides.sortedBy { it.measurementElementUuid })))
        return LiveEstimatePreview(source.projectUuid, source.measurementUuid, compositionUuid, sourceSignature, catalogSignature, scopeSignature,
            signature, priced, unresolved, fatal.distinct().sortedWith(compareBy({ it.sourceKey.orEmpty() }, { it.code.name })),
            warnings.distinct().sortedWith(compareBy({ it.sourceKey.orEmpty() }, { it.code.name })), confirmed, potential, businessRoles, mandatory)
    }

    private fun validatePlacements(source: LiveEstimateSource, fatal: MutableList<PricingProblem>) {
        fun invalid() { fatal += PricingProblem(PricingProblemCode.INVALID_SOURCE) }
        val elements = source.elements.associateBy { it.uuid }
        val ids = source.points.map { it.uuid } + source.lines.map { it.uuid } + source.areas.map { it.uuid }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) invalid()
        source.points.forEach { p ->
            val e = elements[p.elementUuid]
            if (e?.pointCapacity() == null || p.deletedAt != null ||
                !p.mounting.unresolved && !pointStrictlyInside(source.geometry, p.xMm, p.yMm)) invalid()
        }
        source.lines.forEach { l ->
            if (elements[l.elementUuid]?.supportsLinePlacement() != true || l.deletedAt != null ||
                !l.unresolved && !lineStrictlyInside(source.geometry, l.start, l.end)) invalid()
        }
        source.areas.forEach { a ->
            if (a.measurementUuid != source.measurementUuid || a.deletedAt != null ||
                a.elementUuid != null && elements[a.elementUuid]?.supportsAreaPlacement() != true ||
                runCatching { canonicalTwiceAreaMm2(a.shape) }.isFailure ||
                !a.unresolved && !areaStrictlyInside(source.geometry, a.shape)) invalid()
        }
    }
}
