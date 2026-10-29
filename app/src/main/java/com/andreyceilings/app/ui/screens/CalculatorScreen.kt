package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
import com.andreyceilings.app.*

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.backup.BackupPasswordStore
import com.andreyceilings.app.data.backup.AutomaticBackupScheduler
import com.andreyceilings.app.data.backup.AutomaticBackupSettings
import com.andreyceilings.app.data.backup.BackupSchedule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.app.core.CeilingEstimate
import com.andreyceilings.app.core.CeilingCalculator
import com.andreyceilings.shared.calculation.EstimateOptionId
import com.andreyceilings.shared.calculation.MeasurementPrecision
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalculatorScreen(
    database: CeilingsDatabase?,
    length: String, onLength: (String) -> Unit,
    width: String, onWidth: (String) -> Unit,
    price: String, onPrice: (String) -> Unit,
    profilePrice: String, onProfilePrice: (String) -> Unit,
    material: String, onMaterial: (String) -> Unit,
    profileType: String, onProfileType: (String) -> Unit,
    measurementPrecision: MeasurementPrecision, onMeasurementPrecision: (MeasurementPrecision) -> Unit,
    estimate: CeilingEstimate?,
    calculationError: String?,
    priceItems: List<PriceItemEntity>,
    priceItemsAvailable: Boolean,
    lightLineMeters: String, onLightLineMeters: (String) -> Unit,
    shadowProfileMeters: String, onShadowProfileMeters: (String) -> Unit,
    floatingCeilingMeters: String, onFloatingCeilingMeters: (String) -> Unit,
    chandeliersCount: String, onChandeliersCount: (String) -> Unit,
    spotCount: String, onSpotCount: (String) -> Unit,
    pipePassCount: String, onPipePassCount: (String) -> Unit,
    curtainNicheMeters: String, onCurtainNicheMeters: (String) -> Unit,
    extraCorners: String, onExtraCorners: (String) -> Unit,
    hardWallMeters: String, onHardWallMeters: (String) -> Unit,
    complexity: String, onComplexity: (String) -> Unit
) {
    val projectsFlow = remember(database) { database?.projects()?.observeAll() ?: flowOf<List<ProjectEntity>>(emptyList()) }
    val projects by projectsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    SectionTitle("Калькулятор стелі", "Попередній кошторис за розмірами приміщення")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(length, onLength, label = { Text("Довжина, м") }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(width, onWidth, label = { Text("Ширина, м") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    var precisionExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = precisionExpanded, onExpandedChange = { precisionExpanded = it }) {
        OutlinedTextField(
            value = measurementPrecision.label, onValueChange = {}, readOnly = true,
            label = { Text("Точність вимірювань") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(precisionExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
        )
        AppExposedDropdownMenu(expanded = precisionExpanded, onDismissRequest = { precisionExpanded = false }) {
            MeasurementPrecision.entries.forEach { precision ->
                DropdownMenuItem(text = { Text(precision.label) }, onClick = {
                    onMeasurementPrecision(precision)
                    precisionExpanded = false
                })
            }
        }
    }
    Text("Округлення розмірів до вибраного кроку; грошові суми округлюються окремо.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(price, onPrice, label = { Text("Полотно, ₴/м²") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(profilePrice, onProfilePrice, label = { Text("Профіль, ₴/м.п.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    var materialExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = materialExpanded, onExpandedChange = { materialExpanded = it }) {
        OutlinedTextField(
            when (material) {
                "pvc_glossy" -> "ПВХ глянцевий"
                "pvc_satin" -> "ПВХ сатиновий"
                "fabric" -> "Тканинний Descor"
                "fireproof_km2" -> "Негорючий KM2"
                "acoustic" -> "Акустичний"
                else -> "ПВХ матовий"
            }, {}, readOnly = true, label = { Text("Матеріал") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(materialExpanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth())
        AppExposedDropdownMenu(expanded = materialExpanded, onDismissRequest = { materialExpanded = false }) {
            listOf(
                "pvc_matte" to "ПВХ матовий", "pvc_glossy" to "ПВХ глянцевий",
                "pvc_satin" to "ПВХ сатиновий", "fabric" to "Тканинний Descor",
                "fireproof_km2" to "Негорючий KM2", "acoustic" to "Акустичний"
            ).forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onMaterial(id); materialExpanded = false })
            }
        }
    }
    var profileExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = profileExpanded, onExpandedChange = { profileExpanded = it }) {
        OutlinedTextField(
            when (profileType) {
                "pvc" -> "ПВХ стіновий"
                "shadow_kraab" -> "Тіньовий EuroKRAAB"
                "floating_led" -> "Парящий з LED"
                "slott_tracks" -> "Слот / трековий"
                else -> "Алюмінієвий гарпунний"
            }, {}, readOnly = true, label = { Text("Система профілю") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth())
        AppExposedDropdownMenu(expanded = profileExpanded, onDismissRequest = { profileExpanded = false }) {
            listOf(
                "standard_alu" to "Алюмінієвий гарпунний", "pvc" to "ПВХ стіновий",
                "shadow_kraab" to "Тіньовий EuroKRAAB",
                "floating_led" to "Парящий з LED",
                "slott_tracks" to "Слот / трековий"
            ).forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { onProfileType(id); profileExpanded = false })
            }
        }
    }
    Text("Додаткові роботи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(lightLineMeters, onLightLineMeters, label = { Text("Світлова лінія, м") }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(curtainNicheMeters, onCurtainNicheMeters, label = { Text("Ніша штор, м") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(chandeliersCount, onChandeliersCount, label = { Text("Люстри, шт.") }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(spotCount, onSpotCount, label = { Text("Точкові світильники, шт.") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(pipePassCount, onPipePassCount, label = { Text("Обхід труб, шт.") }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(extraCorners, onExtraCorners, label = { Text("Дод. кути, шт.") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(shadowProfileMeters, onShadowProfileMeters, label = { Text("Тіньовий профіль, м") }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(floatingCeilingMeters, onFloatingCeilingMeters, label = { Text("Парящий профіль, м") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    OutlinedTextField(hardWallMeters, onHardWallMeters, label = { Text("Складна стіна, м") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    var complexityExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = complexityExpanded, onExpandedChange = { complexityExpanded = it }) {
        OutlinedTextField(
            value = when (complexity) { "multilevel" -> "Багаторівнева"; "curved" -> "Криволінійна"; else -> "Пряма стеля" },
            onValueChange = {}, readOnly = true, label = { Text("Складність") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(complexityExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
        )
        AppExposedDropdownMenu(expanded = complexityExpanded, onDismissRequest = { complexityExpanded = false }) {
            listOf("Пряма стеля" to "straight", "Багаторівнева" to "multilevel", "Криволінійна" to "curved").forEach { (label, value) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onComplexity(value); complexityExpanded = false })
            }
        }
    }
    if (estimate != null) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Результат · Go", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Площа: ${formatNumber(estimate.area)} м²")
                Text("Периметр: ${formatNumber(estimate.perimeter)} м")
                HorizontalDivider()
                Text("Полотно: ${money(estimate.canvasCost)}")
                Text("Профіль: ${money(estimate.profileCost)}")
                Text("Складність: ${money(estimate.complexityCost)}")
                Text("Додаткові роботи: ${money(estimate.optionsCost)}")
                Text("Разом: ${money(estimate.total)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Go-рушій: помилка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Розрахунок не виконано. Kotlin не підміняє Go.")
                Text(calculationError ?: "Go не повернув результат.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(onClick = { showSaveDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = database != null && projects.isNotEmpty() && estimate != null && estimate.area > 0.0) {
        Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Зберегти кошторис до об’єкта")
    }
    if (projects.isEmpty()) Text("Щоб зберегти кошторис, спочатку створіть об’єкт у вкладці «Об’єкти».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val estimateForSave = estimate
    if (showSaveDialog && database != null && projects.isNotEmpty() && estimateForSave != null) SaveEstimateDialog(
        projects = projects,
        defaultName = "Кімната ${formatNumber(length.toSafeDouble())} × ${formatNumber(width.toSafeDouble())} м",
        onDismiss = { showSaveDialog = false },
        onSave = { projectId, name ->
            val details = org.json.JSONObject()
                .put(EstimateOptionId.LIGHT_LINE.code, calculatorOptionsForSave(lightLineMeters))
                .put(EstimateOptionId.SHADOW_PROFILE.code, calculatorOptionsForSave(shadowProfileMeters))
                .put(EstimateOptionId.FLOATING_PROFILE.code, calculatorOptionsForSave(floatingCeilingMeters))
                .put(EstimateOptionId.CHANDELIER.code, calculatorOptionsForSave(chandeliersCount))
                .put(EstimateOptionId.SPOT.code, calculatorOptionsForSave(spotCount))
                .put(EstimateOptionId.PIPE_PASS.code, calculatorOptionsForSave(pipePassCount))
                .put(EstimateOptionId.CURTAIN_NICHE.code, calculatorOptionsForSave(curtainNicheMeters))
                .put(EstimateOptionId.EXTRA_CORNER.code, calculatorOptionsForSave(extraCorners))
                .put(EstimateOptionId.HARD_WALL.code, calculatorOptionsForSave(hardWallMeters))
            scope.launch {
                runCatching {
                    val estimateDraft = EstimateEntity(
                        projectId = projectId, name = name.trim(), material = material, profileType = profileType, complexity = complexity,
                        lengthMeters = length.toSafeDouble(), widthMeters = width.toSafeDouble(), areaSquareMeters = estimateForSave.area, perimeterMeters = estimateForSave.perimeter,
                        canvasCost = estimateForSave.canvasCost, profileCost = estimateForSave.profileCost, complexityCost = estimateForSave.complexityCost,
                        optionsCost = estimateForSave.optionsCost, totalCost = estimateForSave.total, detailsJson = details.toString()
                    )
                    val lineItems = com.andreyceilings.app.core.EstimateLineItems.create(estimateDraft, details, priceItems)
                    details.put("lineItems", com.andreyceilings.app.core.EstimateLineItems.toJson(lineItems))
                    database.estimates().insert(estimateDraft.copy(detailsJson = details.toString()))
                }.onFailure { saveError = it.message ?: "Не вдалося зберегти кошторис" }
                showSaveDialog = false
            }
        }
    )
    Text(if (priceItemsAvailable) "Ціни матеріалу й профілю синхронізуються із зашифрованим прайс-листом. Зміни в прайсі впливають на нові розрахунки." else "Прайс-лист ще не завантажено; використовуються тимчасові значення.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        if (estimate != null) "Рушій розрахунку: Go · працює" else "Рушій розрахунку: Go · помилка",
        style = MaterialTheme.typography.labelSmall,
        color = if (estimate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}
