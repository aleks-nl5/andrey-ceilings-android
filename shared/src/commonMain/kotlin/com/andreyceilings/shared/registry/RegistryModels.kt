package com.andreyceilings.shared.registry

import com.andreyceilings.shared.db.ActiveCeilings
import com.andreyceilings.shared.db.ConsolidatedVersions
import com.andreyceilings.shared.db.IndividualVersions
import com.andreyceilings.shared.estimate.EstimateWorkflowStatus
import com.andreyceilings.shared.objects.CeilingQuoteSummary
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class CeilingQuoteKind { DRAFT, SENT, APPROVED, REJECTED, NONE, UNAVAILABLE }
internal data class CeilingFinance(
    val kind: CeilingQuoteKind,
    val quote: CeilingQuoteSummary?,
    val numbers: List<String>
)

internal enum class RegistrySort { LATEST, CREATED, MODIFIED, NAME, NUMBER, ADDRESS, AREA, COST }

internal data class RegistryFilter(
    val query: String = "",
    val statuses: Set<String> = emptySet(),
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val projectId: Long? = null,
    val consolidated: Boolean? = null,
    val sort: RegistrySort,
    val ascending: Boolean = false
) {
    fun acceptsDate(epoch: Long, zone: TimeZone): Boolean {
        if (from == null && through == null) return true
        val date = dateOf(epoch, zone)
        return (from == null || date >= from) && (through == null || date <= through)
    }
}

internal enum class RegistryPeriod { ALL, TODAY, LAST_7_DAYS, MONTH, YEAR }

internal fun registryPeriod(period: RegistryPeriod, today: LocalDate): Pair<LocalDate?, LocalDate?> =
    when (period) {
        RegistryPeriod.ALL -> null to null
        RegistryPeriod.TODAY -> today to today
        RegistryPeriod.LAST_7_DAYS -> today.minus(DatePeriod(days = 6)) to today
        RegistryPeriod.MONTH -> LocalDate(today.year, today.monthNumber, 1) to today
        RegistryPeriod.YEAR -> LocalDate(today.year, 1, 1) to today
    }

internal data class RegistryRevision(
    val id: String,
    val version: Long,
    val createdAt: Long,
    val title: String?,
    val status: EstimateWorkflowStatus?,
    val amount: Long?,
    val ceilingCount: Long? = null
) {
    val filterStatus: String get() = if (amount == null || status == null) CeilingQuoteKind.UNAVAILABLE.name else status.name
}

internal data class RegistrySeries(
    val id: String,
    val consolidated: Boolean,
    val projectId: Long,
    val number: String?,
    val createdAt: Long,
    val address: String,
    val projectTitle: String,
    val ceilingName: String?,
    val clientName: String?,
    val phone: String?,
    val versions: List<RegistryRevision>
)

internal fun quoteSeries(individuals: List<IndividualVersions>, consolidated: List<ConsolidatedVersions>): List<RegistrySeries> =
    individuals.groupBy { it.seriesUuid }.map { (id, rows) ->
        val first = rows.first()
        RegistrySeries(id, false, first.projectId, first.publicNumber, first.seriesCreatedAt,
            first.address.ifBlank { first.projectTitle }, first.projectTitle, first.ceilingName, first.clientName, first.phone,
            rows.map {
                val status = workflow(it.status)
                val amount = it.finalCents.takeIf { value -> value >= 0 && it.currency == "UAH" &&
                    status != null && !it.title.isNullOrBlank() }
                RegistryRevision(it.revisionUuid, it.versionNumber, it.createdAt, it.title, status, amount)
            })
    } + consolidated.groupBy { it.seriesUuid }.map { (id, rows) ->
        val first = rows.first()
        RegistrySeries(id, true, first.projectId, first.publicNumber, first.seriesCreatedAt ?: 0L,
            first.address.ifBlank { first.projectTitle }, first.projectTitle, null, first.clientName, first.phone,
            rows.map {
                val status = workflow(it.status)
                val count = it.ceilingCount.takeIf { value -> value > 0 }
                val amount = it.finalCents.takeIf { value -> value >= 0 && status != null &&
                    !it.publicNumber.isNullOrBlank() && count != null && first.seriesCreatedAt != null }
                RegistryRevision(it.revisionUuid, it.versionNumber, it.createdAt, it.title, status, amount, count)
            })
    }

private fun workflow(status: String?) = EstimateWorkflowStatus.entries.firstOrNull { it.name == status }

internal fun dateOf(time: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(time).toLocalDateTime(zone).date

private fun String.matchesFields(vararg values: String?): Boolean =
    isBlank() || values.any { it?.contains(this, ignoreCase = true) == true }

private fun String.matchesPhone(phone: String?): Boolean {
    val digits = filter(Char::isDigit)
    return digits.isNotEmpty() && all { it.isDigit() || it in " +()-." } &&
        phone?.filter(Char::isDigit)?.contains(digits) == true
}

internal fun filterQuoteRegistry(
    series: List<RegistrySeries>,
    filter: RegistryFilter,
    zone: TimeZone = TimeZone.currentSystemDefault()
): List<RegistrySeries> {
    val query = filter.query.trim()
    return series.mapNotNull { row ->
        if (filter.consolidated != null && row.consolidated != filter.consolidated) return@mapNotNull null
        val sharedMatch = query.matchesFields(row.number, row.address, row.projectTitle, row.clientName, row.ceilingName, row.phone) ||
            query.matchesPhone(row.phone)
        val versions = row.versions.filter { version ->
            (sharedMatch || query.matchesFields(version.title)) &&
                (filter.statuses.isEmpty() || version.filterStatus in filter.statuses) &&
                filter.acceptsDate(version.createdAt, zone)
        }.sortedByDescending { it.version }
        row.copy(versions = versions).takeIf { versions.isNotEmpty() }
    }.sortedWith(
        when (filter.sort) {
            RegistrySort.CREATED -> compareBy<RegistrySeries> { it.createdAt }
            RegistrySort.NUMBER -> compareBy { it.number.orEmpty().lowercase() }
            RegistrySort.ADDRESS -> compareBy { it.address.lowercase() }
            else -> compareBy { it.versions.maxOf { revision -> revision.createdAt } }
        }.direction(filter.ascending).thenByDescending { it.createdAt }.thenBy { it.id }
    )
}

internal fun filterCeilingRegistry(
    rows: List<ActiveCeilings>,
    finances: Map<Long, CeilingFinance>?,
    filter: RegistryFilter,
    zone: TimeZone = TimeZone.currentSystemDefault()
): List<ActiveCeilings> {
    val query = filter.query.trim()
    val primary = when (filter.sort) {
        RegistrySort.MODIFIED -> compareBy<ActiveCeilings> { it.updatedAt }.direction(filter.ascending)
        RegistrySort.NAME -> compareBy<ActiveCeilings> { it.name.lowercase() }.direction(filter.ascending)
        RegistrySort.ADDRESS -> compareBy<ActiveCeilings> { it.address.ifBlank { it.projectTitle }.lowercase() }.direction(filter.ascending)
        RegistrySort.AREA -> compareBy<ActiveCeilings> { it.areaSquareMeters }.direction(filter.ascending)
        RegistrySort.COST -> Comparator { a: ActiveCeilings, b: ActiveCeilings ->
            val left = finances?.get(a.id)?.quote?.finalCents
            val right = finances?.get(b.id)?.quote?.finalCents
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                filter.ascending -> left.compareTo(right)
                else -> right.compareTo(left)
            }
        }
        else -> compareBy<ActiveCeilings> { it.createdAt }.direction(filter.ascending)
    }
    return rows.filter { row ->
        val finance = finances?.get(row.id)
        val kind = if (finances == null) CeilingQuoteKind.UNAVAILABLE else finance?.kind ?: CeilingQuoteKind.NONE
        (filter.projectId == null || filter.projectId == row.projectId) &&
            (filter.statuses.isEmpty() || kind.name in filter.statuses) &&
            filter.acceptsDate(row.createdAt, zone) &&
            (query.matchesFields(row.name, row.projectTitle, row.address, row.clientName, row.phone) ||
                query.matchesPhone(row.phone) || finance?.numbers?.any { query.matchesFields(it) } == true)
    }.sortedWith(primary.thenByDescending { it.createdAt }.thenBy { it.uuid })
}

private fun <T> Comparator<T>.direction(ascending: Boolean): Comparator<T> = if (ascending) this else reversed()
