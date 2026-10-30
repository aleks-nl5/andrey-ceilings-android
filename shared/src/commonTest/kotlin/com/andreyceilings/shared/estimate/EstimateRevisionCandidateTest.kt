package com.andreyceilings.shared.estimate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EstimateRevisionCandidateTest {
    private fun frozenLines() = listOf(
        ImmutableEstimateLine("canvas.pvc", "PVC canvas", "м²", 6.0, 10.0, 60.0, "canvas-uuid"),
        ImmutableEstimateLine("profile.standard", "Aluminium", "м.п.", 10.0, 5.0, 50.0, "profile-uuid")
    )

    private fun source(lines: List<ImmutableEstimateLine> = frozenLines()) =
        ImmutableEstimateDraft(
            sourceMeasurementJson = """{"lengthMeters":2.0,"widthMeters":3.0}""",
            detailsJson = """{"measurementPrecisionMeters":0.01}""",
            measurementPrecisionMeters = 0.01,
            calculationRuleVersion = "kmp-v1",
            totalCost = lines.sumOf { it.lineTotal },
            lines = lines
        )

    private fun measurement() = VerifiedRectangleInputs(
        lengthMeters = 2.0, widthMeters = 3.0,
        storedAreaSquareMeters = 6.0, storedPerimeterMeters = 10.0,
        precisionStepMeters = 0.01
    )

    private fun preview(src: ImmutableEstimateDraft) = EstimateRecalculation.preview(
        baseRevisionUuid = "original-v1",
        baseVersionNumber = 1L,
        originalTotal = src.totalCost,
        frozenLines = src.lines,
        prices = listOf(
            CurrentEstimatePrice("canvas.pvc", "canvas-uuid", "Updated canvas",
                "м²", 20.0, 111L),
            CurrentEstimatePrice("profile.standard", "profile-uuid", "Updated profile",
                "м.п.", 6.0, 112L)
        )
    )

    @Test fun reproducibleRectangleProducesWriteFreeImmutableLineCandidate() {
        val saved = source()
        val result = EstimateRevisionCandidates.prepareSimpleRectangle(
            saved, measurement(), preview(saved)
        )
        assertEquals("original-v1", result.baseRevisionUuid)
        assertEquals(2L, result.proposedVersionNumber)
        assertEquals(6.0, result.geometry.area)
        assertEquals(10.0, result.geometry.perimeter)
        assertEquals(180.0, result.totalCost)
        assertEquals(listOf(120.0, 60.0), result.lines.map { it.lineTotal })
        assertEquals("Updated canvas", result.lines.first().title)
        assertEquals(110.0, saved.totalCost)
        assertEquals("PVC canvas", saved.lines.first().title)
        assertTrue(result.lines !== saved.lines)
    }

    @Test fun inconsistentGeometryAndFrozenQuantitiesFailClosed() {
        val saved = source()
        val proposed = preview(saved)
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(
                saved, measurement().copy(storedAreaSquareMeters = 7.0), proposed
            )
        }
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(
                saved, measurement().copy(lengthMeters = 4.0), proposed
            )
        }
        val wrongQuantity = saved.copy(lines = saved.lines.mapIndexed { i, line ->
            if (i == 0) line.copy(quantity = 5.0) else line
        })
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(
                wrongQuantity, measurement(), preview(wrongQuantity)
            )
        }
    }

    @Test fun optionsLegacyAndUnsupportedRulesAreNotGuessed() {
        val saved = source()
        val proposed = preview(saved)
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(
                saved.copy(calculationRuleVersion = "legacy-v0"), measurement(), proposed
            )
        }
        val extra = saved.copy(
            lines = saved.lines + ImmutableEstimateLine(
                "option.spot", "Spot", "шт.", 1.0, 100.0, 100.0
            ),
            totalCost = 210.0
        )
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(
                extra, measurement(), preview(extra)
            )
        }
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(
                saved, measurement().copy(precisionStepMeters = 0.005), proposed
            )
        }
    }

    @Test fun blockedPreviewCannotBeConvertedIntoCandidate() {
        val saved = source()
        val proposed = preview(saved)
        val blocked = proposed.copy(problems = setOf(RecalculationProblem.CHANGED_CODE),
            proposedTotal = null)
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(saved, measurement(), blocked)
        }
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareSimpleRectangle(saved,
                measurement(), proposed.copy(proposedTotal = 999.0))
        }
    }

    @Test fun canonicalOptionsProduceConsistentKmpRectangleRepricing() {
        val option = ImmutableEstimateLine(
            "light.spot", "Old spotlight", "шт.", 2.0, 11.0, 22.0, "spot-uuid"
        )
        val frozen = listOf(
            ImmutableEstimateLine(
                "canvas.pvc_matte", "Old canvas", "м²", 6.0, 10.0, 60.0, "canvas-uuid"
            ),
            ImmutableEstimateLine(
                "profile.standard_alu", "Old profile", "м.п.", 10.0, 5.0, 50.0, "profile-uuid"
            ),
            option
        )
        val saved = source(frozen)
        val prices = listOf(
            CurrentEstimatePrice(
                "canvas.pvc_matte", "canvas-uuid", "New canvas", "м²", 20.0, 100L
            ),
            CurrentEstimatePrice(
                "profile.standard_alu", "profile-uuid", "New profile", "м.п.", 6.0, 100L
            ),
            CurrentEstimatePrice(
                "light.spot", "spot-uuid", "New spotlight", "шт.", 25.0, 100L
            )
        )
        val comparison = EstimateRecalculation.preview(
            "old-v1", 1L, saved.totalCost, saved.lines, prices
        )
        val options = com.andreyceilings.shared.calculation.EstimateOptionId.entries
            .associate { it.code to (if (it.code == "light.spot") 2.0 else 0.0) }
        val result = EstimateRevisionCandidates.prepareRectangleWithOptions(
            saved, measurement(), comparison, options
        )
        assertEquals(230.0, result.totalCost)
        assertEquals(listOf(120.0, 60.0, 50.0),
            result.lines.map { it.lineTotal })

        // Lost options, mismatched units or quantities, and extra hidden
        // lines must stop before any future v2/v3 INSERT.
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareRectangleWithOptions(
                saved, measurement(), comparison, options + ("light.spot" to 0.0)
            )
        }
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareRectangleWithOptions(
                saved, measurement(), comparison, options - "light.spot"
            )
        }
        val altered = saved.copy(lines = frozen.map {
            if (it.stableCode == "light.spot") it.copy(quantity = 3.0) else it
        })
        val alteredPreview = EstimateRecalculation.preview(
            "old-v1", 1L, altered.totalCost, altered.lines, prices
        )
        assertFailsWith<IllegalStateException> {
            EstimateRevisionCandidates.prepareRectangleWithOptions(
                altered, measurement(), alteredPreview, options
            )
        }
    }
}
