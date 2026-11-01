package com.andreyceilings.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientAlphabetIndexTest {
    private fun client(id: Long, name: String) =
        ClientListEntry(id, name, "", "", "", 0)

    @Test
    fun clientsAreSortedByUkrainianPhonebookLettersThenLatinAndOther() {
        val unsorted = listOf(
            client(1, "Ївга"),
            client(2, "Ґанна"),
            client(3, "Єва"),
            client(4, "Андрій"),
            client(5, "Ілля"),
            client(6, "Галина"),
            client(7, "Иван"),
            client(8, "Емма"),
            client(9, "Alice"),
            client(10, "9 сервіс")
        )
        assertEquals(
            listOf("Андрій", "Галина", "Ґанна", "Емма", "Єва", "Иван", "Ілля", "Ївга", "Alice", "9 сервіс"),
            ClientAlphabetIndex.sorted(unsorted).map { it.name }
        )
    }

    @Test
    fun markersPointToFirstVisibleClientOfEachRealGroup() {
        val visible = ObjectsClientsSearch.clients(
            listOf(client(1, "Яна"), client(2, "Андрій"), client(3, "Алла"),
                client(4, "Єва"), client(5, "Alice"), client(6, "# сервіс")),
            ""
        )
        assertEquals(
            listOf(
                ClientLetterJump('А', 0),
                ClientLetterJump('Є', 2),
                ClientLetterJump('Я', 3),
                ClientLetterJump('A', 4),
                ClientLetterJump('#', 5)
            ),
            ClientAlphabetIndex.markers(visible)
        )
        assertEquals("Алла", visible.first().name)
        assertEquals("# сервіс", visible.last().name)
    }

    @Test
    fun searchFilteringAndEmptyListsNeverProduceInvalidJumps() {
        val source = listOf(client(1, "Марія"), client(2, "Марко"),
            client(3, "Катерина"), client(4, "Андрій"))
        val filtered = ObjectsClientsSearch.clients(source, "мар")
        assertEquals(listOf("Марія", "Марко"), filtered.map { it.name })
        assertEquals(listOf(ClientLetterJump('М', 0)), ClientAlphabetIndex.markers(filtered))
        assertTrue(ClientAlphabetIndex.markers(emptyList()).isEmpty())
        assertTrue(ClientAlphabetIndex.markers(ObjectsClientsSearch.clients(source, "неіснує")).isEmpty())
    }
}
