package com.andreyceilings.app.calendar

internal data class ProviderCalendarEvent(
    val id: Long,
    val calendarId: Long,
    val marker: String?,
    val deleted: Boolean,
)

internal fun matchesOwnedRow(row: ProviderCalendarEvent, calendarId: Long, marker: String): Boolean =
    row.calendarId == calendarId && row.marker == marker

/** A missing row may be repaired, whereas an occupied or ambiguous ID must fail closed. */
internal fun verifiedExportEvent(
    calendarId: Long,
    marker: String,
    mapped: ProviderCalendarEvent?,
    matching: List<ProviderCalendarEvent>,
    pendingInsert: Boolean,
): ProviderCalendarEvent? {
    check(mapped == null || mapped.calendarId == calendarId && mapped.marker == marker)
    check(matching.size <= 1)
    val discovered = matching.singleOrNull()
    val liveMapped = mapped?.takeUnless { it.deleted }
    if (liveMapped != null) check(liveMapped.id == discovered?.id)
    if (discovered != null) {
        check(discovered.calendarId == calendarId && discovered.marker == marker && !discovered.deleted)
        check(discovered.id == liveMapped?.id || pendingInsert)
    }
    return liveMapped ?: discovered
}
