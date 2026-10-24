package com.andreyceilings.app

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import kotlinx.coroutines.flow.flowOf

private enum class AppTab(val title: String) {
    DASHBOARD("Панель"), OBJECTS("Об’єкти"), MEASUREMENTS("Заміри"),
    CALCULATOR("Калькулятор"), ESTIMATES("Кошториси"), PRICES("Прайс"), SETTINGS("Налаштування")
}

class MainActivity : ComponentActivity() {
    private val databaseStatus = mutableStateOf("Перевірка зашифрованої бази…")
    private val databaseState = mutableStateOf<CeilingsDatabase?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val db = withContext(Dispatchers.IO) {
                    CeilingsDatabase.get(applicationContext).also { db ->
                        db.openHelper.writableDatabase
                        com.andreyceilings.app.data.db.DemoPriceList.seedIfEmpty(db.priceItems())
                    }
                }
                databaseState.value = db
                databaseStatus.value = "Зашифрована база даних активна"
            } catch (_: Exception) {
                databaseStatus.value = "Помилка відкриття захищеної бази. Дані не завантажено."
            }
        }
        setContent { CeilingsTheme { CeilingsApp(databaseStatus.value, databaseState.value) { refreshed -> databaseState.value = refreshed; databaseStatus.value = "Зашифрована база даних активна" } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CeilingsApp(databaseStatus: String, database: CeilingsDatabase?, onDatabaseReplaced: (CeilingsDatabase) -> Unit) {
    var tab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var length by remember { mutableStateOf("5.4") }
    var width by remember { mutableStateOf("3.8") }
    var pricePerM2 by remember { mutableStateOf("290") }
    var profilePrice by remember { mutableStateOf("130") }
    var profileType by remember { mutableStateOf("Алюмінієвий гарпунний") }
    var material by remember { mutableStateOf("ПВХ матовий") }
    var lightLineMeters by remember { mutableStateOf("0") }
    var shadowProfileMeters by remember { mutableStateOf("0") }
    var floatingCeilingMeters by remember { mutableStateOf("0") }
    var chandeliersCount by remember { mutableStateOf("0") }
    var spotsCount by remember { mutableStateOf("0") }
    var pipePassCount by remember { mutableStateOf("0") }
    var curtainNicheMeters by remember { mutableStateOf("0") }
    var extraCorners by remember { mutableStateOf("0") }
    var hardWallMeters by remember { mutableStateOf("0") }
    var complexity by remember { mutableStateOf("straight") }

    val priceItemsFlow = remember(database) {
        database?.priceItems()?.observeAll() ?: flowOf<List<PriceItemEntity>>(emptyList())
    }
    val priceItems by priceItemsFlow.collectAsState(initial = emptyList())
    fun materialCode(label: String): String = when (label) {
        "ПВХ матовий" -> "canvas.pvc_matte"
        "ПВХ глянцевий" -> "canvas.pvc_glossy"
        "ПВХ сатиновий" -> "canvas.pvc_satin"
        "Тканинний Descor" -> "canvas.fabric"
        "Негорючий KM2" -> "canvas.fireproof_km2"
        "Акустичний" -> "canvas.acoustic"
        else -> "canvas.pvc_matte"
    }
    fun profileCode(label: String): String = when (label) {
        "Алюмінієвий гарпунний" -> "profile.standard_alu"
        "ПВХ стіновий" -> "profile.pvc"
        "Тіньовий EuroKRAAB" -> "profile.shadow_kraab"
        "Парящий з LED" -> "profile.floating_led"
        "Слот / трековий" -> "profile.slott_tracks"
        else -> "profile.standard_alu"
    }

    // Keep calculator defaults synchronized with the current encrypted price list.
    LaunchedEffect(priceItems, material, profileType) {
        priceItems.firstOrNull { it.code == materialCode(material) }?.let { pricePerM2 = it.price.toPriceInput() }
        priceItems.firstOrNull { it.code == profileCode(profileType) }?.let { profilePrice = it.price.toPriceInput() }
    }

    val calculatorOptions = CalculatorOptions(
        lightLinesMeters = lightLineMeters.toSafeDouble(),
        shadowProfileMeters = shadowProfileMeters.toSafeDouble(),
        floatingCeilingMeters = floatingCeilingMeters.toSafeDouble(),
        chandeliersCount = chandeliersCount.toSafeDouble(),
        spotsGX53Count = spotsCount.toSafeDouble(),
        pipePassCount = pipePassCount.toSafeDouble(),
        curtainNicheMeters = curtainNicheMeters.toSafeDouble(),
        extraCorners = extraCorners.toSafeDouble(),
        hardWallMeters = hardWallMeters.toSafeDouble(),
        complexity = complexity
    )
    // Go is the only calculation engine. Failure is surfaced to the user; no Kotlin fallback exists.
    val calculation = remember(length, width, pricePerM2, profilePrice, priceItems, calculatorOptions, material, profileType) {
        runCatching {
            CeilingCalculator.estimate(
                length.toSafeDouble(), width.toSafeDouble(),
                pricePerM2.toSafeDouble(), profilePrice.toSafeDouble(),
                priceItems = priceItems, options = calculatorOptions,
                materialType = materialCode(material).removePrefix("canvas."),
                profileType = profileCode(profileType).removePrefix("profile.")
            )
        }
    }
    val estimate = calculation.getOrNull()
    val calculationError = calculation.exceptionOrNull()?.let { error ->
        error.message?.takeIf { it.isNotBlank() } ?: "Невідома помилка Go-рушія"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("СтеляПро", fontWeight = FontWeight.Bold)
                        Text("Майстер натяжних стель", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { IconButton(onClick = { tab = AppTab.SETTINGS }) { Icon(Icons.Default.Settings, "Налаштування") } }
            )
        },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    AppTab.DASHBOARD to Icons.Default.Dashboard,
                    AppTab.OBJECTS to Icons.Default.Business,
                    AppTab.MEASUREMENTS to Icons.Default.Straighten,
                    AppTab.CALCULATOR to Icons.Default.Calculate,
                    AppTab.ESTIMATES to Icons.Default.ReceiptLong,
                    AppTab.PRICES to Icons.Default.PriceChange,
                    AppTab.SETTINGS to Icons.Default.Settings
                )
                items.forEach { (item, icon) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = item.title) },
                        label = { Text(item.title, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (tab) {
                AppTab.DASHBOARD -> DashboardScreen(
                    onNewMeasurement = { tab = AppTab.MEASUREMENTS },
                    onCalculator = { tab = AppTab.CALCULATOR }
                )
                AppTab.OBJECTS -> ObjectsScreen(database)
                AppTab.MEASUREMENTS -> MeasurementsScreen(database)
                AppTab.CALCULATOR -> CalculatorScreen(
                    database = database,
                    length = length, onLength = { length = it },
                    width = width, onWidth = { width = it },
                    price = pricePerM2, onPrice = { pricePerM2 = it },
                    profilePrice = profilePrice, onProfilePrice = { profilePrice = it },
                    material = material, onMaterial = { material = it },
                    profileType = profileType, onProfileType = { profileType = it },
                    estimate = estimate,
                    calculationError = calculationError,
                    priceItems = priceItems,
                    priceItemsAvailable = priceItems.isNotEmpty(),
                    lightLineMeters = lightLineMeters, onLightLineMeters = { lightLineMeters = it },
                    shadowProfileMeters = shadowProfileMeters, onShadowProfileMeters = { shadowProfileMeters = it },
                    floatingCeilingMeters = floatingCeilingMeters, onFloatingCeilingMeters = { floatingCeilingMeters = it },
                    chandeliersCount = chandeliersCount, onChandeliersCount = { chandeliersCount = it },
                    spotsCount = spotsCount, onSpotsCount = { spotsCount = it },
                    pipePassCount = pipePassCount, onPipePassCount = { pipePassCount = it },
                    curtainNicheMeters = curtainNicheMeters, onCurtainNicheMeters = { curtainNicheMeters = it },
                    extraCorners = extraCorners, onExtraCorners = { extraCorners = it },
                    hardWallMeters = hardWallMeters, onHardWallMeters = { hardWallMeters = it },
                    complexity = complexity, onComplexity = { complexity = it }
                )
                AppTab.ESTIMATES -> EstimatesScreen(database)
                AppTab.PRICES -> PriceListScreen(database)
                AppTab.SETTINGS -> SettingsScreen(databaseStatus, database, onDatabaseReplaced)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(shape = RoundedCornerShape(18.dp), modifier = modifier.widthIn(min = 130.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DashboardScreen(onNewMeasurement: () -> Unit, onCalculator: () -> Unit) {
    SectionTitle("Панель майстра", "Ваші об’єкти та розрахунки в одному місці")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Об’єкти", "3", Icons.Default.Business, Modifier.weight(1f))
        MetricCard("Заміри", "5", Icons.Default.Straighten, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Площа", "86,4 м²", Icons.Default.SquareFoot, Modifier.weight(1f))
        MetricCard("До монтажу", "2", Icons.Default.Event, Modifier.weight(1f))
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Швидкі дії", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onNewMeasurement, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Новий замір")
            }
            OutlinedButton(onClick = onCalculator, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Calculate, null); Spacer(Modifier.width(8.dp)); Text("Розрахувати вартість")
            }
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Найближча подія", fontWeight = FontWeight.Bold)
            Text("Монтаж натяжної стелі")
            Text("Квартира · Київ · 22 жовтня", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("Демо-дані показані лише для макета. Зберігання та повна міграція даних — наступний етап.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalculatorScreen(
    database: CeilingsDatabase?,
    length: String, onLength: (String) -> Unit,
    width: String, onWidth: (String) -> Unit,
    price: String, onPrice: (String) -> Unit,
    profilePrice: String, onProfilePrice: (String) -> Unit,
    material: String, onMaterial: (String) -> Unit,
    profileType: String, onProfileType: (String) -> Unit,
    estimate: CeilingEstimate?,
    calculationError: String?,
    priceItems: List<PriceItemEntity>,
    priceItemsAvailable: Boolean,
    lightLineMeters: String, onLightLineMeters: (String) -> Unit,
    shadowProfileMeters: String, onShadowProfileMeters: (String) -> Unit,
    floatingCeilingMeters: String, onFloatingCeilingMeters: (String) -> Unit,
    chandeliersCount: String, onChandeliersCount: (String) -> Unit,
    spotsCount: String, onSpotsCount: (String) -> Unit,
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
    OutlinedTextField(price, onPrice, label = { Text("Полотно, ₴/м²") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(profilePrice, onProfilePrice, label = { Text("Профіль, ₴/м.п.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    var materialExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = materialExpanded, onExpandedChange = { materialExpanded = it }) {
        OutlinedTextField(material, {}, readOnly = true, label = { Text("Матеріал") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(materialExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = materialExpanded, onDismissRequest = { materialExpanded = false }) {
            listOf("ПВХ матовий", "ПВХ глянцевий", "ПВХ сатиновий", "Тканинний Descor", "Негорючий KM2", "Акустичний").forEach {
                DropdownMenuItem(text = { Text(it) }, onClick = { onMaterial(it); materialExpanded = false })
            }
        }
    }
    var profileExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = profileExpanded, onExpandedChange = { profileExpanded = it }) {
        OutlinedTextField(profileType, {}, readOnly = true, label = { Text("Система профілю") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = profileExpanded, onDismissRequest = { profileExpanded = false }) {
            listOf("Алюмінієвий гарпунний", "ПВХ стіновий", "Тіньовий EuroKRAAB", "Парящий з LED", "Слот / трековий").forEach {
                DropdownMenuItem(text = { Text(it) }, onClick = { onProfileType(it); profileExpanded = false })
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
        OutlinedTextField(spotsCount, onSpotsCount, label = { Text("GX53, шт.") }, modifier = Modifier.weight(1f), singleLine = true)
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
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = complexityExpanded, onDismissRequest = { complexityExpanded = false }) {
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
                .put("lightLineMeters", calculatorOptionsForSave(lightLineMeters))
                .put("shadowProfileMeters", calculatorOptionsForSave(shadowProfileMeters))
                .put("floatingCeilingMeters", calculatorOptionsForSave(floatingCeilingMeters))
                .put("chandeliersCount", calculatorOptionsForSave(chandeliersCount))
                .put("spotsCount", calculatorOptionsForSave(spotsCount))
                .put("pipePassCount", calculatorOptionsForSave(pipePassCount))
                .put("curtainNicheMeters", calculatorOptionsForSave(curtainNicheMeters))
                .put("extraCorners", calculatorOptionsForSave(extraCorners))
                .put("hardWallMeters", calculatorOptionsForSave(hardWallMeters))
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

@Composable
private fun SettingsScreen(databaseStatus: String, database: CeilingsDatabase?, onDatabaseReplaced: (CeilingsDatabase) -> Unit) {
    var fileCheck by remember { mutableStateOf("Натисніть кнопку, щоб перевірити записи та файли бази даних.") }
    var exportStatus by remember { mutableStateOf("Експорт створює копію зашифрованого файла БД без ключа.") }
    var pendingExportFile by remember { mutableStateOf<java.io.File?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var automaticPassword by remember { mutableStateOf("") }
    var automaticStatus by remember { mutableStateOf("Автоматичні копії вимкнені, доки не вибрано папку й пароль.") }
    var selectedFolderLabel by remember { mutableStateOf(BackupPasswordStore.folder(context)?.lastPathSegment ?: "Папку не вибрано") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            automaticStatus = "Вибір папки скасовано."
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                BackupPasswordStore.setFolder(context, uri)
                selectedFolderLabel = uri.lastPathSegment ?: uri.toString()
                automaticStatus = "Папку збережено. Збережіть пароль і ввімкніть резервування."
            }.onFailure { automaticStatus = "Не вдалося зберегти папку: " + (it.message ?: "невідома помилка") }
        }
    }
    var backupStatus by remember { mutableStateOf("Оберіть резервну копію для перевірки або відновлення.") }
    var backupAction by remember { mutableStateOf("verify") }
    var selectedBackup by remember { mutableStateOf<java.io.File?>(null) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var portablePassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var portableStatus by remember { mutableStateOf("Переносна копія захищається окремим паролем.") }
    var pendingPortableBackup by remember { mutableStateOf<ByteArray?>(null) }
    val portableExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination: Uri? ->
        val bytes = pendingPortableBackup
        pendingPortableBackup = null
        if (destination == null || bytes == null) {
            bytes?.fill(0)
            portableStatus = "Збереження переносної копії скасовано."
        } else scope.launch {
            portableStatus = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination, "w")?.use { it.write(bytes) }
                        ?: error("Не вдалося відкрити файл для запису.")
                    "Переносну резервну копію збережено. Для відновлення потрібен цей пароль."
                }.getOrElse { "Не вдалося зберегти копію: ${it.message ?: "невідома помилка"}" }
                    .also { bytes.fill(0) }
            }
        }
    }
    var pendingRestoreContents by remember { mutableStateOf<com.andreyceilings.app.data.security.PortableBackupPayload.Contents?>(null) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) backupStatus = "Вибір резервної копії скасовано."
        else scope.launch {
            backupStatus = "Перевіряю переносну копію .stpb…"
            val result = withContext(Dispatchers.IO) { runCatching {
                val envelope = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Не вдалося прочитати вибраний файл.")
                try {
                    check(envelope.isNotEmpty()) { "Файл порожній." }
                    val password = restorePassword.toCharArray()
                    try { com.andreyceilings.app.data.security.PortableBackupManager.validate(context, envelope, password) }
                    finally { password.fill(Char.MIN_VALUE) }
                } finally { envelope.fill(0) }
            } }
            result.onSuccess { contents ->
                pendingRestoreContents?.let { it.database.fill(0); it.passphrase.fill(0) }
                pendingRestoreContents = contents
                backupStatus = "Пароль правильний, цілісність бази перевірено."
                if (backupAction == "restore") showRestoreConfirmation = true
                else { contents.database.fill(0); contents.passphrase.fill(0); pendingRestoreContents = null }
            }.onFailure { backupStatus = "Перевірка .stpb не пройдена: ${it.message ?: "невідома помилка"}. Поточну БД не змінено." }
        }
    }
    val exportDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination: Uri? ->
        val source = pendingExportFile
        pendingExportFile = null
        if (destination == null || source == null) {
            source?.delete()
            exportStatus = if (source != null) "Перевірки пройшли успішно: SQLCipher відкрив копію, integrity_check = ok, звичайний SQLite без ключа не прочитав схему. Збереження файла скасовано — перевірки залишаються успішними." else "Збереження скасовано до завершення перевірок."
        } else {
            scope.launch {
                exportStatus = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Не вдалося відкрити файл призначення.")
                        "Зашифровану копію збережено. Ключ до файла не додавався. Перевірте її звичайним SQLite-переглядачем без введення ключа."
                    }.getOrElse { "Експорт не вдався: " + (it.message ?: "невідома помилка") }
                }
                source.delete()
            }
        }
    }
    SectionTitle("Налаштування", "Персоналізація та резервне копіювання")
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListItem(leadingContent = { Icon(Icons.Default.Language, null) }, headlineContent = { Text("Мова") }, supportingContent = { Text("Українська") }, trailingContent = { Text("UA") })
            ListItem(leadingContent = { Icon(Icons.Default.Palette, null) }, headlineContent = { Text("Тема") }, supportingContent = { Text("Системна / світла / темна") })
            ListItem(leadingContent = { Icon(Icons.Default.PriceChange, null) }, headlineContent = { Text("Прайс-лист") }, supportingContent = { Text("Налаштування вартості матеріалів і робіт") })
            ListItem(leadingContent = { Icon(Icons.Default.Lock, null) }, headlineContent = { Text("Захист бази даних") }, supportingContent = { Text(databaseStatus) })
            HorizontalDivider()
            Text("Діагностика БД · версія 0.6.1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Підраховуються фактичні записи в таблицях і розміри основного та допоміжних файлів. Перевірка не змінює дані.", style = MaterialTheme.typography.bodySmall)
            Text(fileCheck, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    scope.launch {
                        fileCheck = withContext(Dispatchers.IO) {
                            runCatching {
                                val db = database ?: error("База даних ще не готова.")
                                val sqlDb = db.openHelper.writableDatabase
                                val path = sqlDb.path ?: error("Шлях до файлу БД недоступний.")
                                val file = java.io.File(path)
                                val clients = db.clients().count()
                                val projects = db.projects().count()
                                val measurements = db.measurements().count()
                                val estimates = db.estimates().count()
                                val prices = db.priceItems().count()
                                val files = listOf(file, java.io.File("$path-wal"), java.io.File("$path-shm"))
                                val fileInfo = files.joinToString("\n") { candidate ->
                                    "${candidate.name}: " + if (candidate.exists()) "${candidate.length()} байт" else "відсутній"
                                }
                                val header = if (file.exists()) file.inputStream().use { input ->
                                    ByteArray(16).also { buffer ->
                                        var offset = 0
                                        while (offset < buffer.size) {
                                            val count = input.read(buffer, offset, buffer.size - offset)
                                            if (count <= 0) break
                                            offset += count
                                        }
                                    }
                                } else ByteArray(0)
                                val plainSqlite = header.toString(Charsets.US_ASCII).startsWith("SQLite format 3")
                                val journalMode = sqlDb.query("PRAGMA journal_mode").use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else "невідомо"
                                }
                                val pageSize = sqlDb.query("PRAGMA page_size").use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                                }
                                val pageCount = sqlDb.query("PRAGMA page_count").use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                                }
                                val cipherVersion = runCatching {
                                    sqlDb.query("PRAGMA cipher_version").use { cursor ->
                                        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
                                    }
                                }.getOrNull()
                                val schemaCheck = runCatching {
                                    sqlDb.query("SELECT count(*) FROM sqlite_master").use { cursor ->
                                        if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                                    }
                                }
                                val dbLogicalBytes = if (pageSize > 0 && pageCount >= 0) pageSize * pageCount else -1L
                                "Записи в БД:\nКлієнти: $clients\nОб’єкти: $projects\nЗаміри: $measurements\nКошториси: $estimates\nПозиції прайсу: $prices\n\nSQLite:\nРежим журналювання: $journalMode\nРозмір сторінки: $pageSize байт\nКількість сторінок: $pageCount\nЛогічний розмір БД: $dbLogicalBytes байт\n\nФайли:\n$fileInfo\n\nПеревірка SQLCipher:\nВерсія cipher: ${cipherVersion ?: "не повернута (це саме по собі не доводить відсутність SQLCipher)"}\nЧитання системної схеми: " + schemaCheck.fold({ count -> if (count >= 0) "успішно (${count} об’єктів)" else "не вдалося" }, { error -> "ПОМИЛКА — ${error.message ?: "невідома помилка"}" }) + "\n\nВідкритий заголовок SQLite: " +
                                    if (plainSqlite) "ТАК — перевірте конфігурацію шифрування." else "не виявлено (узгоджується із SQLCipher, але не є самостійним доказом шифрування)."
                            }.getOrElse { "Перевірка не вдалася: ${it.message ?: "невідома помилка"}" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = database != null
            ) { Text("Перевірити БД і кількість записів") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        fileCheck = withContext(Dispatchers.IO) {
                            runCatching {
                                val db = database ?: error("База даних ще не готова.")
                                val sqlDb = db.openHelper.writableDatabase
                                sqlDb.query("PRAGMA wal_checkpoint(PASSIVE)").use { cursor ->
                                    if (!cursor.moveToFirst()) {
                                        "SQLite не повернула результат checkpoint."
                                    } else {
                                        val busy = cursor.getLong(0)
                                        val logFrames = cursor.getLong(1)
                                        val checkpointedFrames = cursor.getLong(2)
                                        "Результат WAL checkpoint (PASSIVE):\nЗайнятий: $busy\nКадрів у WAL: $logFrames\nПеренесено кадрів: $checkpointedFrames\n\nЦе не видаляє файли вручну. Повторіть звичайну діагностику, щоб порівняти розміри."
                                    }
                                }
                            }.getOrElse { "Checkpoint не виконано: ${it.message ?: "невідома помилка"}" }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = database != null
            ) { Text("Перевірити WAL checkpoint") }
            HorizontalDivider()
            Text("Перевірка та імпорт резервної копії", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Перевірка не змінює поточні дані. Імпорт замінить поточну базу лише після успішної перевірки та підтвердження.", style = MaterialTheme.typography.bodySmall)
            Text(backupStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { backupAction = "verify"; backupPicker.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = database != null) { Text("Перевірити резервну копію") }
            Button(onClick = { restorePassword = ""; showImportPasswordDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = database != null) { Text("Імпортувати резервну копію") }
            if (showImportPasswordDialog) {
                AlertDialog(
                    onDismissRequest = { showImportPasswordDialog = false; restorePassword = "" },
                    title = { Text("Пароль резервної копії") },
                    text = {
                        Column {
                            Text("Введіть пароль, який використовувався під час створення цієї копії. Для автоматичної копії це пароль автоматичного резервування, для переносної — пароль, заданий під час її створення.")
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = restorePassword,
                                onValueChange = { restorePassword = it },
                                label = { Text("Пароль") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showImportPasswordDialog = false
                            backupAction = "restore"
                            backupPicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3", "*/*"))
                        }, enabled = restorePassword.isNotEmpty()) { Text("Вибрати копію") }
                    },
                    dismissButton = { TextButton(onClick = { showImportPasswordDialog = false; restorePassword = "" }) { Text("Скасувати") } }
                )
            }
            if (showRestoreConfirmation) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirmation = false; pendingRestoreContents?.let { it.database.fill(0); it.passphrase.fill(0) }; pendingRestoreContents = null },
                    title = { Text("Відновити переносну копію?") },
                    text = { Text("Дані поточної бази буде замінено вмістом .stpb. Продовжити?") },
                    confirmButton = { TextButton(onClick = {
                        showRestoreConfirmation = false
                        val payload = pendingRestoreContents
                        pendingRestoreContents = null
                        scope.launch {
                            backupStatus = "Відновлюю переносну копію…"
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val contents = payload ?: error("Спочатку перевірте файл .stpb.")
                                val appContext = context.applicationContext
                                val live = appContext.getDatabasePath("andrey_ceilings_secure.db")
                                val oldDb = java.io.File.createTempFile("ceilings_before_restore_", ".db", context.cacheDir)
                                val oldKey = com.andreyceilings.app.data.security.DatabaseKeyManager.getOrCreatePassphrase(context)
                                val wal = java.io.File("${live.absolutePath}-wal")
                                val shm = java.io.File("${live.absolutePath}-shm")
                                var oldSaved = false
                                var keyChanged = false
                                try {
                                    database?.openHelper?.writableDatabase?.query("PRAGMA wal_checkpoint(FULL)")?.use { cursor ->
                                        check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "Не вдалося завершити запис поточної БД." }
                                    }
                                    CeilingsDatabase.closeAndReset()
                                    wal.delete(); shm.delete()
                                    if (live.exists()) { live.copyTo(oldDb, overwrite = true); oldSaved = true }
                                    live.writeBytes(contents.database)
                                    com.andreyceilings.app.data.security.DatabaseKeyManager.replacePassphrase(context, contents.passphrase)
                                    keyChanged = true
                                    val reopened = CeilingsDatabase.get(appContext)
                                    val sql = reopened.openHelper.writableDatabase
                                    sql.query("PRAGMA integrity_check").use { cursor ->
                                        check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) { "Перевірка цілісності після відновлення не пройдена." }
                                    }
                                    reopened
                                } catch (failure: Exception) {
                                    CeilingsDatabase.closeAndReset()
                                    wal.delete(); shm.delete()
                                    if (oldSaved && oldDb.exists()) oldDb.copyTo(live, overwrite = true)
                                    if (keyChanged) com.andreyceilings.app.data.security.DatabaseKeyManager.replacePassphrase(context, oldKey)
                                    throw failure
                                } finally {
                                    wal.delete(); shm.delete(); oldDb.delete(); oldKey.fill(0)
                                    contents.database.fill(0); contents.passphrase.fill(0)
                                }
                            } }
                            result.onSuccess { restored -> onDatabaseReplaced(restored); backupStatus = "Переносну копію відновлено. Перевірте клієнтів, об’єкти та кошториси." }
                                .onFailure { backupStatus = "Відновлення не виконано: ${it.message ?: "невідома помилка"}. Перевірте базу перед подальшою роботою." }
                        }
                    }) { Text("Відновити") } },
                    dismissButton = { TextButton(onClick = { showRestoreConfirmation = false; pendingRestoreContents?.let { it.database.fill(0); it.passphrase.fill(0) }; pendingRestoreContents = null }) { Text("Скасувати") } }
                )
            }
            HorizontalDivider()
            Text("Автоматичні резервні копії", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Щодня створюється пароль-захищена переносна копія в обраній папці. Зберігаються п’ять останніх файлів.", style = MaterialTheme.typography.bodySmall)
            Text("Папка: $selectedFolderLabel", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Вибрати папку для копій")
            }
            OutlinedTextField(
                value = automaticPassword,
                onValueChange = { automaticPassword = it },
                label = { Text("Пароль автоматичних копій (від 10 символів)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Button(
                onClick = {
                    runCatching {
                        BackupPasswordStore.savePassword(context, automaticPassword.toCharArray())
                        automaticStatus = "Пароль збережено в зашифрованому вигляді на цьому телефоні."
                    }.onFailure { automaticStatus = "Не вдалося зберегти пароль: " + (it.message ?: "невідома помилка") }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = automaticPassword.length >= 10
            ) { Text("Зберегти пароль автоматичних копій") }
            Text(automaticStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    runCatching {
                        check(BackupPasswordStore.folder(context) != null) { "Спочатку виберіть папку." }
                        val saved = BackupPasswordStore.readPassword(context) ?: error("Спочатку збережіть пароль.")
                        saved.fill(Char.MIN_VALUE)
                        AutomaticBackupScheduler.enable(context)
                        automaticStatus = "Автоматичне резервування ввімкнено. Android виконає першу копію за графіком."
                    }.onFailure { automaticStatus = "Не вдалося ввімкнути резервування: " + (it.message ?: "невідома помилка") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Увімкнути щоденні копії") }
            OutlinedButton(
                onClick = {
                    AutomaticBackupScheduler.disable(context)
                    automaticStatus = "Автоматичне резервування вимкнено."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Вимкнути автоматичні копії") }
            HorizontalDivider()
            Text("Переносна резервна копія", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Файл можна перенести на інший телефон. Весь пакет, включно з ключем SQLCipher, шифрується заданим паролем. Збережіть пароль окремо.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = portablePassword,
                onValueChange = { portablePassword = it },
                label = { Text("Пароль резервної копії (від 10 символів)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(portableStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    scope.launch {
                        portableStatus = "Створюю та перевіряю переносну копію…"
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val db = database ?: error("База даних ще не готова.")
                                val password = portablePassword.toCharArray()
                                try {
                                    com.andreyceilings.app.data.security.PortableBackupManager.create(context, db, password)
                                } finally { password.fill(Char.MIN_VALUE) }
                            }
                        }
                        result.onSuccess { bytes ->
                            pendingPortableBackup?.fill(0)
                            pendingPortableBackup = bytes
                            portableExportLauncher.launch("stelapro-portable-backup.stpb")
                            portableStatus = "Копію сформовано. Оберіть місце збереження."
                        }.onFailure {
                            portableStatus = "Не вдалося створити копію: ${it.message ?: "невідома помилка"}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = database != null && portablePassword.length >= 10
            ) { Text("Створити переносну копію") }
            HorizontalDivider()
            Text("Експорт тестової копії SQLCipher", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Створює копію файла БД у вибрану папку. Ключ не експортується. Зберігайте файл приватно.", style = MaterialTheme.typography.bodySmall)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (exportStatus.startsWith("Перевірки пройшли успішно") ||
                        exportStatus.startsWith("Перевірено:") ||
                        exportStatus.startsWith("Зашифровану копію збережено")) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    exportStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            OutlinedButton(onClick = {
                scope.launch {
                    exportStatus = withContext(Dispatchers.IO) {
                        runCatching {
                            val db = database ?: error("База даних ще не готова.")
                            val sqlDb = db.openHelper.writableDatabase
                            val sourcePath = sqlDb.path ?: error("Шлях до файла БД недоступний.")
                            val source = java.io.File(sourcePath)
                            check(source.isFile) { "Файл БД не знайдено." }
                            sqlDb.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                                check(cursor.moveToFirst()) { "SQLite не повернула результат WAL checkpoint." }
                                val busy = cursor.getLong(0)
                                val frames = cursor.getLong(1)
                                val copied = cursor.getLong(2)
                                check(busy == 0L && (frames < 0L || copied >= frames)) { "WAL checkpoint не завершився повністю. Повторіть, коли БД не використовується." }
                            }
                            val snapshot = java.io.File(context.cacheDir, "andrey_ceilings_secure_export.tmp")
                            source.inputStream().use { input -> snapshot.outputStream().use { output -> input.copyTo(output) } }
                            check(snapshot.length() > 0L) { "Створена копія порожня." }
                            // Verify this exact snapshot through Room's SQLCipher SupportFactory.
                            // The passphrase is binary; do not convert it to CharArray.
                            val passphrase = com.andreyceilings.app.data.security.DatabaseKeyManager
                                .getOrCreatePassphrase(context.applicationContext)
                            try {
                                net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
                                val verifier = androidx.room.Room.databaseBuilder(
                                    context.applicationContext,
                                    CeilingsDatabase::class.java,
                                    snapshot.absolutePath
                                ).openHelperFactory(
                                    net.sqlcipher.database.SupportFactory(passphrase.copyOf())
                                ).build()
                                try {
                                    val verifiedDb = verifier.openHelper.readableDatabase
                                    val integrity = verifiedDb.query("PRAGMA integrity_check").use { cursor ->
                                        check(cursor.moveToFirst()) { "SQLCipher не повернув результат integrity_check." }
                                        cursor.getString(0)
                                    }
                                    check(integrity.equals("ok", ignoreCase = true)) {
                                        "SQLCipher integrity_check не пройшов: $integrity"
                                    }
                                    val schemaCount = verifiedDb.query("SELECT count(*) FROM sqlite_master").use { cursor ->
                                        check(cursor.moveToFirst()) { "Не вдалося прочитати схему копії." }
                                        cursor.getLong(0)
                                    }
                                    check(schemaCount > 0L) { "У копії не знайдено об'єктів схеми." }
                                } finally {
                                    verifier.close()
                                }
                            } finally {
                                passphrase.fill(0)
                            }

                            // A plain Android SQLite connection must not be able to read the
                            // encrypted snapshot without the SQLCipher key.
                            var plainDb: android.database.sqlite.SQLiteDatabase? = null
                            var plainReadFailed = false
                            try {
                                val openedPlainDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                                    snapshot.absolutePath,
                                    null,
                                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                                )
                                plainDb = openedPlainDb
                                openedPlainDb.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                                    plainReadFailed = !cursor.moveToFirst()
                                }
                            } catch (_: Exception) {
                                plainReadFailed = true
                            } finally {
                                plainDb?.close()
                            }
                            check(plainReadFailed) {
                                "Звичайний SQLite зміг прочитати схему копії без ключа. Експорт зупинено."
                            }
                            pendingExportFile = snapshot
                            "Перевірено: SQLCipher відкрив копію, integrity_check = ok, схема доступна з ключем, звичайний SQLite без ключа не прочитав схему. Ключ не експортується. Виберіть папку для збереження."
                        }.getOrElse { "Не вдалося підготувати копію: " + (it.message ?: "невідома помилка") }
                    }
                    if (pendingExportFile != null) exportDocumentLauncher.launch("andrey_ceilings_secure_test.db")
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = database != null) { Text("Експортувати тестову копію БД") }
            ListItem(leadingContent = { Icon(Icons.Default.Backup, null) }, headlineContent = { Text("Резервна копія") }, supportingContent = { Text("Експортована копія зашифрована; ключ не додається до файла") })
        }
    }
}
private fun verifyEncryptedBackup(context: android.content.Context, file: java.io.File) {
    check(file.isFile && file.length() > 0L) { "Файл відсутній або порожній." }
    val passphrase = com.andreyceilings.app.data.security.DatabaseKeyManager.getOrCreatePassphrase(context.applicationContext)
    try {
        net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
        val verifier = androidx.room.Room.databaseBuilder(context.applicationContext, CeilingsDatabase::class.java, file.absolutePath)
            .openHelperFactory(net.sqlcipher.database.SupportFactory(passphrase.copyOf())).build()
        try {
            val db = verifier.openHelper.readableDatabase
            val integrity = db.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst()) { "SQLCipher не повернув результат integrity_check." }; cursor.getString(0)
            }
            check(integrity.equals("ok", ignoreCase = true)) { "integrity_check: $integrity" }
            val count = db.query("SELECT count(*) FROM sqlite_master").use { cursor ->
                check(cursor.moveToFirst()) { "Не вдалося прочитати схему." }; cursor.getLong(0)
            }
            check(count > 0L) { "У файлі немає схеми БД." }
        } finally { verifier.close() }
    } finally { passphrase.fill(0) }
    var plain: android.database.sqlite.SQLiteDatabase? = null
    var unreadable = false
    try {
        plain = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        plain.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor -> unreadable = !cursor.moveToFirst() }
    } catch (_: Exception) { unreadable = true } finally { plain?.close() }
    check(unreadable) { "Звичайний SQLite прочитав схему без ключа." }
}

private fun calculatorOptionsForSave(value: String): Double = value.toSafeDouble()

private fun formatNumber(value: Double) = String.format("%.2f", value)
private fun money(value: Double) = String.format("%,.0f ₴", value)

private fun Double.toPriceInput(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun String.toSafeDouble(): Double = replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
