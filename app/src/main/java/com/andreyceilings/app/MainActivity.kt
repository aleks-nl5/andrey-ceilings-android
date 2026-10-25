package com.andreyceilings.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableStateOf
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.backup.BackupPasswordStore
import com.andreyceilings.app.data.backup.AutomaticBackupScheduler
import com.andreyceilings.app.data.backup.AutomaticBackupSettings
import com.andreyceilings.app.ui.theme.CeilingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val databaseStatus = mutableStateOf("Перевірка зашифрованої бази…")
    private val databaseState = mutableStateOf<CeilingsDatabase?>(null)

    override fun onStop() {
        if (AutomaticBackupSettings.backgroundEnabled(applicationContext) &&
            AutomaticBackupSettings.anyEnabled(applicationContext) &&
            BackupPasswordStore.folder(applicationContext) != null
        ) {
            AutomaticBackupScheduler.triggerOnBackground(applicationContext)
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val db = withContext(Dispatchers.IO) {
                    CeilingsDatabase.get(applicationContext).also { database ->
                        database.openHelper.writableDatabase
                        com.andreyceilings.app.data.db.DemoPriceList.seedIfEmpty(database.priceItems())
                    }
                }
                databaseState.value = db
                databaseStatus.value = "Зашифрована база даних активна"
            } catch (_: Exception) {
                databaseStatus.value = "Помилка відкриття захищеної бази. Дані не завантажено."
            }
        }
        setContent {
            CeilingsTheme {
                CeilingsApp(
                    databaseStatus = databaseStatus.value,
                    database = databaseState.value,
                    onDatabaseReplaced = { refreshed ->
                        databaseState.value = refreshed
                        databaseStatus.value = "Зашифрована база даних активна"
                    }
                )
            }
        }
    }
}
