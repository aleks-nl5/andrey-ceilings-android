package com.andreyceilings.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableStateOf
import com.andreyceilings.app.data.PerformanceDiagnosticsLog
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.app.data.backup.BackupPasswordStore
import com.andreyceilings.app.data.backup.AutomaticBackupScheduler
import com.andreyceilings.app.data.backup.AutomaticBackupSettings
import com.andreyceilings.app.ui.theme.CeilingsTheme
import com.andreyceilings.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val databaseStatus = mutableStateOf("Перевірка зашифрованої бази…")
    private val databaseState = mutableStateOf<CeilingsDatabase?>(null)
    private val themeMode = mutableStateOf(ThemeMode.SYSTEM)

    private fun saveThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        getSharedPreferences("app_preferences", MODE_PRIVATE).edit()
            .putString("theme_mode", mode.name).apply()
    }

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
        themeMode.value = runCatching {
            ThemeMode.valueOf(getSharedPreferences("app_preferences", MODE_PRIVATE)
                .getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        val activityStartedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            try {
                val db = withContext(Dispatchers.IO) {
                    val databaseOpenStartedAt = SystemClock.elapsedRealtime()
                    val database = CeilingsDatabase.get(applicationContext)
                    database.openHelper.writableDatabase
                    val databaseOpenedAt = SystemClock.elapsedRealtime()
                    com.andreyceilings.app.data.db.DemoPriceList.seedIfEmpty(database.priceItems())
                    val initializationFinishedAt = SystemClock.elapsedRealtime()
                    PerformanceDiagnosticsLog.record(
                        applicationContext,
                        "database_open_ms=${databaseOpenedAt - databaseOpenStartedAt} " +
                            "demo_seed_ms=${initializationFinishedAt - databaseOpenedAt} " +
                            "activity_to_database_ready_ms=${initializationFinishedAt - activityStartedAt}"
                    )
                    database
                }
                databaseState.value = db
                databaseStatus.value = "Зашифрована база даних активна"
            } catch (_: Exception) {
                databaseStatus.value = "Помилка відкриття захищеної бази. Дані не завантажено."
            }
        }
        setContent {
            CeilingsTheme(mode = themeMode.value) {
                CeilingsApp(
                    themeMode = themeMode.value,
                    onThemeModeChange = ::saveThemeMode,
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
