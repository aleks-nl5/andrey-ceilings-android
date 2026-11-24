package com.andreyceilings.shared.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.livepricing.*
import com.andreyceilings.shared.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun LiveEstimatePreview.bucketAmount(bucket: PricingBucket): Long? {
    if (fatalProblems.isNotEmpty()) return null
    val known = priceableLines.any { it.bucket == bucket }
    val incomplete = unresolvedItems.any { it.bucket == bucket } ||
        (bucket == PricingBucket.CONFIRMED && mandatoryProblems.isNotEmpty())
    if (!known && incomplete) return null
    return if (bucket == PricingBucket.CONFIRMED) confirmedTotalCents else potentialTotalCents
}

@Composable
internal fun WorkspaceLiveCost(
    result: DraftPricingResult?,
    modifier: Modifier,
    bottomInset: Dp,
    onElement: (String) -> Boolean
) {
    val preview = (result as? DraftPricingResult.Loaded)?.preview
    var unavailableLink by remember { mutableStateOf(false) }
    val groups = if (preview?.let {
            it.priceableLines.any { it.bucket == PricingBucket.POTENTIAL } || it.potentialIncomplete
        } == true) PricingBucket.entries else listOf(PricingBucket.CONFIRMED)
    Column(modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                groups.forEach { bucket ->
                    val subtotal = preview?.bucketAmount(bucket)
                    val title = bucketLabel(bucket)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(title, style = MaterialTheme.typography.labelSmall)
                        Text(if (subtotal == null) stringResource(Res.string.p5d_unknown)
                            else if (bucket == PricingBucket.POTENTIAL) stringResource(Res.string.p5d_plus_amount, measurementMoney(subtotal))
                            else measurementMoney(subtotal), style = MaterialTheme.typography.titleMedium)
                    }
                }
                val issues = when (result) {
                    null -> stringResource(Res.string.p5d_amount_pending)
                    is DraftPricingResult.Unavailable -> workspacePricingProblem(result.reason)
                    is DraftPricingResult.Loaded -> (result.preview.fatalProblems + result.preview.mandatoryProblems)
                        .map { workspacePricingProblem(it.code) }.distinct().joinToString("\n")
                        .ifBlank { if (result.preview.unresolvedItems.isNotEmpty()) stringResource(Res.string.p5d_amount_incomplete) else "" }
                }
                if (issues.isNotBlank()) WorkspaceCostHelp(issues)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(bottom = bottomInset + 8.dp)) {
            for (bucket in groups) {
                val priced = preview?.priceableLines.orEmpty().filter { it.bucket == bucket }
                    .sortedBy { it.businessRole?.ordinal ?: Int.MAX_VALUE }
                val unresolved = preview?.unresolvedItems.orEmpty().filter { it.bucket == bucket }
                if (priced.isNotEmpty() || unresolved.isNotEmpty()) {
                    Text(bucketLabel(bucket), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
                for (line in priced) {
                    WorkspaceFinancialRow(line, onOpen = line.elementUuid?.let { id -> { unavailableLink = !onElement(id) } })
                }
                for (item in unresolved) {
                    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        .clickable { unavailableLink = item.elementUuid?.let(onElement) != true }
                        .padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        WorkspaceCostHelp(item.reasons.map { workspacePricingProblem(it) }.joinToString("\n"))
                    }
                }
            }
        }
    }
    if (unavailableLink) AlertDialog(onDismissRequest = { unavailableLink = false },
        text = { Text(stringResource(Res.string.p5d_no_element_link)) },
        confirmButton = { TextButton(onClick = { unavailableLink = false }) { Text(stringResource(Res.string.p5d_cancel)) } })
}

@Composable
internal fun bucketLabel(bucket: PricingBucket) = stringResource(
    if (bucket == PricingBucket.CONFIRMED) Res.string.p5d_confirmed else Res.string.p5d_discussing
)

@Composable
internal fun WorkspaceFinancialRow(line: LiveEstimateLine, onOpen: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
        .padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(line.title, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.p5d_line_rate, formatElementMilli(line.effectiveBillableQuantityMilli),
                line.unit, measurementMoney(line.unitPriceCents)), style = MaterialTheme.typography.labelSmall)
        }
        Text(measurementMoney(line.totalCents), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun WorkspaceCostHelp(text: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Info, stringResource(Res.string.p5d_cost_details), Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(text, Modifier.widthIn(max = 280.dp).padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun workspacePricingProblem(code: PricingProblemCode): String = stringResource(when (code) {
    PricingProblemCode.PARENT_UNAVAILABLE -> Res.string.p5d_pricing_parent_unavailable
    PricingProblemCode.INVALID_GEOMETRY -> Res.string.p5d_pricing_invalid_geometry
    PricingProblemCode.INVALID_SOURCE -> Res.string.p5d_pricing_invalid_source
    PricingProblemCode.STALE_SOURCE, PricingProblemCode.STALE_CATALOG -> Res.string.p5d_pricing_stale_source
    PricingProblemCode.DATABASE_UNAVAILABLE -> Res.string.p5d_pricing_database_unavailable
    PricingProblemCode.ROLE_UNDETERMINED -> Res.string.p5d_pricing_role_undetermined
    PricingProblemCode.DUPLICATE_MAIN_CANVAS -> Res.string.p5d_pricing_duplicate_main_canvas
    PricingProblemCode.DUPLICATE_PRIMARY_PROFILE -> Res.string.p5d_pricing_duplicate_primary_profile
    PricingProblemCode.CATALOG_MISSING -> Res.string.p5d_pricing_catalog_missing
    PricingProblemCode.METADATA_DRIFT -> Res.string.p5d_pricing_metadata_drift
    PricingProblemCode.UNSUPPORTED_UNIT -> Res.string.p5d_pricing_unsupported_unit
    PricingProblemCode.INVALID_PRICE -> Res.string.p5d_pricing_invalid_price
    PricingProblemCode.UNKNOWN_QUANTITY -> Res.string.p5d_pricing_unknown_quantity
    PricingProblemCode.RANGE_QUANTITY -> Res.string.p5d_pricing_range_quantity
    PricingProblemCode.QUANTITY_PRECISION -> Res.string.p5d_pricing_quantity_precision
    PricingProblemCode.INVALID_QUANTITY -> Res.string.p5d_pricing_invalid_quantity
    PricingProblemCode.SELECTION_MISSING -> Res.string.p5d_pricing_selection_missing
    PricingProblemCode.PLACEMENT_REVIEW -> Res.string.p5d_pricing_placement_review
    PricingProblemCode.PLACEMENT_UNPLACED -> Res.string.p5d_pricing_placement_unplaced
    PricingProblemCode.PLACEMENT_APPROXIMATE -> Res.string.p5d_pricing_placement_approximate
    PricingProblemCode.PLACEMENT_PARTIAL -> Res.string.p5d_pricing_placement_partial
    PricingProblemCode.MISSING_MANDATORY_CANVAS -> Res.string.p5d_pricing_missing_mandatory_canvas
    PricingProblemCode.MISSING_MANDATORY_PRIMARY_PROFILE -> Res.string.p5d_pricing_missing_mandatory_primary_profile
    PricingProblemCode.INVALID_MANDATORY_CANVAS -> Res.string.p5d_pricing_invalid_mandatory_canvas
    PricingProblemCode.INVALID_MANDATORY_PRIMARY_PROFILE -> Res.string.p5d_pricing_invalid_mandatory_primary_profile
    PricingProblemCode.MANDATORY_BASE_REQUIRED -> Res.string.p5d_pricing_mandatory_base_required
    PricingProblemCode.OVERFLOW -> Res.string.p5d_pricing_overflow
    PricingProblemCode.INVALID_OVERRIDE, PricingProblemCode.INVALID_SELECTION -> Res.string.p5d_pricing_invalid_override
    PricingProblemCode.DUPLICATE_SOURCE, PricingProblemCode.PROVENANCE_OVERLAP, PricingProblemCode.PROVENANCE_MISSING, PricingProblemCode.AMBIGUOUS_COVERAGE -> Res.string.p5d_pricing_duplicate_source
    PricingProblemCode.EMPTY_DOCUMENT -> Res.string.p5d_pricing_empty_document
    PricingProblemCode.REVISION_UNAVAILABLE -> Res.string.p5d_pricing_revision_unavailable
    PricingProblemCode.CROSS_PROJECT -> Res.string.p5d_pricing_cross_project
})
