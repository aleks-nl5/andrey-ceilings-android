package com.andreyceilings.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.andreyceilings.app.data.db.CeilingsDatabase
import com.andreyceilings.shared.db.PriceRepository
import com.andreyceilings.shared.prices.PriceCatalogItem
import com.andreyceilings.shared.prices.PriceCatalogScreen
import com.andreyceilings.shared.prices.PriceCatalogStore
import com.andreyceilings.shared.prices.PriceCatalogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Android-only platform boundary (insets, secure driver lifecycle and dispatcher).
 * The same KMP catalog/store is used by Windows; no duplicate Android DAO mapping,
 * price validation, identity logic or CRUD rules remain in this screen.
 */
@Composable
internal fun PriceListScreen(database: CeilingsDatabase?, darkTheme: Boolean) {
    val store = remember(database) {
        database?.let {
            PriceCatalogStore(PriceRepository(it.database), nowMillis = System::currentTimeMillis)
        }
    }
    val priceFlow = remember(store) {
        store?.items ?: flowOf<List<PriceCatalogItem>>(emptyList())
    }
    val catalog by priceFlow.collectAsState(initial = emptyList())

    PriceCatalogTheme(darkTheme) {
        PriceCatalogScreen(
            items = catalog,
            databaseAvailable = store != null,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
            ),
            onSave = { original, draft ->
                withContext(Dispatchers.IO) {
                    checkNotNull(store) { "SQLCipher price repository unavailable" }
                        .save(original, draft)
                }
            },
            onDelete = { item ->
                withContext(Dispatchers.IO) {
                    checkNotNull(store) { "SQLCipher price repository unavailable" }.delete(item)
                }
            }
        )
    }
}
