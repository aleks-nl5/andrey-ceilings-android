package com.andreyceilings.shared.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.resources.*
import com.andreyceilings.shared.ui.ContextFeedbackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun CalendarAndNotificationSettings(
    store: CalendarStore?, showWeekNumbers: Boolean, onShowWeekNumbersChange: ((Boolean) -> Unit)?,
) {
    var open by remember(store) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = !open }, enabled = store != null, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.CalendarMonth, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.p7b_settings))
    }
    if (!open || store == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CalendarSettingsSection(store, showWeekNumbers, onShowWeekNumbersChange, nested = true)
        CalendarIntegrationSettings(store)
    }
}

@Composable
private fun CalendarIntegrationSettings(store: CalendarStore) {
    val platform = LocalCalendarIntegrationPlatform.current
    val options = calendarOptionsState(store).data()
    val snapshot = integrationSnapshotState(store)
    val coroutine = rememberCoroutineScope()
    var page by remember { mutableStateOf<StringResource?>(null) }
    var failure by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var disable by remember { mutableStateOf(false) }
    var moveTo by remember { mutableStateOf<String?>(null) }
    var typeEditor by remember { mutableStateOf<CalendarWorkType?>(null) }
    val choices by produceState(emptyList<CalendarDestinationChoice>(), platform?.revision, page) {
        if (platform != null && page == Res.string.p7b_system) {
            try { value = platform.choices() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
        }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = false
        coroutine.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
            finally { busy = false }
        }
    }

    for (label in listOf(Res.string.p7b_reminders, Res.string.p7b_system)) {
        OutlinedButton(onClick = { page = if (page == label) null else label; failure = false },
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
        if (page != label) continue
        if (platform == null) {
            CalendarFeedback(Res.string.p7b_unsupported, ContextFeedbackKind.INFO)
            continue
        }
        if (snapshot == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            continue
        }
        val settings = snapshot.settings
        if (platform.restorePending) {
            CalendarFeedback(Res.string.p7b_restore_pending, ContextFeedbackKind.WARNING)
            TextButton(onClick = { perform { platform.confirmRestore() } }, enabled = !busy) {
                Text(stringResource(Res.string.p7b_confirm_restore))
            }
        }
        if (label == Res.string.p7b_reminders) {
            IntegrationSwitch(Res.string.p7b_reminders, settings.remindersEnabled, !busy) { enabled ->
                perform {
                    store.updateIntegrationSettings { it.copy(remindersEnabled = enabled) }
                    platform.remindersChanged()
                }
            }
            if (!platform.remindersReady) {
                CalendarFeedback(Res.string.p7b_reminder_access_unavailable, ContextFeedbackKind.WARNING)
                TextButton(onClick = platform::requestReminders, enabled = !busy) {
                    Text(stringResource(Res.string.p7b_allow_reminders))
                }
            }
            Text(stringResource(if (platform.exactAlarmsGranted) Res.string.p7b_exact_allowed else Res.string.p7b_exact_denied),
                style = MaterialTheme.typography.bodyMedium)
            if (!platform.exactAlarmsGranted) TextButton(onClick = platform::requestExactAlarms) {
                Text(stringResource(Res.string.p7b_exact_open))
            }
            IntegrationSwitch(Res.string.p7b_lock_full, !settings.privateLockscreen, !busy) { full ->
                perform {
                    store.updateIntegrationSettings { it.copy(privateLockscreen = !full) }
                    platform.remindersChanged()
                }
            }
            options?.types.orEmpty().forEach { type ->
                TextButton(onClick = { typeEditor = type }, enabled = !busy) { Text(calendarTypeLabel(type)) }
            }
        } else {
            Text(stringResource(Res.string.p7b_free_busy), style = MaterialTheme.typography.titleSmall)
            if (!platform.readCalendarGranted) TextButton(onClick = { platform.requestCalendarAccess(false) }) {
                Text(stringResource(Res.string.p7b_allow_read))
            }
            (platform.busySelection - choices.map { it.identity }.toSet()).takeIf { it.isNotEmpty() }?.let {
                CalendarFeedback(Res.string.p7b_busy_unknown, ContextFeedbackKind.WARNING)
                TextButton(enabled = !busy, onClick = { perform { platform.selectBusy(emptySet()) } }) {
                    Text(stringResource(Res.string.p7b_clear_busy))
                }
            }
            choices.forEach { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(choice.identity in platform.busySelection, onCheckedChange = {
                        val selected = if (it) platform.busySelection + choice.identity else platform.busySelection - choice.identity
                        perform { platform.selectBusy(selected) }
                    }, enabled = !busy)
                    Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            if (!platform.writeCalendarGranted) TextButton(onClick = { platform.requestCalendarAccess(true) }) {
                Text(stringResource(Res.string.p7b_allow_write))
            }
            val current = choices.firstOrNull { it.identity == platform.destination }
            CalendarMenu(current?.label ?: stringResource(Res.string.p7b_destination), fillWidth = true) {
                choices.filter { it.writable }.forEach { choice ->
                    CalendarMenuItem(text = { Text(choice.label) }, onClick = {
                        if (choice.identity != platform.destination) {
                            if (platform.destination == null) perform { platform.selectDestination(choice.identity, false) }
                            else moveTo = choice.identity
                        }
                    })
                }
                if (platform.writeCalendarGranted && !platform.restorePending) {
                    CalendarMenuItem(text = { Text(stringResource(Res.string.p7b_create_destination)) }, onClick = {
                        perform {
                            val target = platform.createDestination()
                            if (platform.destination == null) platform.selectDestination(target, false)
                            else if (target != platform.destination) moveTo = target
                        }
                    })
                }
            }
            IntegrationSwitch(Res.string.p7b_sync, settings.syncEnabled, !busy &&
                (settings.syncEnabled || platform.writeCalendarGranted && current?.writable == true && !platform.restorePending)) {
                if (it) perform { platform.enableSync() } else disable = true
            }
            if (platform.failed || platform.problems.isNotEmpty() || platform.destination != null && current == null) {
                CalendarFeedback(syncFailureLabel(platform.failureReason), ContextFeedbackKind.WARNING)
                TextButton(onClick = platform::retry, enabled = !platform.retrying) {
                    Text(stringResource(if (platform.retrying) Res.string.p7b_sync_retrying else Res.string.work_reload))
                }
            } else if (settings.syncEnabled) {
                Text(stringResource(Res.string.p7b_sync_enabled), style = MaterialTheme.typography.labelMedium)
            }
            options?.types.orEmpty().forEach { type ->
                val rules = snapshot.types[type.code] ?: CalendarTypeIntegration()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(calendarTypeLabel(type), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(rules.syncEnabled, onCheckedChange = {
                        perform { store.saveTypeIntegration(type.code, rules.copy(syncEnabled = it)) }
                    }, enabled = !busy)
                }
            }
            Text(stringResource(Res.string.p7b_export_fields), style = MaterialTheme.typography.titleSmall)
            CalendarExportField.entries.forEach { field ->
                IntegrationSwitch(field.label(), field in settings.exportFields, !busy) {
                    val selected = it
                    perform {
                        store.updateIntegrationSettings { current ->
                            val fields = if (selected) current.exportFields + field else current.exportFields - field
                            current.copy(exportFields = fields)
                        }
                    }
                }
            }
        }
        if (failure) CalendarFeedback(Res.string.p7b_action_failed)
    }
    if (disable && platform != null) AlertDialog(
        onDismissRequest = { disable = false },
        title = { Text(stringResource(Res.string.p7b_disable_sync)) },
        dismissButton = { TextButton(onClick = { disable = false; perform { platform.disableSync(true) } }) {
            Text(stringResource(Res.string.p7b_remove_exports), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { disable = false; perform { platform.disableSync(false) } }) {
            Text(stringResource(Res.string.p7b_leave_exports))
        } },
    )
    moveTo?.takeIf { platform != null }?.let { target ->
        AlertDialog(
            onDismissRequest = { moveTo = null },
            title = { Text(stringResource(Res.string.p7b_destination)) },
            dismissButton = { TextButton(onClick = {
                moveTo = null
                perform { checkNotNull(platform).selectDestination(target, true) }
            }) { Text(stringResource(Res.string.work_move)) } },
            confirmButton = { TextButton(onClick = {
                moveTo = null
                perform { checkNotNull(platform).selectDestination(target, false) }
            }) { Text(stringResource(Res.string.p7b_leave_exports)) } },
        )
    }
    val selectedType = typeEditor
    if (selectedType != null && snapshot != null) {
        val current = snapshot.types[selectedType.code] ?: CalendarTypeIntegration()
        var rules by remember(selectedType) { mutableStateOf(current.reminders ?: standardReminderRules) }
        var valid by remember(selectedType) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { if (!busy) typeEditor = null },
            title = { Text(calendarTypeLabel(selectedType)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                ReminderRuleFields(rules, emptyList(), dayTimeMode = null, onValid = { valid = it }) { rules = it }
                TextButton(onClick = { rules = standardReminderRules }) { Text(stringResource(Res.string.p7b_standard_rules)) }
            } },
            dismissButton = { TextButton(onClick = { typeEditor = null }) { Text(stringResource(Res.string.p5d_cancel)) } },
            confirmButton = { TextButton(enabled = !busy && valid, onClick = {
                typeEditor = null
                perform { store.saveTypeIntegration(selectedType.code,
                    current.copy(reminders = rules.takeUnless { it == standardReminderRules })) }
            }) { Text(stringResource(Res.string.oc_save)) } },
        )
    }
}

@Composable
internal fun integrationSnapshotState(store: CalendarStore): CalendarIntegrationSnapshot? {
    val read by produceState<CalendarIntegrationSnapshot?>(null, store) {
        store.integrationChanges.collect {
            try { value = store.integrationSnapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
    }
    return read
}

@Composable
private fun IntegrationSwitch(label: StringResource, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun WorkIntegrationControls(
    base: CalendarWorkIntegration, value: CalendarWorkIntegration, mode: WorkTimeMode, days: List<Int>,
    defaults: Set<ReminderRule>, setValidation: (Boolean) -> Unit, change: (CalendarWorkIntegration) -> Unit,
) {
    val reminder = value.reminderMode ?: base.reminderMode ?: ReminderMode.INHERIT
    val customRules = if (value.reminderMode != null) value.reminders else base.reminders
    val effective = effectiveReminderRules(defaults, if (value.reminderMode != null) value else base)
    val timed = mode == WorkTimeMode.TIMED
    val multiple = days.size > 1
    val applicable = effective.filterTo(mutableSetOf()) { rule ->
        when (rule.day) {
            ReminderDay.FIRST -> timed && 0 in days
            ReminderDay.LATER -> timed && days.any { it != 0 }
            ReminderDay.DATE -> !timed && days.isNotEmpty()
            ReminderDay.DAY -> rule.offset in days
        }
    }
    val dayKinds = buildList {
        if (timed) {
            add(ReminderDay.FIRST to -1)
            if (multiple) add(ReminderDay.LATER to -1)
        } else add(ReminderDay.DATE to -1)
        if (multiple) days.forEach { add(ReminderDay.DAY to it) }
    }
    var selected by remember(mode, days) { mutableStateOf(dayKinds.first()) }
    var custom by remember { mutableStateOf(false) }
    fun replace(rules: Set<ReminderRule>) {
        setValidation(true)
        change(value.copy(reminderMode = if (rules.isEmpty()) ReminderMode.OFF else ReminderMode.REPLACE,
            reminders = rules))
    }
    fun add(rule: ReminderRule) {
        // Adding to inherited rules keeps inheritance live; a removal creates an explicit replacement.
        if (rule in effective) return
        setValidation(true)
        if (reminder == ReminderMode.INHERIT || reminder == ReminderMode.ADD) {
            change(value.copy(reminderMode = ReminderMode.ADD,
                reminders = (if (reminder == ReminderMode.ADD) customRules else emptySet()) + rule))
        } else replace(effective + rule)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Notifications, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.p7b_reminders), style = MaterialTheme.typography.titleSmall)
    }
    for (rule in applicable.sortedWith(compareBy({ it.day }, { it.offset }, { -it.leadDays }, { -it.minute }))) {
        Row(Modifier.fillMaxWidth().padding(start = 27.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(workReminderLabel(rule, timed, multiple), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { replace(effective - rule) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Close, stringResource(Res.string.oc_delete), modifier = Modifier.size(20.dp))
            }
        }
    }
    if (applicable.isEmpty()) Text(stringResource(Res.string.p7b_off),
        Modifier.padding(start = 27.dp, top = 4.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (multiple) CalendarMenu(reminderTargetLabel(selected), fillWidth = true) {
        dayKinds.forEach { target ->
            CalendarMenuItem(text = { Text(reminderTargetLabel(target)) }, onClick = { selected = target })
        }
    }
    val presets = if (timed) listOf(0 to 5, 0 to 10, 0 to 15, 0 to 30, 0 to 60, 0 to 120, 1 to 0, 2 to 0)
        else listOf(1 to 18 * 60, 1 to 8 * 60, 0 to 8 * 60, 0 to 9 * 60, 0 to 12 * 60)
    CalendarMenu(stringResource(Res.string.p7b_add_reminder), fillWidth = true,
        leading = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }) {
        for ((lead, minute) in presets) {
            val rule = ReminderRule(selected.first, lead, minute, selected.second)
            if (rule !in effective) CalendarMenuItem(text = { Text(workReminderLabel(rule, timed, false)) },
                onClick = { add(rule) })
        }
        CalendarMenuItem(text = { Text(stringResource(Res.string.p7b_custom_reminder)) }, onClick = { custom = true })
    }
    if (reminder != ReminderMode.INHERIT) TextButton(onClick = {
        setValidation(true)
        change(value.copy(reminderMode = ReminderMode.INHERIT, reminders = emptySet()))
    }) { Text(stringResource(Res.string.p7b_reset_reminders)) }
    if (custom) {
        var lead by remember { mutableStateOf("0") }
        var minutes by remember { mutableStateOf("60") }
        var time by remember { mutableStateOf("08:00") }
        val rule = runCatching { ReminderRule(selected.first, lead.toInt(),
            if (timed) minutes.toInt() else LocalTime.parse(time).toSecondOfDay() / 60, selected.second) }.getOrNull()
        AlertDialog(onDismissRequest = { custom = false },
            title = { Text(stringResource(Res.string.p7b_custom_reminder)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (multiple) Text(reminderTargetLabel(selected))
                OutlinedTextField(lead, { lead = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(Res.string.p7b_days_before)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (timed) OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(Res.string.p7b_minutes_before)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                else WorkTimeField(time, Res.string.p7b_reminder_time) { time = it }
            } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(Res.string.p5d_cancel)) } },
            confirmButton = { TextButton(enabled = rule != null && rule !in effective, onClick = {
                rule?.let(::add)
                custom = false
            }) { Text(stringResource(Res.string.p7b_add_reminder)) } })
    }
    val sync = value.syncMode ?: base.syncMode ?: CalendarSyncMode.INHERIT
    Text(stringResource(Res.string.p7b_sync), style = MaterialTheme.typography.labelMedium)
    CalendarMenu(stringResource(sync.label()), fillWidth = true) {
        CalendarSyncMode.entries.forEach { syncMode ->
            CalendarMenuItem(text = { Text(stringResource(syncMode.label())) }, onClick = { change(value.copy(syncMode = syncMode)) })
        }
    }
}

@Composable
private fun reminderTargetLabel(target: Pair<ReminderDay, Int>): String =
    if (target.first == ReminderDay.DAY) stringResource(Res.string.p7b_day_number, target.second + 1)
    else stringResource(target.first.label())

@Composable
private fun workReminderLabel(rule: ReminderRule, timed: Boolean, showDay: Boolean): String {
    val time = if (timed) {
        val parts = buildList {
            if (rule.leadDays > 0) add(stringResource(Res.string.p7b_duration_days, rule.leadDays))
            if (rule.minute >= 60) add(stringResource(Res.string.p7b_duration_hours, rule.minute / 60))
            if (rule.minute % 60 != 0 || rule.leadDays == 0 && rule.minute == 0)
                add(stringResource(Res.string.p7b_minutes_count, rule.minute % 60))
        }
        stringResource(Res.string.p7b_before, parts.joinToString(" "))
    } else {
        val clock = (rule.minute / 60).toString().padStart(2, '0') + ":" + (rule.minute % 60).toString().padStart(2, '0')
        when (rule.leadDays) {
            0 -> stringResource(Res.string.p7b_on_day_at, clock)
            1 -> stringResource(Res.string.p7b_previous_day_at, clock)
            else -> stringResource(Res.string.p7b_days_before_at, rule.leadDays, clock)
        }
    }
    return if (showDay) reminderTargetLabel(rule.day to rule.offset) + " · " + time else time
}

@Composable
private fun ReminderRuleFields(rules: Set<ReminderRule>, days: List<Int>, dayTimeMode: WorkTimeMode?,
    onValid: (Boolean) -> Unit, change: (Set<ReminderRule>) -> Unit) {
    var kind by remember { mutableStateOf(ReminderDay.FIRST) }
    var offset by remember { mutableIntStateOf(-1) }
    var lead by remember { mutableStateOf("0") }
    var minute by remember { mutableStateOf("60") }
    var timeOfDay by remember { mutableStateOf("08:00") }
    fun localTime(day: ReminderDay) = day == ReminderDay.DATE ||
        day == ReminderDay.DAY && dayTimeMode != WorkTimeMode.TIMED
    val typed = runCatching {
        val amount = if (localTime(kind)) LocalTime.parse(timeOfDay).toSecondOfDay() / 60 else minute.toInt()
        ReminderRule(kind, lead.toInt(), amount, offset)
    }.getOrNull()
    val incomplete = typed == null
    LaunchedEffect(incomplete) { onValid(!incomplete) }
    rules.sortedWith(compareBy({ it.day }, { it.offset }, { it.leadDays }, { it.minute })).forEach { rule ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val day = if (rule.day == ReminderDay.DAY) (rule.offset + 1).toString() else stringResource(rule.day.label())
            val timing = if (localTime(rule.day)) {
                (rule.minute / 60).toString().padStart(2, '0') + ":" + (rule.minute % 60).toString().padStart(2, '0')
            } else stringResource(Res.string.p7b_minutes_count, rule.minute)
            Text(stringResource(Res.string.p7b_rule_summary, day, rule.leadDays, timing), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { change(rules - rule) }) { Icon(Icons.Default.Delete, stringResource(Res.string.oc_delete)) }
        }
    }
    CalendarMenu(if (offset < 0) stringResource(kind.label()) else stringResource(Res.string.p7b_day_number, offset + 1)) {
        for (item in listOf(ReminderDay.FIRST, ReminderDay.LATER, ReminderDay.DATE)) {
            CalendarMenuItem(text = { Text(stringResource(item.label())) }, onClick = {
                kind = item
                offset = -1
            })
        }
        for (day in days) CalendarMenuItem(text = { Text(stringResource(Res.string.p7b_day_number, day + 1)) }, onClick = {
            kind = ReminderDay.DAY
            offset = day
        })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(lead, { lead = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
            label = { Text(stringResource(Res.string.p7b_days_before)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (localTime(kind)) {
            WorkTimeField(timeOfDay, Res.string.p7b_reminder_time, Modifier.weight(1f)) { timeOfDay = it }
        } else {
            OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f),
                label = { Text(stringResource(Res.string.p7b_minutes_before)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        IconButton(onClick = { typed?.let { change(rules + it) } }, enabled = typed != null && typed !in rules) {
            Icon(Icons.Default.Add, stringResource(Res.string.p7b_add_rule))
        }
    }
}

private fun CalendarExportField.label() = when (this) {
    CalendarExportField.PROJECT -> Res.string.work_object
    CalendarExportField.ADDRESS -> Res.string.work_address
    CalendarExportField.CONTACT_NAME -> Res.string.work_name
    CalendarExportField.CONTACT_PHONE -> Res.string.work_phone
    CalendarExportField.NOTES -> Res.string.work_notes
}

private fun ReminderDay.label() = when (this) {
    ReminderDay.FIRST -> Res.string.p7b_first_timed
    ReminderDay.LATER -> Res.string.p7b_later_timed
    ReminderDay.DATE -> Res.string.p7b_date_days
    ReminderDay.DAY -> Res.string.p7b_specific_day
}

private fun ReminderMode.label() = when (this) {
    ReminderMode.INHERIT -> Res.string.p7b_inherit
    ReminderMode.ADD -> Res.string.p7b_add_rule
    ReminderMode.REPLACE -> Res.string.p7b_replace_rules
    ReminderMode.OFF -> Res.string.p7b_off
}

private fun CalendarSyncMode.label() = when (this) {
    CalendarSyncMode.INHERIT -> Res.string.p7b_inherit
    CalendarSyncMode.ON -> Res.string.p7b_on
    CalendarSyncMode.OFF -> Res.string.p7b_off
}

@Composable
internal fun WorkIntegrationActions(
    work: CalendarWork, platform: CalendarIntegrationPlatform, settings: CalendarIntegrationSettings?,
) {
    val coroutine = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<StringResource?>(null) }
    var snoozeDialog by remember { mutableStateOf(false) }
    val zone = work.draft.schedule.zone
    var snoozeDate by remember { mutableStateOf("") }
    var snoozeTime by remember { mutableStateOf("") }
    var exportDialog by remember { mutableStateOf(false) }
    var adoption by remember { mutableStateOf<CalendarExportConfirmation?>(null) }

    fun export(managed: Boolean, confirmation: CalendarExportConfirmation? = null) {
        if (busy) return
        busy = true
        feedback = null
        coroutine.launch {
            try {
                when (val result = platform.manualExport(work.key, work.version, managed, confirmation)) {
                    CalendarManualExportResult.Exported -> feedback = Res.string.p7b_export_done
                    is CalendarManualExportResult.ConfirmAdoption -> adoption = result.confirmation
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback = Res.string.p7b_action_failed
            } finally {
                busy = false
            }
        }
    }
    fun perform(success: StringResource, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        feedback = null
        coroutine.launch {
            try { action(); feedback = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback = Res.string.p7b_action_failed }
            finally { busy = false }
        }
    }
    if (work.key in platform.problems) {
        CalendarFeedback(syncFailureLabel(platform.failureReason), ContextFeedbackKind.WARNING)
        TextButton(onClick = platform::retry, enabled = !busy && !platform.retrying) { Text(stringResource(Res.string.work_reload)) }
    }
    if (work.draft.status.active && !platform.restorePending) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settings?.remindersEnabled == true && platform.remindersReady) {
                CalendarMenu(stringResource(Res.string.p7b_snooze)) {
                    for (duration in listOf(5, 15, 30, 60)) CalendarMenuItem(
                        text = { Text(stringResource(Res.string.p7b_minutes_count, duration)) },
                        onClick = { perform(Res.string.p7b_snoozed) { check(platform.snooze(work.key, snoozeInstant(duration))) } },
                    )
                    CalendarMenuItem(text = { Text(stringResource(Res.string.p7b_custom_snooze)) },
                        onClick = {
                            val initial = snoozeInstant(15).toLocalDateTime(zone)
                            snoozeDate = initial.date.toString()
                            snoozeTime = initial.time.shortTime()
                            snoozeDialog = true
                        })
                }
            }
            if (platform.destination != null && platform.writeCalendarGranted) {
                TextButton(onClick = { exportDialog = true }, enabled = !busy) { Text(stringResource(Res.string.p7b_export)) }
            }
        }
    }
    feedback?.let { CalendarFeedback(it, if (it == Res.string.p7b_action_failed) ContextFeedbackKind.ERROR else ContextFeedbackKind.INFO) }
    if (snoozeDialog) {
        val time = runCatching {
            LocalDateTime(LocalDate.parse(snoozeDate), LocalTime.parse(snoozeTime)).toInstant(zone)
        }.getOrNull()?.takeIf { it > Clock.System.now() }
        AlertDialog(onDismissRequest = { snoozeDialog = false },
            title = { Text(stringResource(Res.string.p7b_custom_snooze)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiskDateField(snoozeDate) { snoozeDate = it }
                WorkTimeField(snoozeTime, Res.string.p7b_reminder_time) { snoozeTime = it }
            } },
            dismissButton = { TextButton(onClick = { snoozeDialog = false }) { Text(stringResource(Res.string.p5d_cancel)) } },
            confirmButton = { TextButton(enabled = time != null, onClick = {
                snoozeDialog = false
                perform(Res.string.p7b_snoozed) { check(platform.snooze(work.key, checkNotNull(time))) }
            }) { Text(stringResource(Res.string.p7b_snooze)) } })
    }
    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false },
        title = { Text(stringResource(Res.string.p7b_export)) },
        text = { Column {
            TextButton(onClick = {
                exportDialog = false
                export(managed = false)
            }) { Text(stringResource(Res.string.p7b_export_once)) }
            TextButton(onClick = {
                exportDialog = false
                export(managed = true)
            }) { Text(stringResource(Res.string.p7b_export_managed)) }
        } },
        confirmButton = { TextButton(onClick = { exportDialog = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
    adoption?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { adoption = null },
            title = { Text(stringResource(Res.string.p7b_adopt_once_title)) },
            text = { Text(stringResource(Res.string.p7b_adopt_once_text)) },
            dismissButton = {
                TextButton(onClick = { adoption = null }) { Text(stringResource(Res.string.p5d_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = { adoption = null; export(managed = true, confirmation = confirmation) }) {
                    Text(stringResource(Res.string.p7b_export_managed))
                }
            },
        )
    }
}

private fun snoozeInstant(duration: Int) = kotlin.time.Clock.System.now() + duration.minutes

private fun syncFailureLabel(reason: CalendarSyncFailure?) = when (reason) {
    CalendarSyncFailure.ACCESS -> Res.string.p7b_sync_access
    CalendarSyncFailure.DESTINATION -> Res.string.p7b_sync_destination
    CalendarSyncFailure.DELETE -> Res.string.p7b_sync_delete
    CalendarSyncFailure.EXPORT -> Res.string.p7b_sync_export
    else -> Res.string.p7b_sync_problem
}
