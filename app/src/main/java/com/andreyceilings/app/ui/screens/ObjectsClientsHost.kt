package com.andreyceilings.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.objects.ContactAction
import com.andreyceilings.shared.objects.ObjectsClientsRows
import com.andreyceilings.shared.objects.ObjectsClientsScreen
import com.andreyceilings.shared.objects.ObjectsClientsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Thin Android-only boundary: real SQLCipher session + OS intents.
 * Search, edits, confirmation and CRUD rules are shared in commonMain.
 */
@Composable
internal fun ObjectsClientsHost(
    database: CeilingsDatabase?,
    openCreateProject: Boolean,
    onCreateProjectConsumed: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(database) {
        database?.let { ObjectsClientsStore(it.database, System::currentTimeMillis) }
    }
    val flow = remember(store) {
        store?.rows ?: flowOf(ObjectsClientsRows(emptyList(), emptyList()))
    }
    val rows by flow.collectAsState(
        initial = ObjectsClientsRows(emptyList(), emptyList())
    )

    fun openIntent(uri: Uri, dial: Boolean = false) {
        val intent = Intent(if (dial) Intent.ACTION_DIAL else Intent.ACTION_VIEW, uri)
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, "Не вдалося відкрити відповідний застосунок.",
                    Toast.LENGTH_LONG).show()
            }
    }

    ObjectsClientsScreen(
        rows = rows,
        databaseAvailable = store != null,
        requestCreateProject = openCreateProject,
        onCreateProjectConsumed = onCreateProjectConsumed,
        contentPadding = PaddingValues(
            start = 0.dp, end = 0.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp
        ),
        onSaveClient = { id, draft ->
            withContext(Dispatchers.IO) {
                checkNotNull(store) { "База даних недоступна." }.saveClient(id, draft)
            }
        },
        onSaveProject = { id, draft ->
            withContext(Dispatchers.IO) {
                checkNotNull(store) { "База даних недоступна." }.saveProject(id, draft)
            }
        },
        onDeleteClient = { id ->
            withContext(Dispatchers.IO) {
                checkNotNull(store) { "База даних недоступна." }.deleteClient(id)
            }
        },
        onDeleteProject = { id ->
            withContext(Dispatchers.IO) {
                checkNotNull(store) { "База даних недоступна." }.deleteProject(id)
            }
        },
        onDial = { phone ->
            if (phone.isNotBlank()) openIntent(Uri.fromParts("tel", phone.trim(), null), dial = true)
        },
        onExternalAction = { action, raw ->
            val uri = when (action) {
                ContactAction.GOOGLE_MAPS ->
                    "https://www.google.com/maps/search/?api=1&query=${Uri.encode(raw)}"
                ContactAction.WAZE ->
                    "https://waze.com/ul?q=${Uri.encode(raw)}&navigate=yes"
                ContactAction.WHATSAPP -> {
                    val digits = raw.filter(Char::isDigit)
                    if (raw.trim().startsWith("+") && digits.length in 8..15) {
                        "https://wa.me/$digits"
                    } else null // Never guess a missing international country code.
                }
                ContactAction.TELEGRAM -> {
                    val phone = raw.filter(Char::isDigit)
                    if (raw.trim().startsWith("+") && phone.length in 8..15) {
                        "tg://resolve?phone=$phone"
                    } else null
                }
                ContactAction.VIBER -> {
                    if (raw.trim().startsWith("+")) {
                        "viber://chat?number=${Uri.encode(raw.trim())}"
                    } else null
                }
            }
            if (uri == null) {
                Toast.makeText(context,
                    "Для цього месенджера потрібен міжнародний номер із кодом країни.",
                    Toast.LENGTH_LONG).show()
            } else openIntent(Uri.parse(uri))
        }
    )
}
