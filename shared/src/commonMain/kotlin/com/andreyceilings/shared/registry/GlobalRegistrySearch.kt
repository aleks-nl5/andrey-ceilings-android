package com.andreyceilings.shared.registry

import com.andreyceilings.shared.db.ActiveCeilings
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ObjectsClientsSearch
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.objects.ObjectsClientsRows

/** Identity-only selections. No document payloads or client records in navigation. */
sealed interface GlobalRegistryTarget {
    data class Project(val id: Long) : GlobalRegistryTarget
    data class Client(val id: Long) : GlobalRegistryTarget
    data class Ceiling(val projectId: Long, val id: Long) : GlobalRegistryTarget
    data class Quote(val projectId: Long, val seriesId: String, val revisionId: String,
        val consolidated: Boolean) : GlobalRegistryTarget
}

internal data class GlobalRegistryMatches(
    val projects: List<ProjectListEntry> = emptyList(),
    val clients: List<ClientListEntry> = emptyList(),
    val ceilings: List<ActiveCeilings> = emptyList(),
    val quotes: List<Pair<RegistrySeries, RegistryRevision>> = emptyList()
) {
    val isEmpty get() = projects.isEmpty() && clients.isEmpty() && ceilings.isEmpty() && quotes.isEmpty()
}

/** Reuse registry matching semantics, without inheriting filters from any screen.
 * In particular, a quote-title hit returns only the actual matching revisions. */
internal fun globalRegistryMatches(
    query: String, objects: ObjectsClientsRows?, ceilings: List<ActiveCeilings>?,
    finance: Map<Long, CeilingFinance>?, quotes: List<RegistrySeries>?
): GlobalRegistryMatches {
    if (query.isBlank()) return GlobalRegistryMatches()
    return GlobalRegistryMatches(
        projects = ObjectsClientsSearch.projects(objects?.projects.orEmpty(), query),
        clients = ObjectsClientsSearch.clients(objects?.clients.orEmpty(), query),
        ceilings = filterCeilingRegistry(ceilings.orEmpty(), finance, RegistryFilter(query = query, sort = RegistrySort.CREATED)),
        quotes = filterQuoteRegistry(quotes.orEmpty(), RegistryFilter(query = query, sort = RegistrySort.LATEST))
            .flatMap { series -> series.versions.map { series to it } }
    )
}
