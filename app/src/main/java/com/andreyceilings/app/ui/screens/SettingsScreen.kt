package com.andreyceilings.app.ui.screens

import com.andreyceilings.app.ui.theme.AppAlertDialog
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
import com.andreyceilings.app.*
import com.andreyceilings.app.data.PerformanceDiagnosticsSettings
import com.andreyceilings.app.data.PerformanceDiagnosticsLog

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
import androidx.compose.foundation.clickable
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
import com.andreyceilings.app.ui.theme.ThemeMode
import com.andreyceilings.app.ui.theme.ThemeModeSelector
import com.andreyceilings.app.core.CeilingEstimate
import com.andreyceilings.app.core.CeilingCalculator
import com.andreyceilings.app.core.CalculatorOptions
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.app.data.db.ProjectEntity
import com.andreyceilings.app.data.db.EstimateEntity
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(databaseStatus: String, database: CeilingsDatabase?, onDatabaseReplaced: (CeilingsDatabase) -> Unit, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onOpenPriceList: () -> Unit) {
    var fileCheck by remember { mutableStateOf("Натисніть кнопку, щоб перевірити записи та файли бази даних.") }
    var exportStatus by remember { mutableStateOf("Експорт створює копію зашифрованого файла БД без ключа.") }
    var pendingExportFile by remember { mutableStateOf<java.io.File?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appVersionLabel = remember(context) {
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName ?: "невідома"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            "$versionName (збірка $versionCode)"
        }.getOrDefault("Не вдалося визначити")
    }
    var automaticPassword by remember { mutableStateOf("") }
    var automaticStatus by remember { mutableStateOf("Автоматичні копії вимкнені, доки не вибрано папку й пароль.") }
    var showDisableAllConfirmation by remember { mutableStateOf(false) }
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
    var performanceLogStatus by remember { mutableStateOf("Журнал ще не експортовано.") }
    val performanceLogExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destination: Uri? ->
        if (destination == null) {
            performanceLogStatus = "Експорт журналу скасовано."
        } else {
            scope.launch {
                performanceLogStatus = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = PerformanceDiagnosticsLog.readForExport(context)
                        try {
                            check(bytes.isNotEmpty()) { "Журнал порожній. Увімкніть діагностику та повторіть дії в застосунку." }
                            context.contentResolver.openOutputStream(destination, "w")?.use { it.write(bytes) }
                                ?: error("Не вдалося відкрити файл для запису.")
                            "Журнал збережено у вибране вами місце."
                        } finally {
                            bytes.fill(0)
                        }
                    }.getOrElse { "Не вдалося зберегти журнал: ${it.message ?: "невідома помилка"}" }
                }
            }
        }
    }
    SectionTitle("Налаштування", "Персоналізація та резервне копіювання")
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface), leadingContent = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Версія застосунку") }, supportingContent = { Text(appVersionLabel) })
            HorizontalDivider()
            ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface), leadingContent = { Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Мова") }, supportingContent = { Text("Українська") }, trailingContent = { Text("UA", color = MaterialTheme.colorScheme.primary) })
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text("Тема оформлення", style = MaterialTheme.typography.titleSmall)
                    Text("Оберіть кольори застосунку", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ThemeModeSelector(
                    mode = themeMode,
                    onModeChange = onThemeModeChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (BuildConfig.DEBUG) {
                var performanceDiagnosticsEnabled by remember {
                    mutableStateOf(PerformanceDiagnosticsSettings.isEnabled(context))
                }
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Default.Speed, null) },
                    headlineContent = { Text("Журнал продуктивності") },
                    supportingContent = {
                        Text(
                            if (performanceDiagnosticsEnabled) {
                                "Увімкнено: технічні вимірювання збираються локально. Файл зберігається лише за вашою командою."
                            } else {
                                "Вимкнено. Журнал не поповнюється; наявні записи можна зберегти або очистити."
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = performanceDiagnosticsEnabled,
                            onCheckedChange = { enabled ->
                                PerformanceDiagnosticsSettings.setEnabled(context, enabled)
                                performanceDiagnosticsEnabled = enabled
                            }
                        )
                    }
                )
                Text(performanceLogStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { performanceLogExportLauncher.launch("ceilings-perf.txt") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Зберегти журнал у файл…") }
                OutlinedButton(
                    onClick = {
                        performanceLogStatus = if (PerformanceDiagnosticsLog.clear(context)) {
                            "Локальний журнал очищено."
                        } else {
                            "Не вдалося очистити локальний журнал."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Очистити журнал") }
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenPriceList),
                leadingContent = { Icon(Icons.Default.PriceChange, null) },
                headlineContent = { Text("Прайс-лист") },
                supportingContent = { Text("Налаштування вартості матеріалів і робіт") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = "Відкрити прайс-лист") }
            )
            ListItem(leadingContent = { Icon(Icons.Default.Lock, null) }, headlineContent = { Text("Захист бази даних") }, supportingContent = { Text(databaseStatus) })
            HorizontalDivider()
            Text("Діагностика бази даних", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            OutlinedButton(onClick = { backupAction = "verify"; restorePassword = ""; showImportPasswordDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = database != null) { Text("Перевірити резервну копію") }
            Button(onClick = { backupAction = "restore"; restorePassword = ""; showImportPasswordDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = database != null) { Text("Імпортувати резервну копію") }
            if (showImportPasswordDialog) {
                AppAlertDialog(
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
                            backupPicker.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3", "*/*"))
                        }, enabled = restorePassword.isNotEmpty()) { Text("Вибрати копію") }
                    },
                    dismissButton = { TextButton(onClick = { showImportPasswordDialog = false; restorePassword = "" }) { Text("Скасувати") } }
                )
            }
            if (showRestoreConfirmation) {
                AppAlertDialog(
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
                                    val importedCounts = sql.query("""
                                        SELECT
                                            (SELECT COUNT(*) FROM clients),
                                            (SELECT COUNT(*) FROM projects),
                                            (SELECT COUNT(*) FROM room_measurements),
                                            (SELECT COUNT(*) FROM estimates),
                                            (SELECT COUNT(*) FROM price_items)
                                    """.trimIndent()).use { cursor ->
                                        check(cursor.moveToFirst()) { "Не вдалося порахувати імпортовані записи." }
                                        (0..4).map { cursor.getLong(it) }
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
                            result.onSuccess { restored ->
                                onDatabaseReplaced(restored)
                                val counts = withContext(Dispatchers.IO) {
                                    val sql = restored.openHelper.writableDatabase
                                    sql.query("""
                                        SELECT
                                            (SELECT COUNT(*) FROM clients),
                                            (SELECT COUNT(*) FROM projects),
                                            (SELECT COUNT(*) FROM room_measurements),
                                            (SELECT COUNT(*) FROM estimates),
                                            (SELECT COUNT(*) FROM price_items)
                                    """.trimIndent()).use { cursor ->
                                        check(cursor.moveToFirst()) { "Не вдалося прочитати кількість записів після імпорту." }
                                        (0..4).map { cursor.getLong(it) }
                                    }
                                }
                                backupStatus = "ІМПОРТ УСПІШНИЙ — базу відновлено й перевірено.\n" +
                                    "Клієнти: ${counts[0]}\n" +
                                    "Об’єкти: ${counts[1]}\n" +
                                    "Виміри: ${counts[2]}\n" +
                                    "Кошториси: ${counts[3]}\n" +
                                    "Позиції прайсу: ${counts[4]}"
                            }.onFailure { backupStatus = "Відновлення не виконано: ${it.message ?: "невідома помилка"}. Перевірте базу перед подальшою роботою." }
                        }
                    }) { Text("Відновити") } },
                    dismissButton = { TextButton(onClick = { showRestoreConfirmation = false; pendingRestoreContents?.let { it.database.fill(0); it.passphrase.fill(0) }; pendingRestoreContents = null }) { Text("Скасувати") } }
                )
            }
            HorizontalDivider()
            Text("Автоматичні резервні копії", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Копії захищені паролем. Зберігаються п’ять останніх автоматичних файлів.", style = MaterialTheme.typography.bodySmall)
            var periodicEnabled by remember { mutableStateOf(AutomaticBackupSettings.periodicEnabled(context)) }
            var selectedSchedule by remember { mutableStateOf(AutomaticBackupSettings.schedule(context)) }
            var backgroundEnabled by remember { mutableStateOf(AutomaticBackupSettings.backgroundEnabled(context)) }
            var databaseChangeEnabled by remember { mutableStateOf(AutomaticBackupSettings.databaseChangeEnabled(context)) }
            DisposableEffect(databaseChangeEnabled, database) {
                val db = database
                val observer = if (databaseChangeEnabled && db != null) {
                    object : androidx.room.InvalidationTracker.Observer(
                        "clients", "projects", "room_measurements", "price_items", "estimates"
                    ) {
                        override fun onInvalidated(tables: Set<String>) {
                            if (AutomaticBackupSettings.anyEnabled(context) &&
                                BackupPasswordStore.folder(context) != null) {
                                AutomaticBackupScheduler.triggerOnDatabaseChange(context)
                            }
                        }
                    }.also { db.invalidationTracker.addObserver(it) }
                } else null
                onDispose { if (observer != null && db != null) db.invalidationTracker.removeObserver(observer) }
            }
            ListItem(
                headlineContent = { Text("Резервування за графіком") },
                supportingContent = { Text("Створювати копію за вибраною періодичністю") },
                trailingContent = { Switch(checked = periodicEnabled, onCheckedChange = { enabled ->
                    if (enabled) {
                        runCatching {
                            check(BackupPasswordStore.folder(context) != null) { "Спочатку виберіть папку." }
                            val saved = BackupPasswordStore.readPassword(context) ?: error("Спочатку збережіть пароль.")
                            saved.fill(Char.MIN_VALUE)
                            AutomaticBackupScheduler.enable(context, selectedSchedule)
                            AutomaticBackupSettings.setPeriodicEnabled(context, true)
                            periodicEnabled = true
                        }.onFailure { automaticStatus = it.message ?: "Не вдалося ввімкнути графік." }
                    } else {
                        val anotherModeEnabled =
                            AutomaticBackupSettings.backgroundEnabled(context) ||
                            AutomaticBackupSettings.databaseChangeEnabled(context)
                        if (!anotherModeEnabled) {
                            // Keep the switch enabled until the user confirms the destructive action.
                            showDisableAllConfirmation = true
                        } else {
                            AutomaticBackupScheduler.disablePeriodic(context)
                            AutomaticBackupSettings.setPeriodicEnabled(context, false)
                            periodicEnabled = false
                        }
                    }
                }) }
            )
            var scheduleExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = scheduleExpanded && periodicEnabled, onExpandedChange = { if (periodicEnabled) scheduleExpanded = it }) {
                OutlinedTextField(
                    value = selectedSchedule.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Періодичність") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = scheduleExpanded) },
                    enabled = periodicEnabled,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = periodicEnabled).fillMaxWidth()
                )
                AppExposedDropdownMenu(expanded = scheduleExpanded && periodicEnabled, onDismissRequest = { scheduleExpanded = false }) {
                    BackupSchedule.values().forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            selectedSchedule = option
                            AutomaticBackupSettings.setSchedule(context, option)
                            scheduleExpanded = false
                            if (AutomaticBackupSettings.periodicEnabled(context)) AutomaticBackupScheduler.enable(context, option)
                        })
                    }
                }
            }
            ListItem(
                headlineContent = { Text("Резервування при згортанні") },
                supportingContent = { Text("Створювати копію, коли застосунок переходить у фон") },
                trailingContent = { Switch(checked = backgroundEnabled, onCheckedChange = { enabled ->
                    if (enabled) {
                        runCatching {
                            check(BackupPasswordStore.folder(context) != null) { "Спочатку виберіть папку для копій." }
                            val saved = BackupPasswordStore.readPassword(context) ?: error("Спочатку збережіть пароль автоматичних копій.")
                            saved.fill(Char.MIN_VALUE)
                            AutomaticBackupSettings.setBackgroundEnabled(context, true)
                            backgroundEnabled = true
                        }.onFailure { automaticStatus = it.message ?: "Не вдалося ввімкнути резервування." }
                    } else {
                        val anotherModeEnabled =
                            AutomaticBackupSettings.periodicEnabled(context) ||
                            AutomaticBackupSettings.databaseChangeEnabled(context)
                        if (!anotherModeEnabled) {
                            // Do not change the stored or displayed switch state before confirmation.
                            showDisableAllConfirmation = true
                        } else {
                            AutomaticBackupSettings.setBackgroundEnabled(context, false)
                            backgroundEnabled = false
                        }
                    }
                }) }
            )
            ListItem(
                headlineContent = { Text("Резервування після змін БД") },
                supportingContent = { Text("Створювати копію після змін у таблицях бази даних") },
                trailingContent = { Switch(checked = databaseChangeEnabled, onCheckedChange = { enabled ->
                    if (enabled) {
                        runCatching {
                            check(BackupPasswordStore.folder(context) != null) { "Спочатку виберіть папку для копій." }
                            val saved = BackupPasswordStore.readPassword(context) ?: error("Спочатку збережіть пароль автоматичних копій.")
                            saved.fill(Char.MIN_VALUE)
                            AutomaticBackupSettings.setDatabaseChangeEnabled(context, true)
                            databaseChangeEnabled = true
                        }.onFailure { automaticStatus = it.message ?: "Не вдалося ввімкнути резервування." }
                    } else {
                        val anotherModeEnabled =
                            AutomaticBackupSettings.periodicEnabled(context) ||
                            AutomaticBackupSettings.backgroundEnabled(context)
                        if (!anotherModeEnabled) {
                            // Do not change the stored or displayed switch state before confirmation.
                            showDisableAllConfirmation = true
                        } else {
                            AutomaticBackupSettings.setDatabaseChangeEnabled(context, false)
                            databaseChangeEnabled = false
                        }
                    }
                }) }
            )
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
            if (showDisableAllConfirmation) {
                AppAlertDialog(
                    onDismissRequest = { showDisableAllConfirmation = false },
                    title = { Text("Вимкнути резервування?") },
                    text = { Text("Після підтвердження буде вимкнено всі режими й видалено лише автоматичні файли .stpb у вибраній папці. Інші файли не змінюватимуться.") },
                    confirmButton = {
                        TextButton(onClick = {
                            runCatching {
                                AutomaticBackupScheduler.disableAll(context)
                                AutomaticBackupSettings.setPeriodicEnabled(context, false)
                                AutomaticBackupSettings.setBackgroundEnabled(context, false)
                                AutomaticBackupSettings.setDatabaseChangeEnabled(context, false)
                                BackupPasswordStore.folder(context)?.let { uri ->
                                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                                        ?.listFiles()?.filter { it.name?.matches(Regex("СтеляПро_\\d{8}_\\d{6}\\.stpb")) == true }
                                        ?.forEach { it.delete() }
                                }
                                periodicEnabled = false
                                backgroundEnabled = false
                                databaseChangeEnabled = false
                                automaticStatus = "Автоматичне резервування вимкнено; автоматичні .stpb-копії видалено."
                            }.onFailure { automaticStatus = "Не вдалося завершити вимкнення: " + (it.message ?: "невідома помилка") }
                            showDisableAllConfirmation = false
                        }) { Text("Вимкнути й видалити копії") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDisableAllConfirmation = false }) { Text("Скасувати") }
                    }
                )
            }
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
                                System.loadLibrary("sqlcipher")
                                val verifier = androidx.room.Room.databaseBuilder(
                                    context.applicationContext,
                                    CeilingsDatabase::class.java,
                                    snapshot.absolutePath
                                ).openHelperFactory(
                                    net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.copyOf())
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
        System.loadLibrary("sqlcipher")
        val verifier = androidx.room.Room.databaseBuilder(context.applicationContext, CeilingsDatabase::class.java, file.absolutePath)
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.copyOf())).build()
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