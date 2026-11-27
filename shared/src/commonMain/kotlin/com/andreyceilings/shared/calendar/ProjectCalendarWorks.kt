package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.resources.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProjectCalendarWorks(
    store: CalendarStore,
    projectUuid: String,
    approvedQuote: Boolean,
    onEdit: (CalendarEditorRoute) -> Unit,
    onOpen: (String) -> Unit,
    swipeState: com.andreyceilings.shared.objects.CardSwipeState,
) {
    val options = calendarOptionsState(store).data()
    val zone = options?.settings?.zone ?: TimeZone.currentSystemDefault()
    val now = calendarNow()
    val today = now.toLocalDateTime(zone).date
    val upcoming = upcomingCalendarState(store, today, projectUuid, 8, now)
    val installation = upcomingCalendarState(store.takeIf { approvedQuote }, today, projectUuid, 1, now, "INSTALL")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.work_rows), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onOpen(projectUuid) }) { Text(stringResource(Res.string.work_all)) }
            IconButton(onClick = { onEdit(CalendarEditorRoute.Create(projectUuid = projectUuid)) }) {
                Icon(Icons.Default.Add, stringResource(Res.string.work_add))
            }
        }
        upcoming.data().orEmpty().filter { it.upcoming(now) }.take(2).forEach { work ->
            CalendarWorkRow(work, options?.types?.firstOrNull { it.code == work.draft.typeCode }, zone, now, onEdit, swipeState = swipeState)
        }
        if (upcoming is CalendarRead.Unavailable) CalendarFeedback(Res.string.work_unavailable)
        if (approvedQuote && installation is CalendarRead.Ready && installation.value.none { it.upcoming(now) }) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onEdit(CalendarEditorRoute.Create(projectUuid = projectUuid, installation = true)) }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.work_schedule_install), Modifier.padding(start = 6.dp))
            }
        }
    }
}
