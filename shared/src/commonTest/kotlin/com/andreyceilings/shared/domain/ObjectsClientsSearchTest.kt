package com.andreyceilings.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectsClientsSearchTest {
    private val clients = listOf(
        ClientListEntry(1, "Андрій Коваль", "+380 (67) 123-45-67", "a@example.com", "Після обіду", 2),
        ClientListEntry(2, "Марія", "+380 99 700 80 90", "", "", 1)
    )
    private val projects = listOf(
        ProjectListEntry(10, "Квартира", "Київ, Хрещатик 11", 1, "Андрій Коваль",
            "in_progress", "paid", "Натяжна стеля"),
        ProjectListEntry(11, "Кухня", "Львів, Січова 3", 2, "Марія",
            "approved", "partial", "")
    )

    @Test
    fun globalSuggestionsIncludeBothEntitiesWithoutSwitchingTab() {
        val result = ObjectsClientsSearch.suggestions(projects, clients, "андрій")
        assertEquals(listOf(10L), result.projects.map { it.id })
        assertEquals(listOf(1L), result.clients.map { it.id })
        assertTrue(ObjectsClientsSearch.suggestions(projects, clients, " ").isEmpty)
    }

    @Test
    fun wordsCanMatchDifferentProjectFields() {
        assertEquals(listOf(10L), ObjectsClientsSearch.projects(projects, "Київ Коваль").map { it.id })
        assertEquals(listOf(10L), ObjectsClientsSearch.projects(projects, "оплачено").map { it.id })
        assertEquals(emptyList(), ObjectsClientsSearch.projects(projects, "Київ Марія"))
    }

    @Test
    fun phoneSearchIgnoresNumberFormattingAndPunctuation() {
        assertEquals(listOf(1L), ObjectsClientsSearch.clients(clients, "06712345").map { it.id })
        assertEquals(listOf(2L), ObjectsClientsSearch.clients(clients, "700 80").map { it.id })
        assertEquals(listOf(1L), ObjectsClientsSearch.clients(clients, "EXAMPLE.COM").map { it.id })
    }

    @Test
    fun workAndPaymentFiltersCanBeCombinedWithoutMixingTheirMeaning() {
        assertEquals(listOf(10L),
            ObjectsClientsSearch.projects(projects, "", workFilter = "in_progress", paymentFilter = "paid")
                .map { it.id })
        assertTrue(ObjectsClientsSearch.projects(projects, "", workFilter = "completed", paymentFilter = "paid").isEmpty())
        assertEquals(listOf(11L),
            ObjectsClientsSearch.projects(projects, "", paymentFilter = "partial").map { it.id })
    }

    @Test
    fun suggestionsRespectIndependentLimits() {
        val result = ObjectsClientsSearch.suggestions(projects, clients, "а", maxPerType = 1)
        assertTrue(result.projects.size <= 1)
        assertTrue(result.clients.size <= 1)
    }
}
