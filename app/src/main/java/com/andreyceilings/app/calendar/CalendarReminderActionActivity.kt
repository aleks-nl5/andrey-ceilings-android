package com.andreyceilings.app.calendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native contextual actions must not bring MainActivity or its Compose layout to the foreground. */
class CalendarReminderActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the transient window edge-to-edge too, with no transition that resizes
        // the visible task behind the system resolver.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setDimAmount(0f)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        if (savedInstanceState != null || intent.data?.pathSegments?.lastOrNull() !in setOf("navigate", "dial", "message")) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { CalendarReminderRuntime.open(this@CalendarReminderActionActivity, intent) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fail closed; do not launch or consume an unverified notification.
            } finally {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }
}
