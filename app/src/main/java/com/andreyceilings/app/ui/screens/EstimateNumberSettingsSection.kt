package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.ui.theme.AppExposedDropdownMenu
import com.andreyceilings.shared.estimate.EstimateNumberContext
import com.andreyceilings.shared.estimate.EstimateNumberPolicy
import com.andreyceilings.shared.estimate.EstimateNumbering
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * Formats are persisted through the shared SQLDelight repository.
 * Editing and preview never allocate a UUID or a sequence number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EstimateNumberSettingsSection(database: CeilingsDatabase?) {
    val settings = remember(database) { database?.estimateNumberSettings() }
    val scope = rememberCoroutineScope()
    var savedPolicy by remember(database) { mutableStateOf<EstimateNumberPolicy?>(null) }
    var draft by remember(database) { mutableStateOf(EstimateNumberPolicy()) }
    var loading by remember(database) { mutableStateOf(database != null) }
    var saving by remember(database) { mutableStateOf(false) }
    var feedback by remember(database) { mutableStateOf<String?>(null) }
    var loadError by remember(database) { mutableStateOf<String?>(null) }
    var reloadRequest by remember(database) { mutableIntStateOf(0) }
    var presetsExpanded by remember(database) { mutableStateOf(false) }

    LaunchedEffect(settings, reloadRequest) {
        if (settings == null) return@LaunchedEffect
        loading = true
        loadError = null
        runCatching { settings.load() }
            .onSuccess {
                savedPolicy = it
                draft = it
                loading = false
                feedback = null
            }
            .onFailure {
                loading = false
                loadError = "Не вдалося завантажити формат: ${it.message ?: "помилка читання"}"
            }
    }

    val presets = listOf(
        "Стандартний" to "{prefix}-{year}-{sequence}-{uuid}",
        "Рік і номер" to "{year}-{prefix}-{uuid}",
        "Порядковий і UUID" to "{prefix}_{sequence}_{uuid}",
        "Лише UUID" to "{uuid}"
    )
    val selectedPreset = presets.firstOrNull { it.second == draft.template }?.first ?: "Власний шаблон"
    val errors = remember(draft) { EstimateNumbering.validate(draft) }
    val preview = remember(draft) {
        if (errors.isNotEmpty()) null else runCatching {
            EstimateNumbering.render(
                draft,
                EstimateNumberContext(
                    seriesUuid = "12345678-1234-4abc-8def-0123456789ab",
                    year = Calendar.getInstance().get(Calendar.YEAR),
                    localSequence = 125L
                )
            )
        }.getOrNull()
    }
    val editable = settings != null && !loading && !saving && savedPolicy != null && loadError == null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Нумерація кошторисів", style = MaterialTheme.typography.titleSmall)
        Text(
            "Формат діятиме лише для нових кошторисів. Номери вже збережених документів, " +
                "їхніх копій і PDF не змінюються.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (settings == null) {
            Text("Зашифрована база даних ще недоступна.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (loading) {
            Text("Завантаження налаштувань…", style = MaterialTheme.typography.bodySmall)
        } else if (loadError != null) {
            Text(loadError!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { reloadRequest++ }) { Text("Повторити") }
        }

        ExposedDropdownMenuBox(
            expanded = presetsExpanded && editable,
            onExpandedChange = { if (editable) presetsExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedPreset,
                onValueChange = {},
                enabled = editable,
                readOnly = true,
                label = { Text("Готові формати") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(presetsExpanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = editable)
                    .fillMaxWidth()
            )
            AppExposedDropdownMenu(
                expanded = presetsExpanded && editable,
                onDismissRequest = { presetsExpanded = false }
            ) {
                presets.forEach { (label, template) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        draft = draft.copy(template = template)
                        feedback = null
                        presetsExpanded = false
                    })
                }
            }
        }

        OutlinedTextField(
            value = draft.template,
            onValueChange = { draft = draft.copy(template = it); feedback = null },
            enabled = editable,
            label = { Text("Шаблон номера") },
            supportingText = { Text("{prefix} · {year} · {sequence} · {uuid}") },
            isError = errors.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3
        )
        OutlinedTextField(
            value = draft.prefix,
            onValueChange = { draft = draft.copy(prefix = it); feedback = null },
            enabled = editable && draft.template.contains("{prefix}"),
            label = { Text("Префікс") },
            supportingText = { Text("Латинські літери, цифри, - або _, до 20 символів") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (draft.template.contains("{sequence}")) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Мінімальна кількість цифр", style = MaterialTheme.typography.bodyMedium)
                Text(draft.sequenceWidth.toString(), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = draft.sequenceWidth.toFloat(),
                onValueChange = { draft = draft.copy(sequenceWidth = it.toInt().coerceIn(1, 12)); feedback = null },
                enabled = editable,
                valueRange = 1f..12f,
                steps = 10
            )
        }

        if (errors.isNotEmpty()) {
            errors.forEach {
                Text(numberPolicyErrorUa(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        } else if (preview != null) {
            Text("Приклад майбутнього номера (не резервується):",
                style = MaterialTheme.typography.labelMedium)
            Text(preview, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            Text("У прикладі використано умовний UUID і порядковий номер 125. " +
                    "Реальний номер буде виділено автоматично під час збереження.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val selected = draft
                    saving = true
                    feedback = null
                    scope.launch {
                        runCatching { checkNotNull(settings).save(selected) }
                            .onSuccess {
                                savedPolicy = it
                                draft = it
                                feedback = "Формат збережено. Його отримають наступні кошториси."
                            }
                            .onFailure {
                                feedback = "Не вдалося зберегти формат: ${it.message ?: "помилка запису"}"
                            }
                        saving = false
                    }
                },
                enabled = editable && errors.isEmpty() && draft != savedPolicy
            ) { Text(if (saving) "Зберігаю…" else "Зберегти формат") }

            OutlinedButton(
                onClick = { savedPolicy?.let { draft = it }; feedback = null },
                enabled = editable && draft != savedPolicy
            ) { Text("Скасувати") }
        }

        feedback?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Не вдалося")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary)
        }
        Text(
            "Повний UUID обов’язковий у шаблоні. Локальна послідовність може повторюватися " +
                "на різних офлайн-пристроях, тому її не слід використовувати без UUID.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun numberPolicyErrorUa(message: String): String = when {
    message.contains("1–96") -> "Шаблон має містити від 1 до 96 символів."
    message.contains("unsupported") -> "Шаблон містить недопустимі символи."
    message.contains("{uuid} exactly once") -> "Шаблон повинен містити {uuid} рівно один раз."
    message.contains("unknown placeholder") -> "У шаблоні є невідомий параметр."
    message.contains("repeat placeholders") -> "Не можна повторювати параметри в шаблоні."
    message.contains("invalid placeholder") -> "Перевірте фігурні дужки параметрів."
    message.contains("Prefix") -> "Префікс: 1–20 латинських літер, цифр, - або _."
    message.contains("Sequence width") -> "Кількість цифр має бути від 1 до 12."
    message.contains("exceed") -> "Максимальна довжина номера — 128 символів."
    else -> message
}
