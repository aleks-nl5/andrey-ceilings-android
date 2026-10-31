package com.andreyceilings.shared.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpcomingWorkEventsTest {
    private fun event(id: String, millis: Long) = UpcomingWorkEvent(
        id = id,
        startsAtMillis = millis,
        dateLabel = "12 листопада",
        timeLabel = "10:00",
        description = "Замір",
        projectName = "Тестовий об’єкт"
    )

    @Test
    fun excludesOldCreationDatesAndKeepsOnlyTheNextFourSortedOccurrences() {
        val now = 1_000_000_000L
        val all = listOf(
            event("later", now + 6_000),
            event("old", now - 1),
            event("third", now + 3_000),
            event("first", now),
            event("fifth", now + 8_000),
            event("second", now + 1_000)
        )
        assertEquals(
            listOf("first", "second", "third", "later"),
            upcomingWorkEvents(all, now).map { it.id }
        )
        assertTrue(upcomingWorkEvents(emptyList(), now).isEmpty())
        assertTrue(upcomingWorkEvents(listOf(event("old", now - 1)), now).isEmpty())
    }

    @Test
    fun urgencyBoundariesReflectHoursDaysAndDistantAppointments() {
        val now = 1_000_000_000L
        val hour = 60L * 60L * 1000L
        assertEquals(WorkUrgency.IMMINENT, workUrgency(now, now))
        assertEquals(WorkUrgency.IMMINENT, workUrgency(now, now + 3 * hour))
        assertEquals(WorkUrgency.SOON, workUrgency(now, now + 3 * hour + 1))
        assertEquals(WorkUrgency.SOON, workUrgency(now, now + 24 * hour))
        assertEquals(WorkUrgency.APPROACHING, workUrgency(now, now + 48 * hour))
        assertEquals(WorkUrgency.DISTANT, workUrgency(now, now + 8 * 24 * hour))
    }

    @Test
    fun simultaneousOccurrencesHaveStableTieBreakingById() {
        val now = 1_000_000L
        assertEquals(
            listOf("a", "b"),
            upcomingWorkEvents(listOf(event("b", now + 10), event("a", now + 10)), now)
                .map { it.id }
        )
    }
}
