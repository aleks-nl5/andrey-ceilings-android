package com.andreyceilings.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreyceilings.app.data.backup.AutomaticBackupSettings
import com.andreyceilings.app.data.backup.BackupSchedule
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticBackupSettingsTest {
    @Test fun removingLegacyModePreservesBothActiveModesAndSchedule() {
        val app: Context = ApplicationProvider.getApplicationContext()
        val name = "backup_modes_test_${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }

        try {
            for (periodic in listOf(false, true)) {
                for (background in listOf(false, true)) {
                    for (legacy in listOf(false, true)) {
                        assertTrue(prefs.edit().clear()
                            .putBoolean("periodic_enabled", periodic)
                            .putBoolean("background_enabled", background)
                            .putBoolean("database_change_enabled", legacy)
                            .putString("schedule", BackupSchedule.WEEKLY.name)
                            .putString("unrelated", "preserve")
                            .commit())

                        assertEquals(periodic || background, AutomaticBackupSettings.anyEnabled(isolated))
                        repeat(2) { AutomaticBackupSettings.removeLegacyDatabaseChangeMode(isolated) }
                        assertFalse(prefs.contains("database_change_enabled"))
                        assertEquals(periodic, AutomaticBackupSettings.periodicEnabled(isolated))
                        assertEquals(background, AutomaticBackupSettings.backgroundEnabled(isolated))
                        assertEquals(periodic || background, AutomaticBackupSettings.anyEnabled(isolated))
                        assertEquals(BackupSchedule.WEEKLY, AutomaticBackupSettings.schedule(isolated))
                        assertEquals("preserve", prefs.getString("unrelated", null))
                    }
                }
            }
        } finally {
            app.deleteSharedPreferences(name)
        }
    }
}
