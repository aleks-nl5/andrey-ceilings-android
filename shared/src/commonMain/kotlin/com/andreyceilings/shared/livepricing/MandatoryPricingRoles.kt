package com.andreyceilings.shared.livepricing

/** Readiness policy approved 07.11.2026. Missing bases never synthesize work or money.
 * The same check applies to a whole live ceiling and its selected document rows.
 */
internal fun mandatoryRoleProblems(measurementUuid: String, declaredRoles: List<BusinessRole>,
    selectedLines: List<LiveEstimateLine>): List<PricingProblem> = listOf(
    Triple(BusinessRole.CANVAS, PricingProblemCode.MISSING_MANDATORY_CANVAS, PricingProblemCode.INVALID_MANDATORY_CANVAS),
    Triple(BusinessRole.PRIMARY_PROFILE, PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE, PricingProblemCode.INVALID_MANDATORY_PRIMARY_PROFILE)
).mapNotNull { (role, missing, invalid) ->
    val rows = selectedLines.filter { it.businessRole == role && it.measurementUuid == measurementUuid }
    when {
        role !in declaredRoles -> PricingProblem(missing, "measurement:$measurementUuid")
        rows.size != 1 || rows.single().bucket != PricingBucket.CONFIRMED || !rows.single().validMandatoryRole(role) -> PricingProblem(invalid, "measurement:$measurementUuid")
        else -> null
    }
}

private fun LiveEstimateLine.validMandatoryRole(role: BusinessRole): Boolean = catalogItemUuid.isNotBlank() && when (role) {
    BusinessRole.CANVAS -> unit == "м²" && quantityOrigin in listOf(QuantityOrigin.CAPTURED_AREA, QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE)
    BusinessRole.PRIMARY_PROFILE -> unit == "м.п." && quantityOrigin in listOf(QuantityOrigin.CAPTURED_PERIMETER, QuantityOrigin.EXPLICIT_BILLABLE_OVERRIDE)
    BusinessRole.ADDITIONAL_WORK -> false
}
