package com.andreyceilings.shared.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import com.andreyceilings.shared.domain.ClientListEntry
import com.andreyceilings.shared.domain.ProjectListEntry
import com.andreyceilings.shared.domain.ProjectPaymentStatus
import com.andreyceilings.shared.domain.ProjectStatusRules
import com.andreyceilings.shared.domain.ProjectWorkStage
import com.andreyceilings.shared.domain.ObjectsClientsSearch
import com.andreyceilings.shared.input.CompactOutlinedStringField
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A country code must be explicit; never guess one from the device locale. */
internal fun internationalMessengerDigits(raw: String): String? {
    val trimmed = raw.trim()
    val number = when {
        trimmed.startsWith("+") -> trimmed.drop(1)
        trimmed.startsWith("00") -> trimmed.drop(2)
        else -> return null
    }
    return number.filter { it in '0'..'9' }.takeIf { it.length in 8..15 && it.first() != '0' }
}

@Composable
internal fun ClientReadCard(
    item: ClientListEntry,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDial: (String) -> Unit,
    onExternalAction: (ContactAction, String) -> Unit
) {
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val callError = stringResource(Res.string.p6a_call_unavailable)
    val mailError = stringResource(Res.string.p6a_email_unavailable)
    val messengerError = stringResource(Res.string.oc_messenger_unavailable)
    val internationalError = stringResource(Res.string.oc_messenger_international_number)
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { Text(item.name, style = MaterialTheme.typography.titleLarge) }
                if (onEdit != null) IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, stringResource(Res.string.oc_edit_client))
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A quiet count badge, then separate contact and notes sections.
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(pluralStringResource(Res.plurals.client_project_count, item.activeProjectCount, item.activeProjectCount),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                if (item.phone.isNotBlank() || item.email.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (item.phone.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(stringResource(Res.string.oc_phone),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    SelectionContainer { Text(item.phone, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = {
                                    error = if (runCatching { onDial(item.phone) }.isFailure) callError else null
                                }) {
                                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Call, stringResource(Res.string.p6a_call_client),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                listOf("WhatsApp" to ContactAction.WHATSAPP, "Telegram" to ContactAction.TELEGRAM,
                                    "Viber" to ContactAction.VIBER).forEach { (name, action) ->
                                    IconButton(onClick = {
                                        error = if (internationalMessengerDigits(item.phone) == null) internationalError
                                        else if (runCatching { onExternalAction(action, item.phone) }.isFailure) messengerError else null
                                    },
                                        modifier = Modifier.semantics { contentDescription = name }) {
                                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                            contentAlignment = Alignment.Center) {
                                            ExternalBrandArtwork(action, modifier = Modifier.size(28.dp), monochromeTint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (item.email.isNotBlank()) {
                        if (item.phone.isNotBlank()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(Res.string.oc_email),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(item.email, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface) }
                            }
                            IconButton(onClick = {
                                error = if (runCatching { uri.openUri("mailto:" + item.email.trim()) }.isFailure) mailError else null
                            }) { Icon(Icons.Default.Email, stringResource(Res.string.p6a_email_client),
                                tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
                if (item.notes.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.oc_notes), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(item.notes, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, stringResource(Res.string.oc_delete_client),
                        tint = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.oc_close)) }
            }
        }
    )
}

@Composable
internal fun ClientEditor(
    initial: ClientListEntry?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ClientDraft) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phone.orEmpty()) }
    var email by remember(initial?.id) { mutableStateOf(initial?.email.orEmpty()) }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    val creating = initial == null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(if (creating) Res.string.oc_new_client else Res.string.oc_edit_client)) },
        text = {
            // Floating labels extend above the compact field; keep that space
            // inside the scroll viewport rather than clipping the first label.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactOutlinedStringField(name, { name = it }, stringResource(Res.string.oc_name_required), enabled = !busy)
                CompactOutlinedStringField(phone, { phone = it },
                    stringResource(if (creating) Res.string.oc_phone_required else Res.string.oc_phone),
                    enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                CompactOutlinedStringField(email, { email = it }, stringResource(Res.string.oc_email),
                    enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                CompactOutlinedStringField(notes, { notes = it }, stringResource(Res.string.oc_notes), enabled = !busy, maxLines = 4)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && (!creating || phone.any(Char::isDigit)),
                onClick = { onSave(ClientDraft(name, phone, email, notes)) }) { Text(stringResource(Res.string.oc_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.p5d_cancel)) } }
    )
}

@Composable
internal fun ProjectEditor(
    initial: ProjectListEntry?,
    clients: List<ClientListEntry>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ProjectDraft) -> Unit,
    onCreateClient: suspend (ClientDraft) -> Long
) {
    var address by remember(initial?.id) { mutableStateOf(initial?.address.orEmpty()) }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes.orEmpty()) }
    var work by remember(initial?.id) {
        mutableStateOf(initial?.workStatus?.takeIf(ProjectStatusRules::isKnownWorkStage)
            ?: if (initial == null) ProjectWorkStage.MEASUREMENT.wireId else "")
    }
    var payment by remember(initial?.id) { mutableStateOf(initial?.paymentStatus ?: ProjectPaymentStatus.UNPAID.wireId) }
    var clientId by remember(initial?.id) { mutableStateOf(initial?.clientId) }
    var createdClient by remember(initial?.id) { mutableStateOf<ClientListEntry?>(null) }
    var pickingClient by remember { mutableStateOf(false) }
    var creatingClient by remember { mutableStateOf(false) }
    var savingClient by remember { mutableStateOf(false) }
    var clientError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveClientError = stringResource(Res.string.oc_client_save_failed)
    val availableClients = if (createdClient != null && clients.none { it.id == createdClient?.id })
        clients + checkNotNull(createdClient) else clients
    if (creatingClient) {
        ClientEditor(null, savingClient, clientError, onDismiss = { creatingClient = false }, onSave = { draft ->
            if (!savingClient) {
                savingClient = true
                clientError = null
                scope.launch {
                    try {
                        val id = onCreateClient(draft)
                        createdClient = ClientListEntry(id, draft.name.trim(), draft.phone.trim(), draft.email.trim(), draft.notes.trim(), 0)
                        clientId = id
                        creatingClient = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        clientError = saveClientError
                    } finally { savingClient = false }
                }
            }
        })
    } else if (pickingClient) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(availableClients, query) { ObjectsClientsSearch.clients(availableClients, query) }
        AlertDialog(
            onDismissRequest = { pickingClient = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(Res.string.oc_choose_client)) },
            text = {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactOutlinedStringField(query, { query = it }, stringResource(Res.string.oc_search_client))
                    ClientChoiceCard(
                        title = stringResource(Res.string.oc_without_client),
                        selected = clientId == null,
                        onClick = { clientId = null; pickingClient = false },
                        leading = { Icon(Icons.Default.PersonOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    if (filtered.isEmpty()) {
                        Text(stringResource(Res.string.oc_no_matching_clients),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.id }) { client ->
                                ClientChoiceCard(
                                    title = client.name,
                                    phone = client.phone,
                                    selected = client.id == clientId,
                                    onClick = { clientId = client.id; pickingClient = false },
                                    leading = { ClientExperienceMark(client.activeProjectCount) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingClient = false }) { Text(stringResource(Res.string.p5d_cancel)) } }
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(if (initial == null) Res.string.oc_new_project else Res.string.oc_edit_project)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactOutlinedStringField(address, { address = it }, stringResource(Res.string.oc_address_required), enabled = !busy)
                    Text(stringResource(Res.string.p6a_client), style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(onClick = { pickingClient = true }, enabled = !busy, modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(availableClients.firstOrNull { it.id == clientId }?.name
                                    ?: stringResource(Res.string.p6a_no_client), Modifier.weight(1f))
                                Icon(Icons.Default.KeyboardArrowDown, null)
                            }
                        }
                        IconButton(enabled = !busy, onClick = { clientError = null; creatingClient = true }) {
                            Icon(Icons.Default.PersonAdd, stringResource(Res.string.oc_new_client), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(stringResource(Res.string.oc_work_stage), style = MaterialTheme.typography.labelMedium)
                    WorkStagePicker(initial?.id ?: 0L, work, !busy) { work = it }
                    Text(stringResource(Res.string.oc_payment), style = MaterialTheme.typography.labelMedium)
                    PaymentStatusPicker(initial?.id ?: 0L, payment, !busy) { payment = it }
                    CompactOutlinedStringField(notes, { notes = it }, stringResource(Res.string.oc_notes), enabled = !busy, maxLines = 4)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && address.isNotBlank() && ProjectStatusRules.isKnownWorkStage(work),
                    onClick = { onSave(ProjectDraft(address, clientId, work, payment, notes)) }) { Text(stringResource(Res.string.oc_save)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.p5d_cancel)) } }
        )
    }
}

/** Theme-aligned, compact selection row; no contact or delete side effects. */
@Composable
private fun ClientChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    phone: String = ""
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (selected) colors.primary else colors.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (phone.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Call, null, Modifier.size(14.dp))
                        Text(phone, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun localizedWorkStage(status: String): String = stringResource(when (ProjectWorkStage.fromWireId(status)) {
    ProjectWorkStage.MEASUREMENT -> Res.string.oc_work_measurement
    ProjectWorkStage.CALCULATION -> Res.string.oc_work_calculation
    ProjectWorkStage.APPROVED -> Res.string.oc_work_approved
    ProjectWorkStage.IN_PROGRESS -> Res.string.oc_work_in_progress
    ProjectWorkStage.PAUSED -> Res.string.oc_work_paused
    ProjectWorkStage.REWORK -> Res.string.oc_work_rework
    ProjectWorkStage.COMPLETED -> Res.string.oc_work_completed
    ProjectWorkStage.CANCELLED -> Res.string.oc_work_cancelled
    null -> when (status) {
        "" -> Res.string.oc_work_unknown
        "paid" -> Res.string.oc_work_legacy
        else -> Res.string.oc_work_unrecognized
    }
})

@Composable
internal fun localizedPayment(status: String, compact: Boolean = false): String = stringResource(when (ProjectPaymentStatus.fromWireId(status)) {
    ProjectPaymentStatus.UNKNOWN -> if (compact) Res.string.oc_payment_unknown_short else Res.string.oc_payment_unknown
    ProjectPaymentStatus.UNPAID -> Res.string.oc_payment_unpaid
    ProjectPaymentStatus.PARTIALLY_PAID -> if (compact) Res.string.oc_payment_partial else Res.string.oc_payment_partial_full
    ProjectPaymentStatus.PAID -> Res.string.oc_payment_paid
})
