package com.andreyceilings.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.andreyceilings.app.calendar.AndroidSystemCalendar
import com.andreyceilings.app.calendar.CalendarDeviceState
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.calendar.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarProviderIntegrationTest {
    @get:Rule val access: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    @Test fun localProviderDeduplicatesOwnedExportAndPreservesUnprovedEvents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = UUID.randomUUID().toString()
        val dbName = "sqldelight_test_calendar_$token.db"
        val preferences = "calendar_provider_test_$token"
        val key = ByteArray(32) { 47 }
        val device = CalendarDeviceState(context, preferences)
        val resolver = context.contentResolver
        var calendarId: Long? = null
        try {
            CeilingsDatabase.openIsolatedForTesting(context, dbName, key).use { database ->
                val store = database.objectsClientsStore.calendar
                val system = AndroidSystemCalendar(context, device, store)
                val calendar = system.createLocalCalendar("libellaro-test-$token")
                val id = calendar.substringBefore(':').toLong()
                calendarId = id
                system.selectDestination(calendar, move = false)
                system.selectBusy(setOf(calendar))
                val date = (Clock.System.now() + 10.days).toLocalDateTime(TimeZone.UTC).date
                val work = CalendarWorkDraft(date, WorkSchedule(WorkTimeMode.ALL_DAY, TimeZone.UTC,
                    listOf(WorkDay(0))), "Private fixture title", "MEASURE", notes = "Private fixture notes")
                val created = store.save(CalendarSave(work)) as CalendarSaveResult.Saved
                val saved = checkNotNull(store.exact(created.key))
                system.manualExport(saved.key, saved.version, managed = true)
                system.manualExport(saved.key, saved.version, managed = true)
                fun events() = checkNotNull(resolver.query(Events.CONTENT_URI,
                    arrayOf(Events._ID, Events.TITLE, Events.DESCRIPTION, Events.DTSTART, Events.DTEND),
                    "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(id.toString()), null)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                            cursor.getString(3), cursor.getString(4)))
                    }
                }
                val exported = events().single()
                assertNotEquals(work.title, exported[1])
                assertNull(exported[2])
                val thirdParty = checkNotNull(resolver.insert(Events.CONTENT_URI, ContentValues().apply {
                    put(Events.CALENDAR_ID, id)
                    put(Events.TITLE, "Unrelated provider event")
                    put(Events.DTSTART, exported[3]!!.toLong())
                    put(Events.DTEND, exported[4]!!.toLong())
                    put(Events.EVENT_TIMEZONE, "UTC")
                    put(Events.ALL_DAY, 1)
                    put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                }))
                val availability = system.availability(WorkDateRange(date, date), TimeZone.UTC) as CalendarAvailability.Available
                assertEquals(1, availability.busy.size)

                val eventUri = ContentUris.withAppendedId(Events.CONTENT_URI, exported[0]!!.toLong())
                assertEquals(1, resolver.update(eventUri, ContentValues().apply {
                    put(Events.TITLE, "External edit")
                }, null, null))
                system.reconcile()
                assertEquals(exported[1], events().single { it[0] == exported[0] }[1])

                val afterExport = checkNotNull(store.exact(saved.key))
                assertTrue(store.delete(afterExport.key, afterExport.version, CalendarEditScope.OCCURRENCE))
                system.reconcile()
                assertEquals(ContentUris.parseId(thirdParty).toString(), events().single()[0])
            }
        } finally {
            calendarId?.let { id ->
                val delete = ContentUris.withAppendedId(Calendars.CONTENT_URI, id).buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(Calendars.ACCOUNT_NAME, context.packageName)
                    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
                resolver.delete(delete, null, null)
            }
            context.deleteSharedPreferences(preferences)
            context.deleteDatabase(dbName)
            key.fill(0)
        }
    }
}
