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
            }
        }
    }
}
