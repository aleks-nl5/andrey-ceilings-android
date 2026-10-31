package com.andreyceilings.shared.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Identical monobank card for Android, Windows and the future iOS host. */
@Composable
fun CurrencyRatesCard(
    state: CurrencyRatesUiState,
    onRefresh: (() -> Unit)?,
    onMonobank: (() -> Unit)?,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val refreshEnabled = onRefresh != null && state.canRefresh
    val refreshLabel = when {
        state.loading -> "…"
        state.remainingMillis > 0L -> ((state.remainingMillis + 59_999L) / 60_000L).toString() + " хв"
        else -> "Оновити"
    }
    val snapshot = state.snapshot

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "КУРС ВАЛЮТ",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                val badgeBackground = if (isDark) Color.White else Color.Black
                val badgeForeground = if (isDark) Color.Black else Color.White
                Box(
                    Modifier.height(19.dp)
                        .clip(CircleShape)
                        .background(badgeBackground, CircleShape)
                        .clickable(enabled = onMonobank != null) { onMonobank?.invoke() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "monobank",
                        color = badgeForeground,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.weight(1f))
                val refreshBackground = if (isDark)
                    Color.White.copy(alpha = if (refreshEnabled) 0.14f else 0.07f)
                else
                    Color.Black.copy(alpha = if (refreshEnabled) 0.08f else 0.04f)
                Surface(
                    modifier = Modifier.height(25.dp).clip(CircleShape)
                        .clickable(enabled = refreshEnabled) { onRefresh?.invoke() },
                    shape = CircleShape,
                    color = refreshBackground,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Оновити курси",
                            modifier = Modifier.size(15.dp)
                        )
                        Text(refreshLabel, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Валюта", Modifier.weight(1.35f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Купівля", Modifier.weight(1f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Продаж", Modifier.weight(1f), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (snapshot?.rows.isNullOrEmpty()) {
                Text(
                    if (state.loading) "Завантаження курсів…" else "Курси недоступні. Перевірте інтернет.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                snapshot!!.rows.forEach { rate ->
                    val symbol = when (rate.title) {
                        "USD" -> "$"
                        "EUR" -> "€"
                        else -> "€/$"
                    }
                    val hue = when (rate.title) {
                        "USD" -> Color(0xFF16834A)
                        "EUR" -> Color(0xFF2878D0)
                        else -> Color(0xFF4F46E5)
                    }
                    val symbolColor = when (rate.title) {
                        "USD" -> if (isDark) Color(0xFF75D9A1) else Color(0xFF0D693A)
                        "EUR" -> if (isDark) Color(0xFF8CC4FF) else Color(0xFF155FA8)
                        else -> if (isDark) Color(0xFFB8B0FF) else Color(0xFF3D36A8)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(29.dp).background(
                                hue.copy(alpha = 0.184f)
                                    .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
                                CircleShape
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                symbol, color = symbolColor, fontWeight = FontWeight.Bold,
                                fontSize = if (symbol.length > 1) 9.sp else 16.sp
                            )
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            rate.title, Modifier.weight(1.35f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            formatCurrencyRate(rate.buy, rate.title == "EUR/USD"),
                            Modifier.weight(1f), textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        Text(
                            formatCurrencyRate(rate.sell, rate.title == "EUR/USD"),
                            Modifier.weight(1f), textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val latestRateDate = snapshot?.rows?.maxOfOrNull { it.setAtSeconds }
                Text(
                    "Встановлено: " + if (latestRateDate != null && latestRateDate > 0L)
                        formatDate(latestRateDate * 1000L) else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Оновлено: " + if (snapshot != null) formatDate(snapshot.fetchedAtMillis) else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
            state.message?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
