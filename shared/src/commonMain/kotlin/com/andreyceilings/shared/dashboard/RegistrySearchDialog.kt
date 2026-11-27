package com.andreyceilings.shared.dashboard

import com.andreyceilings.shared.calendar.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.andreyceilings.shared.objects.CompactObjectsSearchField
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.objects.SearchResultRow
import com.andreyceilings.shared.registry.GlobalRegistryMatches
import com.andreyceilings.shared.registry.GlobalRegistryTarget
import com.andreyceilings.shared.registry.RegistryRead
import com.andreyceilings.shared.registry.globalRegistryMatches
import com.andreyceilings.shared.registry.registryRead
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Modal search over the current screen. All reads are local, compact and session-scoped. */
@Composable
fun RegistrySearchDialog(store: ObjectsClientsStore?, onClose: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    calendar: CalendarStore? = null,
    onWork: ((WorkOccurrenceKey) -> Unit)? = null,
    onOpen: (GlobalRegistryTarget) -> Unit) = key(store) {
    var query by remember { mutableStateOf("") }
    var horizon by remember { mutableStateOf(90) }
    val workFlow = remember(calendar, query, horizon) {
        if (query.isBlank() || onWork == null) null else calendar?.search(query, horizon)
    }
    val works = key(query, horizon) { calendarState(workFlow) }
    val objectFlow = remember(store) { store?.uiObjectsRows() }
    val ceilingFlow = remember(store) { store?.uiRegistryCeilings() }
    val financeFlow = remember(store) { store?.uiRegistryCeilingFinance() }
    val quoteFlow = remember(store) { store?.uiRegistryQuotes() }
    val objectsRead = registryRead(objectFlow)
    val ceilingRead = registryRead(ceilingFlow)
    val financeRead = registryRead(financeFlow)
    val quoteRead = registryRead(quoteFlow)
    val objects = (objectsRead as? RegistryRead.Ready)?.data
    val ceilings = (ceilingRead as? RegistryRead.Ready)?.data
    val finance = (financeRead as? RegistryRead.Ready)?.data
    val quotes = (quoteRead as? RegistryRead.Ready)?.data
    val loading = listOf(objectsRead, ceilingRead, financeRead, quoteRead).any { it is RegistryRead.Loading }
    val unavailable = listOf(objectsRead, ceilingRead, financeRead, quoteRead).any { it is RegistryRead.Unavailable }
    val matches by produceState<Pair<String, GlobalRegistryMatches>?>(null, query, objects, ceilings, finance, quotes) {
        val requested = query
        value = requested to withContext(Dispatchers.Default) {
            globalRegistryMatches(requested, objects, ceilings, finance, quotes)
        }
    }
    val current = matches?.takeIf { it.first == query }?.second
    Dialog(onDismissRequest = onClose, properties = properties) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val colors = MaterialTheme.colorScheme
        val cancel = stringResource(Res.string.p5d_cancel)
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = cancel, onClick = onClose))
            // The search column owns the dialog's safe/IME insets. Measure results
            // in the actual space left below the input, rather than a fixed row estimate.
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompactObjectsSearchField(query, onChange = { query = it }, onClear = { query = "" },
                            onFocusChange = {}, placeholder = stringResource(Res.string.p6c_global_search),
                            modifier = Modifier.weight(1f), inputModifier = Modifier.focusRequester(focusRequester))
                        Surface(shape = MaterialTheme.shapes.medium, color = colors.surface,
                            contentColor = colors.onSurface) {
                            IconButton(onClick = onClose) { Icon(Icons.Default.Close, cancel) }
                        }
                    }
                    if (query.isNotBlank()) {
                        Card(Modifier.fillMaxWidth().weight(1f, fill = false),
                            shape = RoundedCornerShape(17.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface, contentColor = colors.onSurface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant)) {
                            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val matchingWorks = works.data().orEmpty()
                                if (workFlow != null) item(key = "work-horizon") {
                                    TextButton(onClick = { horizon = if (horizon == 90) 365 else 90 }) {
                                        Text(stringResource(Res.string.work_search_window, horizon))
                                    }
                                }
                                if (matchingWorks.isNotEmpty()) item(key = "work-heading") {
                                    GlobalSearchHeading(stringResource(Res.string.work_rows))
                                }
                                items(matchingWorks, key = { "work/${it.key.rootUuid}/${it.key.originalDate}" }) { work ->
                                    SearchResultRow(Icons.Default.CalendarMonth, work.draft.title,
                                        listOfNotNull(work.draft.date.shortDate(), work.projectLabel, work.contact.address)
                                            .joinToString(" · "), { onWork?.invoke(work.key) })
                                }
                                if (current != null) {
                                    if (current.projects.isNotEmpty()) item(key = "projects") {
                                        GlobalSearchHeading(stringResource(Res.string.p6c_objects))
                                    }
                                    items(current.projects, key = { "p-${it.id}" }) { project ->
                                        SearchResultRow(Icons.Default.Business, project.address.ifBlank { project.title },
                                            project.clientName.takeIf { it.isNotBlank() },
                                            { onOpen(GlobalRegistryTarget.Project(project.id)) })
                                    }
                                    if (current.clients.isNotEmpty()) item(key = "clients") {
                                        GlobalSearchHeading(stringResource(Res.string.p6c_clients))
                                    }
                                    items(current.clients, key = { "c-${it.id}" }) { client ->
                                        SearchResultRow(Icons.Default.Person, client.name,
                                            client.phone.takeIf { it.isNotBlank() },
                                            { onOpen(GlobalRegistryTarget.Client(client.id)) })
                                    }
                                    if (current.ceilings.isNotEmpty()) item(key = "ceilings") {
                                        GlobalSearchHeading(stringResource(Res.string.p6c_ceilings))
                                    }
                                    items(current.ceilings, key = { "m-${it.uuid}" }) { ceiling ->
                                        SearchResultRow(Icons.Default.CropSquare, ceiling.name,
                                            ceiling.address.ifBlank { ceiling.projectTitle },
                                            { onOpen(GlobalRegistryTarget.Ceiling(ceiling.projectId, ceiling.id)) })
                                    }
                                    if (current.quotes.isNotEmpty()) item(key = "quotes") {
                                        GlobalSearchHeading(stringResource(Res.string.p6c_quotes))
                                    }
                                    items(current.quotes, key = { (series, version) -> "q-${series.id}-${version.id}" }) { (series, version) ->
                                        val identity = stringResource(Res.string.p5d_revision_heading,
                                            series.number ?: "—", version.version)
                                        val type = stringResource(if (series.consolidated) Res.string.p6c_consolidated else Res.string.p6c_individual)
                                        SearchResultRow(if (series.consolidated) Icons.Default.ReceiptLong else Icons.Default.Receipt,
                                            listOfNotNull(version.title, identity).joinToString(" · "),
                                            listOf(type, series.address).joinToString(" · "),
                                            { onOpen(GlobalRegistryTarget.Quote(series.projectId, series.id, version.id, series.consolidated)) })
                                    }
                                    if (current.isEmpty && !loading && !unavailable && matchingWorks.isEmpty() && works !is CalendarRead.Loading) item {
                                        Text(stringResource(Res.string.p6c_no_matches), Modifier.padding(12.dp),
                                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                    }
                                }
                                if (loading || current == null || works is CalendarRead.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                if (unavailable) item {
                                    Text(stringResource(Res.string.p6c_unavailable), Modifier.padding(8.dp),
                                        color = colors.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchHeading(label: String) {
    Text(label, Modifier.padding(start = 9.dp, top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
