package com.andreyceilings.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CURRENCY_CACHE_KEY = "monobank_currency_cache"
private const val CURRENCY_CACHE_MS = 30 * 60 * 1000L
private val monoPink = Color(0xFFE5007D)

private data class CurrencyRow(
    val title: String,
    val symbol: String,
    val color: Color,
    val buy: Double,
    val sell: Double,
    val date: Long
)
private data class CurrencyCache(val rows: List<CurrencyRow>, val fetchedAt: Long)

@Composable
internal fun CurrencyRatesCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("currency_rates", Context.MODE_PRIVATE) }
    var cache by remember { mutableStateOf(readCurrencyCache(prefs.getString(CURRENCY_CACHE_KEY, null))) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    LaunchedEffect(Unit) {
        if (cache == null || System.currentTimeMillis() - cache!!.fetchedAt >= CURRENCY_CACHE_MS) {
            loading = true
            fetchCurrencyRates(cache)?.let {
                cache = it
                prefs.edit().putString(CURRENCY_CACHE_KEY, encodeCurrencyCache(it)).apply()
            }
            loading = false
        }
    }
    val remaining = cache?.let { (CURRENCY_CACHE_MS - (now - it.fetchedAt)).coerceAtLeast(0L) } ?: 0L
    val locked = remaining > 0L

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("КУРС ВАЛЮТ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("monobank", color = monoPink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        enabled = !loading && !locked,
                        onClick = {
                            loading = true
                            scope.launch {
                                val old = cache
                                val fresh = fetchCurrencyRates(old)
                                if (fresh != null) {
                                    val changed = old != null && fresh.rows.any { row ->
                                        old.rows.any { it.title == row.title && row.date > it.date }
                                    }
                                    cache = fresh
                                    prefs.edit().putString(CURRENCY_CACHE_KEY, encodeCurrencyCache(fresh)).apply()
                                    message = if (changed) {
                                        "Курси змінено банком: " + formatCurrencyDate(fresh.rows.maxOf { it.date })
                                    } else {
                                        "Банк не змінив час встановлення курсів."
                                    }
                                } else {
                                    message = "Не вдалося отримати курси. Показано збережені дані."
                                }
                                loading = false
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Оновити курси", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(if (loading) "…" else if (locked) "${(remaining + 59_999) / 60_000} хв" else "Оновити", fontSize = 12.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Валюта", Modifier.weight(1.35f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Купівля", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Продаж", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (cache?.rows.isNullOrEmpty()) {
                Text(if (loading) "Завантаження курсів…" else "Курси недоступні. Перевірте інтернет.", style = MaterialTheme.typography.bodySmall)
            } else {
                cache!!.rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(29.dp).background(row.color, CircleShape), contentAlignment = Alignment.Center) {
                            Text(row.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (row.symbol.length > 1) 9.sp else 16.sp)
                        }
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(row.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("Встановлено: " + formatCurrencyDate(row.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                        }
                        Text(row.buy.currencyText(row.title == "EUR/USD"), Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
                        Text(row.sell.currencyText(row.title == "EUR/USD"), Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Дані отримано: " + (cache?.fetchedAt?.let(::formatCurrencyDate) ?: "—"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private suspend fun fetchCurrencyRates(previous: CurrencyCache?): CurrencyCache? = withContext(Dispatchers.IO) {
    try {
        val connection = URL("https://api.monobank.ua/bank/currency").openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        val text = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val json = JSONArray(text)
        val wanted = listOf(
            Triple(840, 980, "USD"),
            Triple(978, 980, "EUR"),
            Triple(978, 840, "EUR/USD")
        )
        val received = wanted.mapNotNull { (from, to, title) ->
            var obj: JSONObject? = null
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                if (item.optInt("currencyCodeA") == from && item.optInt("currencyCodeB") == to) {
                    obj = item
                    break
                }
            }
            val rate = obj ?: return@mapNotNull null
            val cross = rate.optDouble("rateCross", Double.NaN)
            val buy = rate.optDouble("rateBuy", Double.NaN).takeIf { it.isFinite() }
                ?: cross.takeIf { it.isFinite() } ?: return@mapNotNull null
            val sell = rate.optDouble("rateSell", Double.NaN).takeIf { it.isFinite() }
                ?: cross.takeIf { it.isFinite() } ?: buy
            val prior = previous?.rows?.firstOrNull { it.title == title }
            val date = rate.optLong("date", 0L).takeIf { it > 0 } ?: prior?.date ?: System.currentTimeMillis() / 1000
            val symbol = when (title) { "USD" -> "$"; "EUR/USD" -> "€/$"; else -> "€" }
            val color = when (title) { "USD" -> Color(0xFF16834A); "EUR/USD" -> Color(0xFF4F46E5); else -> Color(0xFF2878D0) }
            CurrencyRow(title, symbol, color, buy, sell, date)
        }
        if (received.isEmpty()) null else {
            // Keep cached values for any currency pair omitted by a partial API response.
            val merged = wanted.mapNotNull { (_, _, title) ->
                received.firstOrNull { it.title == title } ?: previous?.rows?.firstOrNull { it.title == title }
            }
            CurrencyCache(merged, System.currentTimeMillis())
        }
    } catch (_: Exception) {
        null
    }
}

private fun readCurrencyCache(raw: String?): CurrencyCache? = try {
    if (raw.isNullOrBlank()) null else {
        val root = JSONObject(raw)
        val array = root.getJSONArray("rows")
        val rows = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val symbol = o.getString("symbol")
            val color = when (symbol) { "$" -> Color(0xFF16834A); "€" -> Color(0xFF2878D0); else -> Color(0xFF4F46E5) }
            CurrencyRow(o.getString("title"), symbol, color, o.getDouble("buy"), o.getDouble("sell"), o.getLong("date"))
        }
        CurrencyCache(rows, root.getLong("fetchedAt"))
    }
} catch (_: Exception) { null }

private fun encodeCurrencyCache(cache: CurrencyCache) = JSONObject().apply {
    put("fetchedAt", cache.fetchedAt)
    put("rows", JSONArray().apply {
        cache.rows.forEach { r ->
            put(JSONObject().put("title", r.title).put("symbol", r.symbol).put("buy", r.buy).put("sell", r.sell).put("date", r.date))
        }
    })
}.toString()

private fun Double.currencyText(crossRate: Boolean = false) =
    String.format(Locale("uk", "UA"), if (crossRate) "%.1f" else "%.2f", this)

private fun formatCurrencyDate(seconds: Long): String =
    if (seconds <= 0) "—" else SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale("uk", "UA")).format(Date(seconds * 1000))
