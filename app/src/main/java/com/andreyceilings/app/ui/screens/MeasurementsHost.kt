package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.shared.estimate.FrozenPolygonGeometry
import com.andreyceilings.shared.measurement.MeasurementCreateNavigationGuard
import com.andreyceilings.shared.measurement.MeasurementsScreen
import com.andreyceilings.shared.measurement.MeasurementsStore
import com.andreyceilings.shared.navigation.AppNavigationState

/** Android supplies the unlocked SQLCipher session and system insets; UI and actions are CMP. */
@Composable
internal fun MeasurementsHost(
    database: CeilingsDatabase?, navigation: AppNavigationState, precision: MeasurementPrecision,
    navigationGuard: MeasurementCreateNavigationGuard,
    onNavigate: (AppNavigationState) -> Unit, onEditSaved: (Long, Long) -> Unit,
    onUseMeasurement: (FrozenPolygonGeometry) -> Unit,
    onOpenEstimates: () -> Unit, onCreateProject: () -> Unit
) {
    val store = remember(database) { database?.let { MeasurementsStore(it.database) } }
    val editors = database?.objectsClientsStore
    val fullscreen by navigationGuard.fullscreen.collectAsState()
    MeasurementsScreen(store, editors?.measurementCreate, editors?.measurementDetails, editors?.measurementEdit,
        navigation, precision,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp,
            bottom = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (fullscreen) 0.dp else 84.dp,
                if (navigation.measurementCreate != null || navigation.measurementEdit != null)
                    WindowInsets.ime.asPaddingValues().calculateBottomPadding() else 0.dp)),
        systemTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        navigationGuard = navigationGuard, onNavigate = onNavigate, onEditSaved = onEditSaved,
        onUseMeasurement = onUseMeasurement, onOpenEstimates = onOpenEstimates, onCreateProject = onCreateProject)
}
