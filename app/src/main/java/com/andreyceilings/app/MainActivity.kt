package com.andreyceilings.app

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableStateOf
import com.andreyceilings.app.data.PerformanceDiagnosticsSettings
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
                    if (BuildConfig.DEBUG && PerformanceDiagnosticsSettings.isEnabled(applicationContext)) {
                        Log.d(
                            "CeilingsPerf",
                            "database_open_ms=${databaseOpenedAt - databaseOpenStartedAt} " +
                                "demo_seed_ms=${initializationFinishedAt - databaseOpenedAt} " +
                                "activity_to_database_ready_ms=${initializationFinishedAt - activityStartedAt}"
                        )
                    }
                    database
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
