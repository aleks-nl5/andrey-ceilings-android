package com.andreyceilings.shared.registry

import com.andreyceilings.shared.dashboard.DashboardBlock
import com.andreyceilings.shared.dashboard.DashboardPreferences
import com.andreyceilings.shared.db.ActiveCeilings
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.navigation.registryAnchor
import com.andreyceilings.shared.objects.CeilingQuoteSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryTest {
    @Test fun titleDateAndStatusMustMatchTheSameRevision() {
        val row = series("1", 1, listOf(
            version("early", 1, 1000, "Bedroom", EstimateWorkflowStatus.APPROVED),
            version("late", 2, 86_401_000, "Living room", EstimateWorkflowStatus.DRAFT)
        ))
        val approved = RegistryFilter(query = "Living", statuses = setOf("APPROVED"), sort = RegistrySort.LATEST)
        assertTrue(filterQuoteRegistry(listOf(row), approved, TimeZone.UTC).isEmpty())
        val from = LocalDate(1970, 1, 2)
        assertTrue(filterQuoteRegistry(listOf(row), approved.copy(query = "Bedroom", from = from), TimeZone.UTC).isEmpty())
        assertEquals(listOf("late", "early"), filterQuoteRegistry(listOf(row),
            approved.copy(query = "55501", statuses = emptySet()), TimeZone.UTC).single().versions.map { it.id })
        assertEquals(listOf("early"), filterQuoteRegistry(listOf(row),
            approved.copy(query = "Bedroom"), TimeZone.UTC).single().versions.map { it.id })
    }

    @Test fun sortingUsesVisibleVersionsAndKeepsStableTies() {
        val hiddenHead = series("1", 1, listOf(
            version("a", 1, 1000, "Matched", EstimateWorkflowStatus.DRAFT),
            version("b", 2, 5000, "Hidden", EstimateWorkflowStatus.DRAFT)
        ))
        val newer = series("2", 2, listOf(version("c", 1, 2000, "Matched", EstimateWorkflowStatus.DRAFT)))
        val tiedOlder = series("3", 1, newer.versions)
        val filter = RegistryFilter(query = "Matched", sort = RegistrySort.LATEST)
        val rows = listOf(tiedOlder, hiddenHead, newer)
        assertEquals(listOf("2", "3", "1"), filterQuoteRegistry(rows, filter).map { it.id })
        assertEquals(listOf("1", "2", "3"), filterQuoteRegistry(rows, filter.copy(ascending = true)).map { it.id })
    }

    @Test fun missingCostSortsLastAndQuoteCategoriesStayDistinct() {
        val rows = (1L..5L).map { id ->
            ActiveCeilings(id, id.toString(), 8L, "Ceiling", 1.0, 2.0, id, id, "Project", "", null, null)
        }
        val drafts = mapOf(
            1L to CeilingFinance(CeilingQuoteKind.DRAFT, CeilingQuoteSummary(0, EstimateWorkflowStatus.DRAFT), emptyList()),
            2L to CeilingFinance(CeilingQuoteKind.APPROVED, CeilingQuoteSummary(5, EstimateWorkflowStatus.APPROVED), emptyList()),
            3L to CeilingFinance(CeilingQuoteKind.REJECTED, null, emptyList()),
            4L to CeilingFinance(CeilingQuoteKind.UNAVAILABLE, null, emptyList())
        )
        val cost = RegistryFilter(sort = RegistrySort.COST)
        assertEquals(listOf(2L, 1L, 5L, 4L, 3L), filterCeilingRegistry(rows, drafts, cost).map { it.id })
        assertEquals(listOf(1L, 2L, 5L, 4L, 3L), filterCeilingRegistry(rows, drafts, cost.copy(ascending = true)).map { it.id })
        assertEquals(listOf(5L), filterCeilingRegistry(rows, drafts, cost.copy(statuses = setOf("NONE"))).map { it.id })
        assertEquals(listOf(3L), filterCeilingRegistry(rows, drafts, cost.copy(statuses = setOf("REJECTED"))).map { it.id })
    }

    @Test fun anchorPrefersNextSurvivorThenPreviousAndClamps() {
        val prior = listOf("a", "b", "c", "d")
        assertEquals(0, registryAnchor(prior, 1, listOf("c", "a", "d")))
        assertEquals(1, registryAnchor(prior, 2, listOf("x", "b")))
        assertEquals(1, registryAnchor(prior, 3, listOf("x", "y")))
        assertEquals(2, registryAnchor(prior, 1, listOf("d", "c", "b")))
    }

    @Test fun dashboardNormalizesAndPacksOnlyAdjacentVisibleCards() {
        val prefs = DashboardPreferences.decode("RATES:0,BOGUS:1,CEILINGS:0,CEILINGS:1,OBJECTS:1,AREA:invalid")
        assertTrue(prefs.isVisible(DashboardBlock.RATES))
        assertTrue(prefs.isVisible(DashboardBlock.CEILINGS))
        assertFalse(prefs.isVisible(DashboardBlock.CLIENTS))
        assertEquals(DashboardBlock.entries.size, prefs.order.toSet().size)
        assertEquals(prefs, DashboardPreferences.decode(prefs.encode()))
        val shuffled = DashboardPreferences.default.move(DashboardBlock.WORK, 2).toggle(DashboardBlock.AREA)
        assertEquals(listOf(DashboardBlock.FINANCE), shuffled.rows().first())
        assertEquals(listOf(DashboardBlock.WORK), shuffled.rows()[1])
        assertEquals(listOf(DashboardBlock.CEILINGS, DashboardBlock.QUOTES), shuffled.rows()[2])
    }

    @Test fun invalidAmountMatchesUnavailableRatherThanWorkflowStatus() {
        val row = series("1", 1, listOf(version("a", 1, 1000, "Invalid", EstimateWorkflowStatus.APPROVED).copy(amount = null)))
        val filter = RegistryFilter(sort = RegistrySort.LATEST, statuses = setOf("APPROVED"))
        assertTrue(filterQuoteRegistry(listOf(row), filter).isEmpty())
        assertEquals("a", filterQuoteRegistry(listOf(row), filter.copy(statuses = setOf("UNAVAILABLE"))).single().versions.single().id)
    }

    @Test fun periodPresetsUseInclusiveLocalDatesAcrossYearBoundary() {
        val today = LocalDate(2026, 1, 3)
        assertEquals(null to null, registryPeriod(RegistryPeriod.ALL, today))
        assertEquals(today to today, registryPeriod(RegistryPeriod.TODAY, today))
        assertEquals(LocalDate(2025, 12, 28) to today, registryPeriod(RegistryPeriod.LAST_7_DAYS, today))
        assertEquals(LocalDate(2026, 1, 1) to today, registryPeriod(RegistryPeriod.MONTH, today))
        val later = LocalDate(2026, 11, 26)
        assertEquals(LocalDate(2026, 1, 1) to later, registryPeriod(RegistryPeriod.YEAR, later))
    }

    private fun series(id: String, created: Long, versions: List<RegistryRevision>) =
        RegistrySeries(id, false, 1, "C-1", created, "Street", "Project", "Main ceiling", "Client", "+1 555-0100", versions)

    private fun version(id: String, version: Long, time: Long, title: String, status: EstimateWorkflowStatus) =
        RegistryRevision(id, version, time, title, status, 100L)
}
