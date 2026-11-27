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
