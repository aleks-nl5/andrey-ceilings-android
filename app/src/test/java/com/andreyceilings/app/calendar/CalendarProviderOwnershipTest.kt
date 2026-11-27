package com.andreyceilings.app.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarProviderOwnershipTest {
    private val owned = ProviderCalendarEvent(7, 2, "owned", deleted = false)

    @Test fun exactMappedEventAndInterruptedInsertAreDeduplicated() {
        assertEquals(owned, verify(owned, listOf(owned)))
        assertEquals(owned, verify(null, listOf(owned), pendingInsert = true))
    }

    @Test fun aVerifiedDeletionCanBeRecreatedAndAnotherCalendarCannotBeAdopted() {
        assertNull(verify(owned.copy(deleted = true), emptyList()))
        assertNull(verify(null, emptyList()))
        assertThrows(IllegalStateException::class.java) {
            verify(owned.copy(calendarId = 8), emptyList())
        }
    }

    @Test fun markerLossIdReuseAndDuplicatesDoNotAuthorizeAWritingRepair() {
        assertThrows(IllegalStateException::class.java) { verify(owned.copy(marker = null), emptyList()) }
        assertThrows(IllegalStateException::class.java) { verify(owned, listOf(owned, owned.copy(id = 8))) }
        assertThrows(IllegalStateException::class.java) { verify(null, listOf(owned)) }
        assertThrows(IllegalStateException::class.java) {
            verify(null, listOf(owned.copy(marker = "another-device")), pendingInsert = true)
        }
    }

    private fun verify(mapped: ProviderCalendarEvent?, matching: List<ProviderCalendarEvent>, pendingInsert: Boolean = false) =
        verifiedExportEvent(owned.calendarId, "owned", mapped, matching, pendingInsert)
}
