package com.andreyceilings.app.calendar

import android.content.Context
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.db.ProductionDatabaseGate
import com.andreyceilings.shared.calendar.CalendarStore

internal fun <T> withCalendarEffects(
    context: Context,
    isolatedStore: CalendarStore? = null,
    effect: CalendarStore.IntegrationAccess.() -> T,
): T = ProductionDatabaseGate.calendarEffects {
    val store = isolatedStore ?: CeilingsDatabase.get(context).objectsClientsStore.calendar
    store.atIntegrationBoundary(effect)
}
