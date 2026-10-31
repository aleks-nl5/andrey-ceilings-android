package com.andreyceilings.shared.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Public monobank exchange rates; independent of the encrypted business database. */
const val CURRENCY_CACHE_MS: Long = 30L * 60L * 1000L

data class CurrencyRate(
    val title: String,
    val buy: Double,
    val sell: Double,
    /** Unix timestamp provided by monobank; 0 means unknown. */
    val setAtSeconds: Long
)

data class CurrencySnapshot(val rows: List<CurrencyRate>, val fetchedAtMillis: Long)

data class CurrencyRatesUiState(
    val snapshot: CurrencySnapshot? = null,
    val nowMillis: Long = 0L,
    val loading: Boolean = false,
    val message: String? = null,
    /** Incremented for every notification, including repeated identical text. */
    val messageVersion: Long = 0L
) {
    val remainingMillis: Long get() = currencyRefreshRemainingMillis(snapshot, nowMillis)
    val canRefresh: Boolean get() = !loading && remainingMillis == 0L
}

/**
 * Platform hosts own the HTTP client and non-sensitive persistent storage.
 * The common code owns parsing, cache compatibility, throttling and error handling.
 */
interface CurrencyRatesGateway {
    fun readCache(): String?
    fun saveCache(value: String)
    suspend fun fetchRatesJson(): String
}

private data class RatePair(val first: Int, val second: Int, val title: String)
private val wantedRates = listOf(
    RatePair(840, 980, "USD"),
    RatePair(978, 980, "EUR"),
    RatePair(978, 840, "EUR/USD")
)

fun currencyRefreshRemainingMillis(snapshot: CurrencySnapshot?, nowMillis: Long): Long {
    val fetched = snapshot?.fetchedAtMillis ?: return 0L
    // Clock rollback or corrupt/future cache must never lock out manual refresh.
    if (fetched <= 0L || fetched > nowMillis) return 0L
    return (CURRENCY_CACHE_MS - (nowMillis - fetched)).coerceAtLeast(0L)
}

private fun JsonObject.positiveDouble(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }

private fun JsonObject.positiveLong(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }

/**
 * Rate pairs are selected by ISO 4217 codes, never by array position.
 * A partial bank response preserves previously cached pairs. Missing bank
 * dates are not replaced with the device time (which would be misleading).
 */
fun parseMonobankRates(json: String, previous: CurrencySnapshot?, fetchedAtMillis: Long): CurrencySnapshot? {
    val items = try {
        Json.parseToJsonElement(json) as? JsonArray ?: return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val received = wantedRates.mapNotNull { pair ->
        val item = items.firstOrNull { element ->
            val obj = element as? JsonObject ?: return@firstOrNull false
            obj["currencyCodeA"]?.jsonPrimitive?.intOrNull == pair.first &&
                obj["currencyCodeB"]?.jsonPrimitive?.intOrNull == pair.second
        } as? JsonObject ?: return@mapNotNull null
        val cross = item.positiveDouble("rateCross")
        val buy = item.positiveDouble("rateBuy") ?: cross ?: return@mapNotNull null
        val sell = item.positiveDouble("rateSell") ?: cross ?: buy
        val priorDate = previous?.rows?.firstOrNull { it.title == pair.title }?.setAtSeconds ?: 0L
        CurrencyRate(pair.title, buy, sell, item.positiveLong("date") ?: priorDate)
    }
    if (received.isEmpty()) return null
    val merged = wantedRates.mapNotNull { pair ->
        received.firstOrNull { it.title == pair.title }
            ?: previous?.rows?.firstOrNull { it.title == pair.title }
    }
    return CurrencySnapshot(merged, fetchedAtMillis)
}

/** Reads existing Android SharedPreferences JSON without losing saved bank rates. */
fun decodeCurrencyCache(raw: String?): CurrencySnapshot? {
    if (raw.isNullOrBlank()) return null
    return try {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val fetchedAt = root.positiveLong("fetchedAt") ?: return null
        val entries = root["rows"] as? JsonArray ?: return null
        val rows = wantedRates.mapNotNull { pair ->
            val item = entries.firstOrNull {
                (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull == pair.title
            } as? JsonObject ?: return@mapNotNull null
            val buy = item.positiveDouble("buy") ?: return@mapNotNull null
            val sell = item.positiveDouble("sell") ?: return@mapNotNull null
            CurrencyRate(pair.title, buy, sell, item.positiveLong("date") ?: 0L)
        }
        if (rows.isEmpty()) null else CurrencySnapshot(rows, fetchedAt)
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun encodeCurrencyCache(snapshot: CurrencySnapshot): String = buildJsonObject {
    put("fetchedAt", snapshot.fetchedAtMillis)
    put("rows", buildJsonArray {
        snapshot.rows.forEach { row ->
            add(buildJsonObject {
                put("title", row.title)
                put("buy", row.buy)
                put("sell", row.sell)
                put("date", row.setAtSeconds)
                put("symbol", when (row.title) {
                    "USD" -> "$"
                    "EUR" -> "€"
                    else -> "€/$"
                })
            })
        }
    })
}.toString()

/** Stable formatting across KMP targets, with Ukrainian decimal comma. */
fun formatCurrencyRate(value: Double, crossRate: Boolean): String {
    val precision = if (crossRate) 1000L else 100L
    val scaled = kotlin.math.round(value * precision).toLong()
    val fraction = (scaled % precision).toString().padStart(if (crossRate) 3 else 2, '0')
    return (scaled / precision).toString() + "," + fraction + if (crossRate) " $" else " ₴"
}

class CurrencyRatesStore(
    private val gateway: CurrencyRatesGateway,
    private val nowMillis: () -> Long
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CurrencyRatesUiState(nowMillis = nowMillis()))
    val state: StateFlow<CurrencyRatesUiState> = mutableState
    private var initialized = false

    /** Start only while a host dashboard is displayed; no background polling. */
    suspend fun start() {
        mutex.withLock {
            if (initialized) return
            initialized = true
            val cached = runCatching { decodeCurrencyCache(gateway.readCache()) }.getOrNull()
            mutableState.value = mutableState.value.copy(snapshot = cached, nowMillis = nowMillis())
        }
        if (mutableState.value.canRefresh) refresh(automatic = true)
    }

    fun tick() {
        mutableState.value = mutableState.value.copy(nowMillis = nowMillis())
    }

    /** Avoid clearing a newer message when an older five-second timer expires. */
    fun dismissMessage(version: Long) {
        mutableState.update { current ->
            if (current.message != null && current.messageVersion == version) current.copy(message = null)
            else current
        }
    }

    suspend fun refresh() = refresh(automatic = false)

    private suspend fun refresh(automatic: Boolean) {
        mutex.withLock {
            val current = mutableState.value
            val now = nowMillis()
            if (current.loading || currencyRefreshRemainingMillis(current.snapshot, now) > 0L) return
            mutableState.value = current.copy(loading = true, message = null, nowMillis = now)
            try {
                val result = parseMonobankRates(gateway.fetchRatesJson(), current.snapshot, nowMillis())
                if (result == null) {
                    mutableState.value = mutableState.value.copy(
                        message = "Не вдалося отримати курси. Показано збережені дані.",
                        messageVersion = current.messageVersion + 1L
                    )
                } else {
                    val saved = runCatching { gateway.saveCache(encodeCurrencyCache(result)) }.isSuccess
                    val changed = result.rows != current.snapshot?.rows
                    mutableState.value = mutableState.value.copy(
                        snapshot = result,
                        message = when {
                            !saved -> "Курси отримано, але кеш не вдалося зберегти."
                            automatic -> null
                            changed -> "Курси оновлено."
                            else -> "Банк не змінив встановлені курси."
                        },
                        messageVersion = current.messageVersion + 1L
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    message = "Не вдалося отримати курси. Показано збережені дані.",
                        messageVersion = current.messageVersion + 1L
                )
            } finally {
                mutableState.value = mutableState.value.copy(loading = false, nowMillis = nowMillis())
            }
        }
    }
}
