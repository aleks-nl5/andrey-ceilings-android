package com.andreyceilings.shared.domain

/** A real, visible section in the clients list (zero-based LazyColumn index). */
data class ClientLetterJump(val letter: Char, val listIndex: Int)

/**
 * Phonebook-style index for CMP on every platform. SQLite NOCASE only handles
 * ASCII, so the visible client list must have a deterministic Ukrainian-first
 * ordering before we calculate jumps. Latin names follow Ukrainian ones and
 * non-alphabetic initials are grouped last as '#'.
 *
 * The rail only shows groups that actually exist; selecting a marker can never
 * jump into an absent section.
 */
object ClientAlphabetIndex {
    private const val UKRAINIAN = "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ"
    private const val LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val OTHER_CYRILLIC = "ЁЪЫЭ"
    private const val ALPHABET = UKRAINIAN + OTHER_CYRILLIC + LATIN

    private fun initial(name: String): Char {
        val first = name.trim().firstOrNull()?.uppercaseChar() ?: return '#'
        return first.takeIf { it in ALPHABET } ?: '#'
    }

    private fun order(letter: Char): Int {
        val index = ALPHABET.indexOf(letter)
        return if (index < 0) ALPHABET.length else index
    }

    // Compare every character, not just the first one: the natural Unicode
    // order would incorrectly put "Марко" before "Марія" (І precedes К in
    // Ukrainian). Unrecognized punctuation sorts after recognized letters.
    private fun compareNames(left: String, right: String): Int {
        val a = left.trim().uppercase()
        val b = right.trim().uppercase()
        for (i in 0 until minOf(a.length, b.length)) {
            val rankA = order(a[i])
            val rankB = order(b[i])
            if (rankA != rankB) return rankA.compareTo(rankB)
            if (rankA == ALPHABET.length && a[i] != b[i]) {
                return a[i].compareTo(b[i])
            }
        }
        return a.length.compareTo(b.length)
    }

    fun sorted(clients: List<ClientListEntry>): List<ClientListEntry> =
        clients.sortedWith(Comparator { a, b ->
            val order = compareNames(a.name, b.name)
            if (order != 0) order else a.id.compareTo(b.id)
        })

    /** The input is the exact sorted, filtered list rendered by the CMP screen. */
    fun markers(visibleClients: List<ClientListEntry>): List<ClientLetterJump> {
        var previous: Char? = null
        return buildList {
            visibleClients.forEachIndexed { index, client ->
                val letter = initial(client.name)
                if (letter != previous) {
                    add(ClientLetterJump(letter, index))
                    previous = letter
                }
            }
        }
    }
}
