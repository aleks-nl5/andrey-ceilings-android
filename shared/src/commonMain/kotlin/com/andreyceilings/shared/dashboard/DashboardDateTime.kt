package com.andreyceilings.shared.dashboard

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val ukrainianGenitiveMonths = listOf(
    "січня", "лютого", "березня", "квітня", "травня", "червня",
    "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"
)

/** Epoch millis are common on all platforms; timestamps in monobank JSON are seconds. */
fun dashboardClockMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Local device calendar date, equivalent to the old uk-UA Android home label. */
fun dashboardTodayLabel(
    millis: Long = dashboardClockMillis(),
    zone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    return "${local.day} ${ukrainianGenitiveMonths[local.monthNumber - 1]} ${local.year}"
}

/** Local wall-clock time with stable Ukrainian dd.MM, HH:mm formatting. */
fun currencyLocalDateTimeLabel(
    millis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault()
): String {
    if (millis <= 0L) return "—"
    val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    fun Int.twoDigits() = toString().padStart(2, '0')
    return "${local.day.twoDigits()}.${local.monthNumber.twoDigits()}, " +
        "${local.hour.twoDigits()}:${local.minute.twoDigits()}"
}
