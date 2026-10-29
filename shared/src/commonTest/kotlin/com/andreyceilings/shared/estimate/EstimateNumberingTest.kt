package com.andreyceilings.shared.estimate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EstimateNumberingTest {
    private val uuid = "12345678-1234-4abc-8def-0123456789ab"

    @Test
    fun defaultNumberIncludesAllUuidBitsAndReadableSequence() {
        val actual = EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid, 2026, 125))
        assertEquals("AC-2026-00125-$uuid", actual)
        assertEquals(actual, EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid.uppercase(), 2026, 125)))
    }

    @Test
    fun differentOfflineDevicesWithSameLocalSequenceYieldDifferentFullNumbers() {
        val policy = EstimateNumberPolicy()
        val numbers = (1..10_000).map { index ->
            val deviceUuid = "12345678-1234-4abc-8def-${index.toString(16).padStart(12, '0')}"
            EstimateNumbering.render(policy, EstimateNumberContext(deviceUuid, 2026, 1))
        }
        assertEquals(numbers.size, numbers.toSet().size)
        assertTrue(numbers.all { it.contains("-2026-00001-") })
    }

    @Test
    fun templateSettingsAffectNewNumbersButNeverMutateOldOnes() {
        val first = EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid, 2026, 1))
        val changed = EstimateNumbering.render(
            EstimateNumberPolicy(template = "{year}_{prefix}_{uuid}", prefix = "CEIL"),
            EstimateNumberContext(uuid, 2026, 1)
        )
        assertEquals("AC-2026-00001-$uuid", first)
        assertEquals("2026_CEIL_$uuid", changed)
        assertEquals("AC-2026-00001-$uuid", first)
    }

    @Test
    fun missingTruncatedAndRepeatedUuidAreRejected() {
        assertTrue(EstimateNumbering.validate(EstimateNumberPolicy(template = "{prefix}-{year}-{sequence}")).isEmpty())
        assertFalse(EstimateNumbering.validate(EstimateNumberPolicy(template = "{uuid}-{uuid}")).isEmpty())
        assertFalse(EstimateNumbering.validate(EstimateNumberPolicy(template = "{uuidShort}")).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext("12345678", 2026, 1))
        }
    }

    @Test
    fun maliciousUnsupportedUnknownAndUnclosedTokensAreRejected() {
        for (template in listOf("{{uuid}}", "{uuid}-{magic}", "{uuid}-{year", "{uuid}-\u0024{year}", "{uuid}-{prefix}-{prefix}")) {
            assertTrue(EstimateNumbering.validate(EstimateNumberPolicy(template = template)).isNotEmpty(), template)
        }
        assertTrue(EstimateNumbering.validate(EstimateNumberPolicy(prefix = "../../oops")).isNotEmpty())
        assertTrue(EstimateNumbering.validate(EstimateNumberPolicy(sequenceWidth = 0)).isNotEmpty())
    }

    @Test
    fun invalidUuidVersionVariantYearOrSequenceRejected() {
        val invalid = listOf(
            "12345678-1234-1abc-8def-0123456789ab",
            "12345678-1234-4abc-0def-0123456789ab",
            "00000000-0000-0000-0000-000000000000"
        )
        for (bad in invalid) {
            assertFailsWith<IllegalArgumentException> {
                EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(bad, 2026, 1))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid, 2026, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid, 99, 1))
        }
    }

    @Test
    fun identicalUuidStillCollidesAndMustBeRejectedByDatabaseUniqueConstraint() {
        val a = EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid, 2026, 7))
        val b = EstimateNumbering.render(EstimateNumberPolicy(), EstimateNumberContext(uuid, 2026, 7))
        assertEquals(a, b) // No false uniqueness claim: unique indexes and retry are required at insertion.
    }

    @Test
    fun printableFormatHidesFullUuidAndKeepsPrivateIdentity() {
        val cases = listOf(
            EstimateNumberPolicy("{prefix}-{year}-{sequence}", "КШ", 4) to "КШ-2026-0125",
            EstimateNumberPolicy("{year}/{sequence}", "AC", 4) to "2026/0125",
            EstimateNumberPolicy("{prefix}/{year}/{sequence}", "КШ", 5) to "КШ/2026/00125",
            EstimateNumberPolicy("{prefix}-{sequence}", "AC", 5) to "AC-00125",
            EstimateNumberPolicy("{year}.{sequence}", "AC", 4) to "2026.0125"
        )
        for ((policy, expected) in cases) {
            assertTrue(EstimateNumbering.validate(policy).isEmpty(), policy.toString())
            val internal = EstimateNumbering.render(policy, EstimateNumberContext(uuid, 2026, 125))
            assertTrue(internal.contains(uuid), internal)
            assertEquals(expected, EstimateNumbering.printable(internal, 125, policy.prefix, policy.sequenceWidth))
            assertFalse(EstimateNumbering.printable(internal).contains(uuid), internal)
        }
    }

    @Test
    fun legacyUuidTemplatesArePrintableWithoutLeakingPrivateIdentity() {
        for ((template, expected) in listOf(
            "{prefix}-{year}-{sequence}-{uuid}" to "AC-2026-00125",
            "{prefix}-{uuid}-{sequence}" to "AC-00125",
            "{uuid}/{year}/{sequence}" to "2026/00125",
            "{uuid}" to "AC-00125"
        )) {
            val policy = EstimateNumberPolicy(template)
            val internal = EstimateNumbering.render(policy, EstimateNumberContext(uuid, 2026, 125))
            val printed = EstimateNumbering.printable(internal, 125, policy.prefix, policy.sequenceWidth)
            assertEquals(expected, printed)
            assertFalse(printed.contains(uuid))
        }
    }

    @Test
    fun rejectsSequenceFreePublicFormatsButPermitsLegacyUuidOnly() {
        assertTrue(EstimateNumbering.validate(EstimateNumberPolicy("{year}")).isNotEmpty())
        assertTrue(EstimateNumbering.validate(EstimateNumberPolicy("{uuid}")).isEmpty())
        assertTrue(EstimateNumbering.validate(EstimateNumberPolicy("{year}/{sequence}")).isEmpty())
    }

    @Test
    fun previewIsDeterministicAndDoesNotAllocateNumbers() {
        assertEquals(EstimateNumbering.preview(EstimateNumberPolicy()), EstimateNumbering.preview(EstimateNumberPolicy()))
        assertEquals("AC-2026-00001", EstimateNumbering.preview(EstimateNumberPolicy()))
    }
}
