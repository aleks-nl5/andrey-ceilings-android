package com.andreyceilings.shared.calculation

/** Stable IDs stored in estimate details and price list, never localized UI text. */
enum class EstimateOptionId(val code: String) {
    LIGHT_LINE("light.line"),
    SHADOW_PROFILE("work.shadow_profile"),
    FLOATING_PROFILE("work.floating_ceiling"),
    CHANDELIER("light.chandelier"),
    SPOT_GX53("light.spot_gx53"),
    PIPE_PASS("work.pipe_pass"),
    CURTAIN_NICHE("work.curtain_niche"),
    EXTRA_CORNER("work.extra_corner"),
    HARD_WALL("work.hard_wall");

    companion object {
        fun fromCode(code: String): EstimateOptionId? =
            entries.firstOrNull { it.code == code }
    }
}

fun RoomOptions.toCanonicalQuantities(): Map<String, Double> = mapOf(
    EstimateOptionId.LIGHT_LINE.code to lightLinesMeters,
    EstimateOptionId.SHADOW_PROFILE.code to shadowProfileMeters,
    EstimateOptionId.FLOATING_PROFILE.code to floatingCeilingMeters,
    EstimateOptionId.CHANDELIER.code to chandeliersCount,
    EstimateOptionId.SPOT_GX53.code to spotsGX53Count,
    EstimateOptionId.PIPE_PASS.code to pipePassCount,
    EstimateOptionId.CURTAIN_NICHE.code to curtainNicheMeters,
    EstimateOptionId.EXTRA_CORNER.code to extraCorners,
    EstimateOptionId.HARD_WALL.code to hardWallMeters
)

fun roomOptionsFromCanonicalQuantities(quantities: Map<String, Double>): RoomOptions {
    fun get(id: EstimateOptionId): Double = quantities[id.code] ?: 0.0
    return RoomOptions(
        lightLinesMeters = get(EstimateOptionId.LIGHT_LINE),
        shadowProfileMeters = get(EstimateOptionId.SHADOW_PROFILE),
        floatingCeilingMeters = get(EstimateOptionId.FLOATING_PROFILE),
        chandeliersCount = get(EstimateOptionId.CHANDELIER),
        spotsGX53Count = get(EstimateOptionId.SPOT_GX53),
        pipePassCount = get(EstimateOptionId.PIPE_PASS),
        curtainNicheMeters = get(EstimateOptionId.CURTAIN_NICHE),
        extraCorners = get(EstimateOptionId.EXTRA_CORNER),
        hardWallMeters = get(EstimateOptionId.HARD_WALL)
    )
}
