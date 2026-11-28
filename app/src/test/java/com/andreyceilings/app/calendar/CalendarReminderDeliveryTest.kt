package com.andreyceilings.app.calendar

import com.andreyceilings.app.data.db.ProductionDatabaseGate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class CalendarReminderDeliveryTest {
    @Test fun batchesIdenticalAndAdjacentTriggersAndRefillsAfterWakeWithoutUi() {
        val alerts = List(300) { 100L } + List(200) { 101L } + (102L..280L)
        val scheduled = exactReminderWakeups(alerts, 128)
        assertEquals((100L..227L).toList(), scheduled)
        val atFirstWake = alerts.filter { reminderIsDue(100, it, scheduled = true, relevant = true, workStarted = true) }
        assertEquals(300, atFirstWake.size)
        val atAdjacentWake = alerts.drop(atFirstWake.size).filter {
            reminderIsDue(101, it, scheduled = true, relevant = true, workStarted = true)
        }
        assertEquals(200, atAdjacentWake.size)
        assertEquals((102L..229L).toList(), exactReminderWakeups(alerts.drop(500), 128))
        assertFalse(reminderIsDue(101, 100, scheduled = false, relevant = true, workStarted = true))
        assertFalse(reminderIsDue(101, 100, scheduled = true, relevant = false, workStarted = true))
    }

    @Test fun staleScheduledAlertsDoNotReplayAfterWorkStart() {
        assertTrue(reminderIsDue(100, 100, scheduled = true, relevant = true, workStarted = true))
        assertTrue(reminderIsDue(100 + EXACT_DELIVERY_GRACE_MS, 100, true, true, true))
        assertFalse(reminderIsDue(101 + EXACT_DELIVERY_GRACE_MS, 100, true, true, true))
        assertFalse(reminderIsDue(100, 100, false, true, true))
        assertFalse(reminderIsDue(1_000_000, 100, false, true, false))
        assertTrue(reminderIsDue(1_000_000, 100, true, true, false, sameBoot = false))
        assertFalse(reminderIsDue(100, 100, true, true, true, sameBoot = false))
        assertFalse(reminderIsDue(1_000_000, 100, false, false, false))
    }

    @Test fun creatingOrReschedulingWorkNeverDeliversAnUnplannedPastRule() {
        val now = 9 * 60 * 60_000L + 6 * 60_000L
        val start = 10 * 60 * 60_000L + 10 * 60_000L
        val dayBefore = start - 24 * 60 * 60_000L
        val hourBefore = start - 60 * 60_000L
        assertFalse(reminderIsDue(now, dayBefore, false, true, false))
        assertFalse(reminderIsDue(now, hourBefore, false, true, false))
        assertTrue(reminderIsDue(hourBefore, hourBefore, true, true, false))
        assertFalse(reminderIsDue(hourBefore + 60_000, hourBefore, false, true, false))
    }

    @Test fun groupSummaryIsRetainedWhenOpeningOrDismissingAnyChild() {
        assertFalse(canRemoveReminderSummary(3))
        assertFalse(canRemoveReminderSummary(2))
        assertFalse(canRemoveReminderSummary(1))
        assertTrue(canRemoveReminderSummary(0))
    }

    @Test fun fullTrayUsesOneOverflowBatchWithoutWaitingForDismissal() {
        val due = (1..130).toList()
        val (individual, overflow) = reminderOverflow(due, setOf(1, 2), 0)
        assertEquals(listOf(1, 2), individual)
        assertEquals(due.drop(2), overflow)
        val (withSlots, rest) = reminderOverflow(due, setOf(2), 2)
        assertEquals(listOf(1, 2, 3), withSlots)
        assertEquals(due.drop(3), rest)
        assertEquals(due, reminderOverflow(due, emptySet(), 0).second)
    }

    @Test fun privacyChangeReplacesAnActiveAlertAndDismissalInvalidatesActions() {
        assertEquals(VisibleReminderChange.REPLACE, visibleReminderChange(true, true, false))
        assertEquals(VisibleReminderChange.CANCEL, visibleReminderChange(true, false, false))
        assertEquals(VisibleReminderChange.DISMISSED, visibleReminderChange(false, true, false))
        assertTrue(activeReminderAction(true, "alert", "alert", "alert"))
        assertFalse(activeReminderAction(true, "alert", null, "alert"))
        assertFalse(activeReminderAction(true, "alert", "next", "alert"))
        assertFalse(activeReminderAction(false, "alert", "alert", "alert"))
    }

    @Test fun freshProjectionAndNativeEffectAreOrderedAgainstCommitAndCancellation() {
        val executor = Executors.newFixedThreadPool(2)
        val projected = CountDownLatch(1)
        val writerAttempt = CountDownLatch(1)
        var record: String? = "public"
        var delivered: String? = null
        try {
            val native = executor.submit {
                ProductionDatabaseGate.calendarEffects {
                    val current = record
                    projected.countDown()
                    assertTrue(writerAttempt.await(5, SECONDS))
                    delivered = current
                }
            }
            val writer = executor.submit {
                assertTrue(projected.await(5, SECONDS))
                writerAttempt.countDown()
                ProductionDatabaseGate.read {
                    assertEquals("public", delivered)
                    record = "private"
                }
            }
            native.get(5, SECONDS)
            writer.get(5, SECONDS)
            assertEquals("private", ProductionDatabaseGate.calendarEffects { record })
            ProductionDatabaseGate.read { record = null }
            assertNull(ProductionDatabaseGate.calendarEffects { record })
        } finally {
            executor.shutdownNow()
        }
    }
}
