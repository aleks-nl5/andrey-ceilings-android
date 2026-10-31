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
import com.andreyceilings.app.data.db.PriceItemEntity
import com.andreyceilings.shared.prices.PriceCatalogItem
import com.andreyceilings.shared.prices.PriceCatalogScreen
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * Temporary Android host adapter for the single shared CMP price-list screen.
 * Existing SQLCipher/WAL open, restore, backup and DAO compatibility remain unchanged.
 */
@Composable
internal fun PriceListScreen(database: CeilingsDatabase?) {
    val priceFlow = remember(database) {
        database?.priceItems()?.observeAll() ?: flowOf<List<PriceItemEntity>>(emptyList())
    }
    val rows by priceFlow.collectAsState(initial = emptyList())
    val catalog = remember(rows) {
        rows.map { row ->
            PriceCatalogItem(
                id = row.id,
                code = row.code,
                title = row.title,
                category = row.category,
                unit = row.unit,
                price = row.price
            )
        }
    }
    val priceDao = database?.priceItems()
    PriceCatalogScreen(
        items = catalog,
        databaseAvailable = database != null,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
        ),
        onSave = { original, draft ->
            val dao = checkNotNull(priceDao) { "SQLCipher price repository unavailable" }
            dao.upsert(
                PriceItemEntity(
                    id = original?.id ?: 0L,
                    code = original?.code ?: "custom.${UUID.randomUUID()}",
                    title = draft.title,
                    category = draft.category,
                    unit = draft.unit,
                    price = draft.price
                )
            )
        },
        onDelete = { item ->
            checkNotNull(priceDao) { "SQLCipher price repository unavailable" }.deleteById(item.id)
        }
    )
}
