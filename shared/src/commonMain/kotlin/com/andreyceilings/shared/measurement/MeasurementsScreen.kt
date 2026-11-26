package com.andreyceilings.shared.measurement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.navigation.AppNavigationState
import com.andreyceilings.shared.objects.ObjectsClientsStore
import com.andreyceilings.shared.objects.ProjectEditor
import com.andreyceilings.shared.objects.PickerProjectSave
import com.andreyceilings.shared.registry.CeilingRegistryScreen
import com.andreyceilings.shared.registry.RegistryRead
import com.andreyceilings.shared.registry.registryRead
import com.andreyceilings.shared.resources.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Shared ceiling registry and the existing workspace, also hosted over quick-create sources. */
@Composable
fun MeasurementsScreen(
    store: ObjectsClientsStore?,
    navigation: AppNavigationState, precision: MeasurementPrecision,
    contentPadding: PaddingValues, systemTopInset: Dp,
    navigationGuard: MeasurementCreateNavigationGuard,
    onNavigate: (AppNavigationState) -> Unit,
    initialSearch: String = "",
    searchRequest: Int = 0,
    onUseMeasurement: ((com.andreyceilings.shared.estimate.FrozenPolygonGeometry) -> Unit)? = null
) {
    val back = { onNavigate(navigation.back()) }
    val details = store?.measurementDetails
    val editor = store?.measurementEdit
    navigation.measurementCreate?.let { route ->
        MeasurementCreateWorkspace(route.projectId, precision, editor, contentPadding, systemTopInset,
            onBack = back, onSaved = { onNavigate(navigation.measurementCreatedInEditor(route.projectId, it)) },
            navigationGuard = navigationGuard, quoteStore = details?.elements)
        return
    }
    navigation.measurementEdit?.let { route ->
        MeasurementEditScreen(route.projectId, route.measurementId, editor, contentPadding, systemTopInset,
            onBack = back, navigationGuard = navigationGuard, quoteStore = details?.elements,
            onUseMeasurement = onUseMeasurement)
        return
    }
    if (navigation.measurementProjectPicker) {
        MeasurementProjectPicker(store, contentPadding, back) { onNavigate(navigation.createMeasurement(it)) }
    } else {
        CeilingRegistryScreen(store, contentPadding, initialSearch, searchRequest) { project, ceiling ->
            onNavigate(navigation.openCeilingWorkspace(project, ceiling))
        }
    }
}

@Composable
private fun MeasurementProjectPicker(
    store: ObjectsClientsStore?, padding: PaddingValues, onClose: () -> Unit, onChoose: (Long) -> Unit
) {
    var retry by remember { mutableIntStateOf(0) }
    val flow = remember(store) { store?.rows?.flowOn(Dispatchers.Default) }
    val result = registryRead(flow, retry)
    val rows = (result as? RegistryRead.Ready)?.data
    val scope = rememberCoroutineScope()
    var editor by rememberSaveable { mutableStateOf(false) }
    val save by store?.pickerProjectSave?.collectAsState(PickerProjectSave.Idle)
        ?: remember { mutableStateOf<PickerProjectSave>(PickerProjectSave.Idle) }
    val busy = save == PickerProjectSave.Saving
    val onChooseLatest by rememberUpdatedState(onChoose)
    LaunchedEffect(store, save) {
        val created = save as? PickerProjectSave.Saved ?: return@LaunchedEffect
        editor = false
        onChooseLatest(created.id)
        store?.clearPickerResult(created)
    }
    val failed = stringResource(Res.string.p6c_unavailable)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(Res.string.p6c_choose_project), Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(Res.string.p5d_cancel)) }
            }
        }
        when (result) {
            RegistryRead.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            RegistryRead.Unavailable -> item {
                Text(failed, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(Res.string.p6c_retry)) }
            }
            is RegistryRead.Ready -> if (result.data.projects.isEmpty()) {
                item {
                    Text(stringResource(Res.string.p6c_need_project))
                    Button(onClick = { editor = true }) { Text(stringResource(Res.string.p6c_create_project)) }
                }
            } else {
                items(result.data.projects, key = { it.id }) { project ->
                    OutlinedButton(onClick = { onChoose(project.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(project.address.ifBlank { project.title })
                    }
                }
            }
        }
    }
    if (editor && rows != null && store != null) ProjectEditor(null, rows.clients, busy,
        failed.takeIf { save == PickerProjectSave.Failed },
        onDismiss = { if (!busy) { editor = false; store.clearPickerResult(save) } },
        onSave = { draft -> scope.launch(start = CoroutineStart.UNDISPATCHED) { store.createPickerProject(draft) } },
        onCreateClient = { draft -> withContext(Dispatchers.Default) { store.saveClient(null, draft) } })
}
