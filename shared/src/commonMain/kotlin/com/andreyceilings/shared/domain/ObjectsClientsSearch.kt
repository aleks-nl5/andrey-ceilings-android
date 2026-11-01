package com.andreyceilings.shared.domain

/** Lightweight CMP list/search projections. Only current, unlocked SQLDelight data
 * may be mapped into these objects by a platform host; never persist UI search text.
 */
data class ClientListEntry(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String,
    val notes: String,
    val activeProjectCount: Int
)

data class ProjectListEntry(
    val id: Long,
    val title: String,
    val address: String,
    val clientId: Long?,
    val clientName: String,
    val workStatus: String,
    val paymentStatus: String,
    val notes: String
)

data class GlobalSearchResults(
    val projects: List<ProjectListEntry>,
    val clients: List<ClientListEntry>
) {
    val isEmpty get() = projects.isEmpty() && clients.isEmpty()
}

/**
 * Local, cross-entity search. Multiple space-separated words may match different
 * fields, e.g. a client's name + a project's address. Accents are preserved.
 * A phone fragment is matched independent of punctuation and spacing.
 * Do not use fuzzy network/remote lookup for private client records.
 */
object ObjectsClientsSearch {
    fun projects(
        source: List<ProjectListEntry>,
        query: String,
        workFilter: String? = null,
        paymentFilter: String? = null
    ): List<ProjectListEntry> = source.filter { row ->
        (workFilter == null || row.workStatus == workFilter) &&
            (paymentFilter == null || row.paymentStatus == paymentFilter) &&
            matches(
                query,
                row.title, row.address, row.clientName, row.notes,
                ProjectStatusRules.workLabel(row.workStatus),
                ProjectStatusRules.paymentLabel(row.paymentStatus)
            )
    }

    fun clients(source: List<ClientListEntry>, query: String): List<ClientListEntry> =
        source.filter { row ->
            matches(query, row.name, row.phone, row.email, row.notes)
        }

    fun suggestions(
        projectEntries: List<ProjectListEntry>,
        clientEntries: List<ClientListEntry>,
        query: String,
        maxPerType: Int = 4
    ): GlobalSearchResults {
        if (query.isBlank()) return GlobalSearchResults(emptyList(), emptyList())
        val count = maxPerType.coerceIn(1, 20)
        // Suggestions search both sets even if the visible tab is filtered.
        return GlobalSearchResults(
            projects(projectEntries, query).take(count),
            clients(clientEntries, query).take(count)
        )
    }

    private fun matches(query: String, vararg fields: String): Boolean {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val values = fields.map { it.lowercase() }
        val digits = fields.map { field -> field.filter(Char::isDigit) }
        return terms.all { term ->
            values.any { it.contains(term) } ||
                (term.any(Char::isDigit) && term.all(Char::isDigit) &&
                    digits.any { it.contains(term) })
        }
    }
}
