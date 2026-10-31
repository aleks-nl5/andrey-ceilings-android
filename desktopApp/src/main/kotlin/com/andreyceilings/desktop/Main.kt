package com.andreyceilings.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.andreyceilings.shared.prices.PriceCatalogItem
import com.andreyceilings.shared.prices.PriceCatalogScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "СтеляПро — Прайс") {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) { DesktopPriceHost() }
        }
    }
}

/**
 * Windows preview of the *same* CMP price catalog used by Android.
 * Each user unlocks a local encrypted SQLCipher catalog with their own password.
 * Full OS-keystore integration, backup and desktop packaging remain in issue #42.
 */
@Composable
private fun DesktopPriceHost() {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DesktopPriceDatabase?>(null) }
    var password by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var unlocking by remember { mutableStateOf(false) }

    // Close the exact previous session, never a newly assigned replacement.
    val database = session
    DisposableEffect(database) {
        onDispose { database?.close() }
    }
    if (database != null) {
        val priceFlow = remember(database) { database.prices.observeAll() }
        val prices by priceFlow.collectAsState(initial = emptyList())
        val items = remember(prices) {
            prices.map { row ->
                PriceCatalogItem(row.id, row.code, row.title, row.category, row.unit, row.price)
            }
        }
        PriceCatalogScreen(
            items = items,
            databaseAvailable = true,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            onSave = { original, draft ->
                withContext(Dispatchers.IO) {
                    database.prices.upsertReturningId(
                        id = original?.id ?: 0L,
                        code = original?.code ?: "custom.${UUID.randomUUID()}",
                        title = draft.title,
                        category = draft.category,
                        unit = draft.unit,
                        price = draft.price,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            },
            onDelete = { item ->
                withContext(Dispatchers.IO) { database.prices.delete(item.id) }
            }
        )
    } else {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(0.66f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Прайс-лист", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Введіть пароль для відкриття захищеного локального прайсу Windows. " +
                        "Якщо його ще немає, буде створено новий зашифрований каталог. " +
                        "Збережіть пароль: без нього відновити цей каталог неможливо."
                )
                if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    Text("Шифрований Windows-драйвер доступний лише у збірці Windows.",
                        color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль локальної БД (від 10 символів)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !unlocking,
                        modifier = Modifier.fillMaxWidth()
                    )
                    unlockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = password.length >= 10 && !unlocking,
                        onClick = {
                            if (!unlocking) {
                                unlocking = true
                                unlockError = null
                                val secret = password.toCharArray()
                                password = ""
                                scope.launch {
                                    try {
                                        session = withContext(Dispatchers.IO) {
                                            DesktopPriceDatabase.open(secret)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        unlockError = "Не вдалося відкрити зашифрований прайс. " +
                                            "Перевірте пароль, доступ до файлів і стан БД. " +
                                            "Нова порожня БД замість існуючої не створюється."
                                    } finally {
                                        secret.fill('\u0000')
                                        unlocking = false
                                    }
                                }
                            }
                        }
                    ) { Text(if (unlocking) "Відкриття…" else "Відкрити прайс") }
                }
            }
        }
    }
}
