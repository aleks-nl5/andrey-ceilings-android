package com.andreyceilings.shared.estimate

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

class EstimateNumberingTest {
    private val date = LocalDate(2026, 11, 5)

    @Test fun defaultsAndExactLiterals() {
        assertEquals("КШ-2026-0125", EstimateNumbering.render(EstimateNumberPolicy(), date, 125))
        val policy = EstimateNumberPolicy(" № {yearShort}.{month}.{day} / {prefix}--{sequence}// ", "Étage_2", 2)
        val number = EstimateNumbering.render(policy, date, 125)
        assertEquals(" № 26.11.05 / Étage_2--125// ", number)
        assertEquals(number, EstimateNumbering.printable(EstimateNumbering.canonical(number, "12345678-1234-4abc-8def-0123456789ab")))
        assertEquals("КШ-2026-9223372036854775807", EstimateNumbering.render(EstimateNumberPolicy(), date, Long.MAX_VALUE))
    }

    @Test fun rejectsInvalidPoliciesAndOutput() {
        for (template in listOf("{year}/{sequence}", "{prefix}/{year}", "{prefix}/{sequence}",
            "{prefix}{year}{yearShort}{sequence}", "{prefix}{year}{sequence}{day}{day}",
            "{prefix}{year}{sequence}{uuid}", "{prefix}{year}{sequence}{suffix}",
            "{prefix}{year}{sequence}{", "{prefix}{year}{sequence}\\", "{prefix}{year}{sequence}\n",
            "x".repeat(94) + "{prefix}{year}{sequence}")) {
            assertTrue(EstimateNumbering.validatePublic(EstimateNumberPolicy(template)).isNotEmpty(), template)
        }
        assertTrue(EstimateNumbering.validatePublic(EstimateNumberPolicy(prefix = "../A")).isNotEmpty())
        assertTrue(EstimateNumbering.validatePublic(EstimateNumberPolicy(sequenceWidth = 13)).isNotEmpty())
        assertTrue(EstimateNumbering.validate(EstimateIssuancePolicy(individualPrefix = "ІП", consolidatedPrefix = "іп")).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { EstimateNumbering.render(EstimateNumberPolicy(), date, 0) }
        assertFailsWith<IllegalArgumentException> {
            EstimateNumbering.render(EstimateNumberPolicy("12345678-1234-4abc-8def-0123456789ab {prefix}{year}{sequence}"), date, 1)
        }
    }

    @Test fun issueDateUsesTheSuppliedTimeZoneAndTimestamp() {
        val now = Instant.parse("2026-12-31T23:30:00Z").toEpochMilliseconds()
        assertEquals(LocalDate(2026, 12, 31), EstimateNumbering.issueDate(now, TimeZone.UTC))
        assertEquals(LocalDate(2027, 1, 1), EstimateNumbering.issueDate(now, TimeZone.of("Europe/Kyiv")))
    }
}
